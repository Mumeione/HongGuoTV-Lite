package com.hongguotv.lite.uikit.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 视觉方向导航：屏幕上的上下左右，就是焦点移动的上下左右。
 *
 * 用矩形喂纯函数，不启动 Activity。这里的布局照着详情页、季线页与播放抽屉的真实形状写。
 * 生产绑定只走 [VisualFocus.pickVisual]，因此用例也钉这一条：出口 → 锥内几何 → 语义落点。
 */
class VisualFocusTest {

    private fun rect(key: String, left: Int, top: Int, right: Int, bottom: Int) =
        FocusRect(FocusKey(key), left, top, right, bottom)

    private fun move(
        from: FocusRect,
        all: List<FocusRect>,
        direction: FocusDirection,
        exits: FocusExits = FocusExits.NONE,
        sideKeys: Set<FocusKey> = emptySet(),
        semantic: String? = null,
    ): String? =
        VisualFocus.pickVisual(
            self = from,
            all = all,
            direction = direction,
            exits = exits,
            sideKeys = sideKeys,
            semantic = semantic?.let(::FocusKey),
        )?.value

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

        // 继续播放上方只有返回（锥内没有同一列的项）：焦点图的语义落点就是返回
        assertEquals("back", move(play, all, FocusDirection.UP, semantic = "back"))
        // 继续播放往左：同一高度的导航项（贴左缘）在锥内，视觉上就是左边那一列
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
        assertNull("右边没有别的项", move(strip, all, FocusDirection.RIGHT))
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
     * 详情页「‹ 返回」按右：斜下方第二集离得近，但视觉上右边那一列是「继续播放」。
     *
     * 规则：先看 45° 视线锥（左右方向 `|dy| ≤ |dx|`）。锥内有候选就只在锥内挑，
     * 锥内没有才回焦点图的语义落点。
     */
    @Test
    fun `back goes right to the action column even when episode cells sit closer below`() {
        val back = rect("back", 24, 24, 120, 58)
        val play = rect("play", 780, 128, 1010, 168)
        val pick = rect("pick", 780, 176, 1010, 216)
        val episodes = (0 until 5).map { i -> rect("ep:$i", 100 + i * 50, 400, 145 + i * 50, 426) }
        val all = listOf(back, play, pick) + episodes

        assertEquals("返回的正右方是动作列，不是斜下方的第二集", "play", move(back, all, FocusDirection.RIGHT))
        // 集数格的第一行往上：锥内没有候选，语义落点回到页头
        assertEquals("back", move(episodes[0], all, FocusDirection.UP, semantic = "back"))
    }

    @Test
    fun `outside the cone it uses the semantic target instead of a far jump`() {
        val self = rect("self", 100, 100, 200, 140)
        // 右上方很远：不在 45° 锥里
        val aboveFarRight = rect("above", 900, 20, 1000, 54)
        val all = listOf(self, aboveFarRight)

        assertNull("锥外只有几何候选时夹住自己", move(self, all, FocusDirection.UP))
        assertEquals("锥外有语义目标时回语义落点", "above", move(self, all, FocusDirection.UP, semantic = "above"))
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
        assertEquals("back", move(play, all, FocusDirection.LEFT, exits))
    }

