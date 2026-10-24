package com.hongguotv.lite.uikit.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 视觉方向导航：屏幕上的上下左右，就是焦点移动的上下左右。
 *
 * 用矩形喂纯函数，不启动 Activity。这里的布局照着详情页与播放抽屉的真实形状写。
 */
class VisualFocusTest {

    private fun rect(key: String, left: Int, top: Int, right: Int, bottom: Int) =
        FocusRect(FocusKey(key), left, top, right, bottom)

    private fun move(from: FocusRect, all: List<FocusRect>, direction: FocusDirection): String? =
        VisualFocus.neighbor(from, all, direction)?.value

    @Test
    fun `back goes right to the first action below the title`() {
        val back = rect("back", 120, 24, 200, 58)
        val play = rect("play", 800, 70, 968, 110)
        val pick = rect("pick", 800, 118, 968, 158)
        val favorite = rect("favorite", 800, 166, 968, 206)
        val all = listOf(back, play, pick, favorite)

        assertEquals("返回的右侧没有按键，但视觉上右边是继续播放", "play", move(back, all, FocusDirection.RIGHT))
    }

    @Test
    fun `actions move vertically and come back to the title row on the left`() {
        val back = rect("back", 120, 24, 200, 58)
        val play = rect("play", 800, 70, 968, 110)
        val pick = rect("pick", 800, 118, 968, 158)
        val favorite = rect("favorite", 800, 166, 968, 206)
        val rail = rect("nav:0", 8, 20, 64, 74)
        val all = listOf(rail, back, play, pick, favorite)

        // 继续播放上方只有返回（同一高度的导航项更低更远），视觉上就是"往左上"
        assertEquals("back", move(play, all, FocusDirection.UP))
        // 继续播放往左：同一高度的导航项（贴左缘）比远处的返回更近——视觉上就是左边那一列
        assertEquals("nav:0", move(play, all, FocusDirection.LEFT))
        assertEquals("pick", move(play, all, FocusDirection.DOWN))
        assertEquals("favorite", move(pick, all, FocusDirection.DOWN))
        assertEquals("pick", move(favorite, all, FocusDirection.UP))
    }

    @Test
    fun `season cards own the columns and the side strips own the row edges`() {
        val left = rect("prev", 250, 500, 290, 626)
        val card1 = rect("season:1", 300, 500, 390, 626)
        val card2 = rect("season:2", 400, 500, 490, 626)
        val card3 = rect("season:3", 500, 500, 590, 626)
        val right = rect("next", 900, 500, 940, 626)
        val all = listOf(left, card1, card2, card3, right)

        assertEquals("行首按左落到长条", "prev", move(card1, all, FocusDirection.LEFT))
        assertEquals("行内按右落到下一张", "season:2", move(card1, all, FocusDirection.RIGHT))
        assertEquals("行尾按右落到长条", "next", move(card3, all, FocusDirection.RIGHT))
        assertEquals("长条按右回到最近的一行", "season:1", move(left, all, FocusDirection.RIGHT))
        assertNull("长条按左没有别的项就夹住自己", move(left, all, FocusDirection.LEFT))
    }

    @Test
    fun `a tall strip is entered from any row and returns to the nearest row`() {
        // 播放抽屉：10 列分集格 + 右侧竖条
        val cells = (0 until 3).flatMap { row ->
            (0 until 10).map { column ->
                rect("ep:${row * 10 + column}", 100 + column * 60, 100 + row * 40, 150 + column * 60, 126 + row * 40)
            }
        }
        val strip = rect("next", 720, 100, 760, 220)
        val all = cells + strip

        assertEquals("最后一格按右进长条", "next", move(cells[9], all, FocusDirection.RIGHT))
        val fromStrip = move(strip, all, FocusDirection.RIGHT)
        assertNull("右边没有别的项", fromStrip)
        assertEquals("长条按左回到竖直方向最近的那一格（中间行末列）", "ep:19", move(strip, all, FocusDirection.LEFT))
    }

    @Test
    fun `nothing in a direction means clamp not a wild guess`() {
        val only = rect("only", 100, 100, 200, 200)
        FocusDirection.entries.forEach { direction ->
            assertNull("$direction 没有候选时必须返回 null（调用方夹住自己）", move(only, listOf(only), direction))
        }
        // 在上方的东西不能算"往下走"
        val above = rect("above", 100, 0, 200, 50)
        assertNull(move(only, listOf(above), FocusDirection.DOWN))
        assertEquals("above", move(only, listOf(above), FocusDirection.UP))
    }

    @Test
    fun `the score prefers the same row over a nearer but diagonal item`() {
        val self = rect("self", 100, 100, 200, 140)
        val sameRowFar = rect("same", 500, 100, 600, 140)
        val diagonalNear = rect("diagonal", 220, 200, 320, 240)
        assertEquals("same", move(self, listOf(sameRowFar, diagonalNear), FocusDirection.RIGHT))
    }
}
