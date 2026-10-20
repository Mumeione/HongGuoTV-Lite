package com.hongguotv.lite.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 路由栈的行为：**只断言"一串导航动作之后顶层是什么、还能退几次"**，
 * 不关心它内部用哪种容器。
 *
 * 行为基准是旧 `MainActivity` 的 `screen` 变量与 `goBack()`：那里用字符串 + 几个布尔
 * 拼出来的返回语义，这里必须逐条对得上，且能断言。
 */
class RouteStackTest {

    private fun stack() = RouteStack()

    @Test
    fun `启动时只剩目录页`() {
        val stack = stack()
        assertEquals(Route.Catalog, stack.top)
        assertEquals(1, stack.depth)
    }

    @Test
    fun `从目录进详情再返回`() {
        val stack = stack()
        assertEquals(NavResult.MOVED, stack.openDetail("s1"))
        assertEquals(Route.Detail("s1", autoplay = false), stack.top)
        assertEquals(NavResult.MOVED, stack.back())
        assertEquals(Route.Catalog, stack.top)
    }

    @Test
    fun `详情进播放页是压栈 返回回到详情`() {
        val stack = stack()
        stack.openDetail("s1")
        assertEquals(NavResult.MOVED, stack.openPlayer("s1", 3, 12_000L))
        assertEquals(3, stack.depth)
        stack.back()
        assertEquals(Route.Detail("s1", autoplay = false), stack.top)
    }

    @Test
    fun `起播跳板进播放页是替换 返回直接回目录`() {
        val stack = stack()
        stack.openDetail("s1", autoplay = true)
        assertEquals(NavResult.MOVED, stack.openPlayer("s1", 3, 12_000L))
        // 跳板不在栈里：用户从没见过那一页，不能退回它
        assertEquals(2, stack.depth)
        assertEquals(Route.Player("s1", 3, 12_000L), stack.top)
        stack.back()
        assertEquals(Route.Catalog, stack.top)
    }

    @Test
    fun `播放页再进播放页是替换 跨季连播不叠栈`() {
        val stack = stack()
        stack.openDetail("s1")
        stack.openPlayer("s1", 9, 0L)
        assertEquals(NavResult.MOVED, stack.openPlayer("s2", 0, 0L))
        assertEquals(3, stack.depth)
        assertEquals(Route.Player("s2", 0, 0L), stack.top)
        stack.back()
        assertEquals(Route.Detail("s1", autoplay = false), stack.top)
    }

    @Test
    fun `系列页进详情再返回回到系列页`() {
        val stack = stack()
        stack.openSeries("魏家逆子成首辅")
        stack.openDetail("season-2")
        stack.back()
        assertEquals(Route.Series("魏家逆子成首辅"), stack.top)
        stack.back()
        assertEquals(Route.Catalog, stack.top)
    }

    @Test
    fun `系列页直接起播 返回回到系列页`() {
        val stack = stack()
        stack.openSeries("魏家逆子成首辅")
        stack.openPlayer("season-3", 0, 5_000L)
        stack.back()
        assertEquals(Route.Series("魏家逆子成首辅"), stack.top)
    }

    @Test
    fun `重复导航被忽略 不叠同一页`() {
        val stack = stack()
        assertEquals(NavResult.MOVED, stack.openDetail("s1"))
        // 遥控器连按：第二次必须什么都不做，否则返回要按两下才回到目录
        assertEquals(NavResult.IGNORED, stack.openDetail("s1"))
        assertEquals(2, stack.depth)
    }

    @Test
    fun `重复进同一集播放页被忽略`() {
        val stack = stack()
        stack.openDetail("s1")
        stack.openPlayer("s1", 2, 1_000L)
        assertEquals(NavResult.IGNORED, stack.openPlayer("s1", 2, 1_000L))
        assertEquals(3, stack.depth)
    }

    @Test
    fun `同一季的起播跳板与普通详情是两个路由`() {
        val stack = stack()
        stack.openDetail("s1")
        // 已经在详情页，又从历史点"继续播放"：跳板要叠上去，起播后替换成播放页，
        // 返回才回到用户刚才看的那个详情页
        assertEquals(NavResult.MOVED, stack.openDetail("s1", autoplay = true))
        stack.openPlayer("s1", 4, 0L)
        assertEquals(3, stack.depth)
        stack.back()
        assertEquals(Route.Detail("s1", autoplay = false), stack.top)
    }

    @Test
    fun `回目录会丢掉中间层`() {
        val stack = stack()
        stack.openSeries("k")
        stack.openDetail("s1")
        stack.openPlayer("s1", 0, 0L)
        assertEquals(NavResult.MOVED, stack.resetToCatalog())
        assertEquals(1, stack.depth)
        assertEquals(Route.Catalog, stack.top)
        // 已经在目录页：再回一次是空动作，不要重画
        assertEquals(NavResult.IGNORED, stack.resetToCatalog())
    }

    @Test
    fun `栈底返回是退出`() {
        val stack = stack()
        assertEquals(NavResult.EXIT, stack.back())
        stack.openDetail("s1")
        stack.back()
        assertEquals(NavResult.EXIT, stack.back())
    }

    @Test
    fun `快照从底到顶`() {
        val stack = stack()
        stack.openDetail("s1")
        stack.openPlayer("s1", 1, 0L)
        assertEquals(
            listOf<Route>(Route.Catalog, Route.Detail("s1"), Route.Player("s1", 1, 0L)),
            stack.snapshot(),
        )
    }
}