    @Test
    fun `an exit whose target is not on screen falls back to geometry`() {
        val play = rect("play", 780, 128, 1010, 168)
        val nav = rect("nav:1", 8, 120, 64, 174)
        val all = listOf(play, nav)
        val exits = FocusExits.left(FocusKey("back"), listOf(FocusKey("play")))

        assertEquals("nav:1", move(play, all, FocusDirection.LEFT, exits))
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
                move(card, all, FocusDirection.UP, exits),
            )
        }
        assertEquals(
            "「排序」按下固定回第一张季卡",
            "season:1",
            move(sort, all, FocusDirection.DOWN, exits),
        )
    }

    @Test
    fun `an up exit whose target is gone falls back to the semantic target`() {
        val card = rect("season:2", 400, 500, 490, 626)
        val play = rect("series:play", 780, 120, 1010, 160)
        val all = listOf(card, play)
        val exits = FocusExits.up(FocusKey("series:sort"), listOf(card.key))

        assertEquals(
            "排序键不在屏上（季线还没到）：回焦点图的语义落点，不按几何远跳",
            "series:play",
            move(card, all, FocusDirection.UP, exits, semantic = "series:play"),
        )
    }

    /**
     * 内容项的几何候选**不含左侧栏**：分集格行首即使紧挨着侧栏，也不会掉进「设置」。
     * 这条用例把侧栏放在锥内，删掉 [VisualFocus.pickVisual] 的过滤就会变红。
     */
    @Test
    fun `the rail is never a geometric candidate for content items`() {
        val nav = rect("nav:1", 30, 400, 86, 426)
        val firstCell = rect("ep:0", 100, 400, 145, 426)
        val all = listOf(nav, firstCell)
        val side = setOf(nav.key)

        assertEquals("裸几何会进侧栏", "nav:1", move(firstCell, all, FocusDirection.LEFT))
        assertEquals(
            "排除侧栏后锥内没有候选：回语义落点（自身），不撞设置",
            "ep:0",
            move(firstCell, all, FocusDirection.LEFT, sideKeys = side, semantic = "ep:0"),
        )
    }

    /** 页头按左：侧栏在锥内也不走几何，按焦点图的当前 tab 落。 */
    @Test
    fun `the header still enters the rail through the semantic target`() {
        val nav = rect("nav:1", 0, 24, 56, 58)
        val back = rect("back", 80, 24, 176, 58)
        val all = listOf(nav, back)

        assertEquals(
            "当前 tab 由焦点图指名",
            "nav:1",
            move(back, all, FocusDirection.LEFT, sideKeys = setOf(nav.key), semantic = "nav:1"),
        )
    }

    /** 返回按下：页头下方只有封面与分集格，声明出口后固定回主操作。 */
    @Test
    fun `back down uses the declared exit instead of the episode grid or the rail`() {
        val back = rect("back", 24, 24, 120, 58)
        val nav = rect("nav:1", 8, 120, 64, 174)
        val play = rect("play", 780, 128, 1010, 168)
        val ep1 = rect("ep:0", 100, 400, 145, 426)
        val all = listOf(back, nav, play, ep1)
        val exits = FocusExits.down(FocusKey("play"), listOf(FocusKey("back")))

        assertEquals(
            "play",
            move(back, all, FocusDirection.DOWN, exits, setOf(FocusKey("nav:1"))),
        )
    }

    /** 单页网格没有长条：行缘夹住自己，不撞动作列。 */
    @Test
    fun `row edges clamp when no strip is on screen`() {
        val first = rect("ep:1", 100, 500, 145, 526)
        val last = rect("ep:20", 900, 500, 945, 526)
        val play = rect("play", 1100, 300, 1300, 340)
        val all = listOf(first, last, play)
        val exits = FocusExits.clampLeft(listOf(first.key)) + FocusExits.clampRight(listOf(last.key))

        assertEquals("行首按左夹住自己", "ep:1", move(first, all, FocusDirection.LEFT, exits))
        assertEquals("行尾按右夹住自己，不去右上方的动作列", "ep:20", move(last, all, FocusDirection.RIGHT, exits))
    }

    /** 「排序」左右都没有同一行的项：两侧都夹住自己。 */
    @Test
    fun `sort clamps on both horizontal edges`() {
        val sort = rect("series:sort", 100, 430, 200, 470)
        val card4 = rect("season:4", 500, 500, 590, 626)
        val play = rect("series:play", 900, 120, 1010, 160)
        val all = listOf(sort, card4, play)
        val exits = FocusExits.left(sort.key, listOf(sort.key)) + FocusExits.right(sort.key, listOf(sort.key))

        assertEquals("series:sort", move(sort, all, FocusDirection.RIGHT, exits))
        assertEquals("series:sort", move(sort, all, FocusDirection.LEFT, exits))
    }
}
