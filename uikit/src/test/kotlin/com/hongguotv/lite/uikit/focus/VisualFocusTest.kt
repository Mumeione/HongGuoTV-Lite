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

    /**
     * 2026-10-24 验收：详情页「‹ 返回」按右，落到了斜下方的第二集——
     * 它离得近，但视觉上右边那一列是「继续播放」。
     *
     * 规则：先看 45° 视线锥（左右方向 `|dy| ≤ |dx|`）。锥内有候选就只在锥内挑，
     * 锥内没有才退回全方向最近。
     */
    @Test
    fun `back goes right to the action column even when episode cells sit closer below`() {
        val back = rect("back", 24, 24, 120, 58)
        val play = rect("play", 780, 128, 1010, 168)
        val pick = rect("pick", 780, 176, 1010, 216)
        val episodes = (0 until 5).map { i -> rect("ep:$i", 100 + i * 50, 400, 145 + i * 50, 426) }
        val all = listOf(back, play, pick) + episodes

        assertEquals("返回的正右方是动作列，不是斜下方的第二集", "play", move(back, all, FocusDirection.RIGHT))
        // 集数格的第一行往上仍然回到页头（锥内只有它）
        assertEquals("back", move(episodes[0], all, FocusDirection.UP))
    }

    @Test
    fun `outside the cone it still falls back to the nearest in that direction`() {
        val self = rect("self", 100, 100, 200, 140)
        // 右上方很远：不在 45° 锥里，但这是"上"方向唯一的候选
        val aboveFarRight = rect("above", 900, 20, 1000, 54)
        assertEquals("above", move(self, listOf(self, aboveFarRight), FocusDirection.UP))
    }

    /**
     * 详情页动作列左边是封面与简介（不可聚焦），几何上最近的是侧栏。
     * 页面声明了出口：动作列按左 → 页头返回，不再「按左跑出这一页」。
     */
    @Test
    fun `a declared exit wins over the rail on the left`() {
        val play = rect("play", 780, 128, 1010, 168)
        val nav = rect("nav:1", 8, 120, 64, 174)
        val back = rect("back", 24, 24, 120, 58)
        val all = listOf(play, nav, back)
        val exits = FocusExits.left(FocusKey("back"), listOf(FocusKey("play")))

        // 没有出口时，几何最近项就是侧栏（这就是"按左跑出这一页"）
        assertEquals("nav:1", move(play, all, FocusDirection.LEFT))
        assertEquals("back", VisualFocus.neighbor(play, all, FocusDirection.LEFT, exits)?.value)
    }

    @Test
    fun `an exit whose target is not on screen falls back to geometry`() {
        val play = rect("play", 780, 128, 1010, 168)
        val nav = rect("nav:1", 8, 120, 64, 174)
        val all = listOf(play, nav)
        val exits = FocusExits.left(FocusKey("back"), listOf(FocusKey("play")))

        assertEquals("nav:1", VisualFocus.neighbor(play, all, FocusDirection.LEFT, exits)?.value)
    }

    /**
     * 季线页的真实形状：排序在季卡上方偏左，动作列在右上。
     * 右侧季卡按上会按几何落到动作列；页面声明后固定回「排序」，「排序」按下固定回第一张季卡。
     */
    @Test
    fun `season cards go fixedly up to the sort control and sort goes down to the first card`() {
        val sort = rect("series:sort", 100, 430, 200, 470)
        val play = rect("series:play", 780, 120, 1010, 160)
        val pick = rect("series:pick", 780, 168, 1010, 208)
        val favorite = rect("series:favorite", 780, 216, 1010, 256)
        val cards = (1..5).map { i -> rect("season:$i", 300 + (i - 1) * 100, 500, 390 + (i - 1) * 100, 626) }
        val all = listOf(sort, play, pick, favorite) + cards
        val exits = FocusExits.up(FocusKey("series:sort"), cards.map { it.key }) +
            FocusExits.down(FocusKey("season:1"), listOf(FocusKey("series:sort")))

        // 没有出口时：靠右的季卡按上按几何落到动作列（"取决于列位置"就是这条）
        assertEquals("series:favorite", move(cards.last(), all, FocusDirection.UP))
        cards.forEach { card ->
            assertEquals(
                "${card.key.value} 按上固定到「排序」",
                "series:sort",
                VisualFocus.neighbor(card, all, FocusDirection.UP, exits)?.value,
            )
        }
        assertEquals(
            "「排序」按下固定回第一张季卡",
            "season:1",
            VisualFocus.neighbor(sort, all, FocusDirection.DOWN, exits)?.value,
        )
    }

    @Test
    fun `an up exit whose target is gone falls back to geometry`() {
        val card = rect("season:2", 400, 500, 490, 626)
        val play = rect("series:play", 780, 120, 1010, 160)
        val all = listOf(card, play)
        val exits = FocusExits.up(FocusKey("series:sort"), listOf(card.key))

        assertEquals("排序键不在屏上（季线还没到）：仍按几何", "series:play", VisualFocus.neighbor(card, all, FocusDirection.UP, exits)?.value)
    }

    /** 出口只改动作列：侧栏仍可从页头或内容最左缘进入（A 口径）。 */
    @Test
    fun `the rail is still reachable from the page header and the content edge`() {
        val back = rect("back", 24, 24, 120, 58)
        val nav = rect("nav:1", 8, 120, 64, 174)
        val firstCell = rect("ep:0", 100, 400, 145, 426)
        val all = listOf(back, nav, firstCell)

        assertEquals("页头按左进侧栏", "nav:1", move(back, all, FocusDirection.LEFT))
        assertEquals("单页分集格（无长条）按左进侧栏", "nav:1", move(firstCell, all, FocusDirection.LEFT))
    }
}
