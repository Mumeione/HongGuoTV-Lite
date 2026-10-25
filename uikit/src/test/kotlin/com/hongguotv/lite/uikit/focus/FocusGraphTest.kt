package com.hongguotv.lite.uikit.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusGraphTest {

    private fun key(value: String) = FocusKey(value)

    private fun keys(prefix: String, count: Int): List<FocusKey> = (0 until count).map { key("$prefix$it") }

    /** 顶栏 4 项 + 3×3 网格：模拟目录页最常见的样子。 */
    private fun catalogPlan(): FocusPlan = FocusPlan(
        listOf(
            FocusSection(keys("nav", 4), columns = 4),
            FocusSection(keys("card", 9), columns = 3),
        ),
    )

    @Test
    fun `grid moves left and right within a row`() {
        val graph = FocusGraph.of(catalogPlan())
        val middle = graph.neighborsOf(key("card4"))!!
        assertEquals(key("card3"), middle.left)
        assertEquals(key("card5"), middle.right)
        assertEquals(key("card1"), middle.up)
        assertEquals(key("card7"), middle.down)
    }

    @Test
    fun `grid row edges clamp to themselves instead of wrapping into another row`() {
        val graph = FocusGraph.of(catalogPlan())
        val rowStart = graph.neighborsOf(key("card3"))!!
        assertEquals("行首按左键应停在原地", key("card3"), rowStart.left)
        val rowEnd = graph.neighborsOf(key("card5"))!!
        assertEquals("行尾按右键应停在原地", key("card5"), rowEnd.right)
    }

    @Test
    fun `top row moves up into the section above by horizontal position`() {
        val graph = FocusGraph.of(catalogPlan())
        assertEquals(key("nav0"), graph.neighborsOf(key("card0"))!!.up)
        assertEquals(key("nav1"), graph.neighborsOf(key("card1"))!!.up)
        assertEquals(key("nav2"), graph.neighborsOf(key("card2"))!!.up)
    }

    @Test
    fun `page top and bottom clamp to themselves`() {
        val graph = FocusGraph.of(catalogPlan())
        assertEquals(key("nav0"), graph.neighborsOf(key("nav0"))!!.up)
        assertEquals(key("card8"), graph.neighborsOf(key("card8"))!!.down)
    }

    @Test
    fun `nav edges clamp and stay in the row`() {
        val graph = FocusGraph.of(catalogPlan())
        val first = graph.neighborsOf(key("nav0"))!!
        assertEquals(key("nav0"), first.left)
        assertEquals(key("nav1"), first.right)
        // 往下落网格首行的同一列附近，不是整段的第一个下标。
        assertEquals(key("card0"), first.down)
        assertEquals(key("card2"), graph.neighborsOf(key("nav3"))!!.down)
    }

    @Test
    fun `uneven last row keeps focus on existing cells and falls back to the nearest one`() {
        // 7 项 / 3 列：末行只有 1 项（index 6）。
        val graph = FocusGraph.of(FocusPlan(listOf(FocusSection(keys("c", 7), columns = 3))))
        assertEquals(key("c6"), graph.neighborsOf(key("c3"))!!.down)
        assertEquals(key("c6"), graph.neighborsOf(key("c4"))!!.down)
        assertEquals(key("c6"), graph.neighborsOf(key("c5"))!!.down)
        assertEquals("末行唯一一项按右键停在原地", key("c6"), graph.neighborsOf(key("c6"))!!.right)
        assertEquals(key("c3"), graph.neighborsOf(key("c6"))!!.up)
    }

    @Test
    fun `cross section mapping is proportional to horizontal position`() {
        // 6 列卡片行向上去 4 项导航：落在相近位置，且不越界。
        val plan = FocusPlan(
            listOf(
                FocusSection(keys("nav", 4), columns = 4),
                FocusSection(keys("card", 6), columns = 6),
            ),
        )
        val graph = FocusGraph.of(plan)
        val targets = (0 until 6).map { graph.neighborsOf(key("card$it"))!!.up.value }
        assertEquals(listOf("nav0", "nav0", "nav1", "nav1", "nav2", "nav3"), targets)
    }

    @Test
    fun `five column row moves up into a three item row on the aligned column`() {
        // 回归钉：搜索页「继续看」一行 5 张，按上要落输入行 3 项的
        // 列对齐位置——最后一列（第 5 张）必须落到第 3 项（「倒序」）。中心对齐映射下
        // 5 列 → 3 项的目标列是 [0, 0, 1, 1, 2]，若实现改回"按下标缩放"这里就会变脸。
        val plan = FocusPlan(
            listOf(
                FocusSection(keys("filter", 3), columns = 3),
                FocusSection(keys("row", 3), columns = 3),
                FocusSection(keys("card", 5), columns = 5),
            ),
        )
        val graph = FocusGraph.of(plan)
        val targets = (0 until 5).map { graph.neighborsOf(key("card$it"))!!.up }
        assertEquals(keys("row", 3).let { listOf(it[0], it[0], it[1], it[1], it[2]) }, targets)
    }

    @Test
    fun `single column section moves straight up and down`() {
        val plan = FocusPlan(
            listOf(
                FocusSection(keys("nav", 2), columns = 2),
                FocusSection(keys("row", 3), columns = 1),
            ),
        )
        val graph = FocusGraph.of(plan)
        assertEquals(key("row1"), graph.neighborsOf(key("row0"))!!.down)
        assertEquals(key("row1"), graph.neighborsOf(key("row2"))!!.up)
        assertEquals("单列段左右夹住自己", key("row1"), graph.neighborsOf(key("row1"))!!.left)
    }

    @Test
    fun `empty section is rejected because it would trap the cursor`() {
        val error = runCatching { FocusSection(emptyList(), columns = 1) }.exceptionOrNull()
        assertTrue("空段必须被拒绝", error is IllegalArgumentException)
    }

    @Test
    fun `duplicate key across sections is rejected`() {
        val plan = FocusPlan(
            listOf(
                FocusSection(listOf(key("nav0")), columns = 1),
                FocusSection(listOf(key("nav0")), columns = 1),
            ),
        )
        val error = runCatching { FocusGraph.of(plan) }.exceptionOrNull()
        assertTrue("重复 key 必须被拒绝，否则焦点会指向两个位置", error is IllegalArgumentException)
    }

    @Test
    fun `zero columns is rejected`() {
        val error = runCatching { FocusSection(keys("a", 1), columns = 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `order keeps declaration order across sections`() {
        val graph = FocusGraph.of(catalogPlan())
        assertEquals("nav0", graph.order.first().value)
        assertEquals("card8", graph.order.last().value)
        assertEquals(13, graph.size)
    }

    @Test
    fun `resolve prefers explicit target then memory then first item`() {
        val graph = FocusGraph.of(catalogPlan())
        assertEquals(key("card4"), graph.resolve(preferred = key("card4"), remembered = key("nav2")))
        assertEquals(key("nav2"), graph.resolve(preferred = key("missing"), remembered = key("nav2")))
        assertEquals(key("nav0"), graph.resolve(preferred = key("missing"), remembered = key("gone")))
    }

    @Test
    fun `unknown key has no neighbors`() {
        val graph = FocusGraph.of(FocusPlan(listOf(FocusSection(keys("only", 1), columns = 1))))
        assertEquals(key("only0"), graph.resolve())
        assertNull("图外的 key 不编造邻居", graph.neighborsOf(key("nope")))
    }

    @Test
    fun `last grid row moves down into the footer row by horizontal position`() {
        val plan = FocusPlan(
            listOf(
                FocusSection(keys("card", 9), columns = 3),
                FocusSection(listOf(key("prev"), key("next")), columns = 2),
            ),
        )
        val graph = FocusGraph.of(plan)
        assertEquals(key("prev"), graph.neighborsOf(key("card6"))!!.down)
        assertEquals(key("prev"), graph.neighborsOf(key("card7"))!!.down)
        assertEquals(key("next"), graph.neighborsOf(key("card8"))!!.down)
    }

    @Test
    fun `two grids stack on each other by column position`() {
        val plan = FocusPlan(
            listOf(
                FocusSection(keys("a", 4), columns = 2),
                FocusSection(keys("b", 6), columns = 3),
            ),
        )
        val graph = FocusGraph.of(plan)
        assertEquals("上格末行往下落到下格首行的相近列", key("b0"), graph.neighborsOf(key("a2"))!!.down)
        assertEquals(key("b1"), graph.neighborsOf(key("a3"))!!.down)
        assertEquals("下格首行往上落到上格末行", key("a2"), graph.neighborsOf(key("b0"))!!.up)
        assertEquals(key("a3"), graph.neighborsOf(key("b2"))!!.up)
    }

    // ---------- 侧段（B 方案的左侧栏：竖排，左右与内容互通） ----------

    /** 左侧栏 4 项（竖排）+ 2 行 × 3 列内容：模拟 B 方案的页面骨架。 */
    private fun railPlan(): FocusPlan = FocusPlan(
        listOf(FocusSection(keys("card", 6), columns = 3)),
        side = FocusSection(keys("nav", 4), columns = 1),
    )

    @Test
    fun `rail moves up and down within itself and clamps at both ends`() {
        val graph = FocusGraph.of(railPlan())
        assertEquals(key("nav1"), graph.neighborsOf(key("nav0"))!!.down)
        assertEquals(key("nav2"), graph.neighborsOf(key("nav1"))!!.down)
        assertEquals(key("nav1"), graph.neighborsOf(key("nav2"))!!.up)
        assertEquals("侧栏顶端按上键停在原地", key("nav0"), graph.neighborsOf(key("nav0"))!!.up)
        assertEquals("侧栏底端按下键停在原地", key("nav3"), graph.neighborsOf(key("nav3"))!!.down)
    }

    @Test
    fun `rail exits right into the first content section by vertical position`() {
        val graph = FocusGraph.of(railPlan())
        assertEquals("侧栏上部往右进内容首行行首", key("card0"), graph.neighborsOf(key("nav0"))!!.right)
        assertEquals("侧栏下部往右进内容末行行首", key("card3"), graph.neighborsOf(key("nav3"))!!.right)
        assertEquals("侧栏最左没有更左，夹住自己", key("nav1"), graph.neighborsOf(key("nav1"))!!.left)
    }

    @Test
    fun `content exits left into the rail by vertical position`() {
        val graph = FocusGraph.of(railPlan())
        assertEquals("内容首行行首往左回侧栏上部", key("nav0"), graph.neighborsOf(key("card0"))!!.left)
        assertEquals("内容末行行首往左回侧栏下部", key("nav2"), graph.neighborsOf(key("card3"))!!.left)
        assertEquals("非行首按左键仍在行内（只有行首才出侧栏）", key("card0"), graph.neighborsOf(key("card1"))!!.left)
    }

    @Test
    fun `content exits left into the anchored rail item regardless of row`() {
        // 目录页的一行卡（画面1/2/4）：按竖直位置映射会落到侧栏中段，
        // 用户按左键只是想回自己那一页的导航项，不该被带去别的 tab。
        val plan = FocusPlan(
            listOf(FocusSection(keys("card", 6), columns = 3)),
            side = FocusSection(keys("nav", 4), columns = 1),
            sideAnchor = key("nav2"),
        )
        val graph = FocusGraph.of(plan)
        assertEquals("首行行首按左回当前 tab", key("nav2"), graph.neighborsOf(key("card0"))!!.left)
        assertEquals("第二行行首也回当前 tab", key("nav2"), graph.neighborsOf(key("card3"))!!.left)
        assertEquals("非行首仍在行内", key("card0"), graph.neighborsOf(key("card1"))!!.left)
    }

    @Test
    fun `anchor outside the rail falls back to vertical position`() {
        val plan = FocusPlan(
            listOf(FocusSection(keys("card", 6), columns = 3)),
            side = FocusSection(keys("nav", 4), columns = 1),
            sideAnchor = key("nav9"),
        )
        val graph = FocusGraph.of(plan)
        assertEquals("锚点不在侧段里就当没给", key("nav0"), graph.neighborsOf(key("card0"))!!.left)
    }

    @Test
    fun `rail order comes first for focus fallback`() {
        val graph = FocusGraph.of(railPlan())
        assertEquals("侧栏是页面的第一段（空页兜底落在导航上）", "nav0", graph.order.first().value)
        assertEquals("card5", graph.order.last().value)
        assertEquals(10, graph.size)
    }

    @Test
    fun `stacked sections still stack among themselves when a rail exists`() {
        val plan = FocusPlan(
            listOf(
                FocusSection(keys("filter", 3), columns = 3),
                FocusSection(keys("card", 6), columns = 3),
            ),
            side = FocusSection(keys("nav", 4), columns = 1),
        )
        val graph = FocusGraph.of(plan)
        assertEquals("段间上下穿行不受侧栏影响", key("filter0"), graph.neighborsOf(key("card0"))!!.up)
        assertEquals("侧栏往右永远进第一段内容", key("filter0"), graph.neighborsOf(key("nav0"))!!.right)
        assertEquals("页面顶部的段往上夹住自己（侧栏在左边，不在上边）", key("filter1"), graph.neighborsOf(key("filter1"))!!.up)
    }

    // ---------- 段级出口（宽窄不一的控件行） ----------

    /**
     * 搜索页的片库胶囊只占左边半屏，下面一行是「输入框 + 搜索 + 排序」。
     * 按列号比例映射时，第二个胶囊会落到「搜索」按钮上；页面指名"按下 → 输入框"。
     */
    @Test
    fun `section down override wins over column mapping`() {
        val plan = FocusPlan(
            listOf(
                FocusSection(keys("chip", 3), columns = 3, down = key("input")),
                FocusSection(listOf(key("input"), key("go"), key("sort")), columns = 3),
                FocusSection(keys("card", 10), columns = 5),
            ),
            side = FocusSection(keys("nav", 4), columns = 1),
        )
        val graph = FocusGraph.of(plan)
        key("chip0").let { assertEquals(key("input"), graph.neighborsOf(it)!!.down) }
        assertEquals("第二个胶囊也必须进输入框", key("input"), graph.neighborsOf(key("chip1"))!!.down)
        assertEquals("第三个胶囊也一样", key("input"), graph.neighborsOf(key("chip2"))!!.down)
        // 覆盖只作用于末行：输入行自己的下移仍按列号落到结果卡
        assertEquals(key("card0"), graph.neighborsOf(key("input"))!!.down)
        assertEquals("中间按钮映射到中间那张卡", key("card2"), graph.neighborsOf(key("go"))!!.down)
    }

    /** 视觉顺序必须与声明顺序一致：翻组键在左、排序在右时，段里也按这个顺序声明。 */
    @Test
    fun `horizontal order is the declared order`() {
        val plan = FocusPlan(listOf(FocusSection(listOf(key("prev"), key("next"), key("sort")), columns = 3)))
        val graph = FocusGraph.of(plan)
        assertEquals(key("next"), graph.neighborsOf(key("prev"))!!.right)
        assertEquals(key("sort"), graph.neighborsOf(key("next"))!!.right)
        assertEquals(key("next"), graph.neighborsOf(key("sort"))!!.left)
    }

    /**
     * 禁用的按钮仍然占着它的列：季线页第一页没有「上一组」，
     * 「下一组」仍在第二列——从它往下要落到第 4 张（1 起算），不是第 2 张。
     */
    @Test
    fun `a disabled control keeps its column in the row`() {
        val plan = FocusPlan(
            listOf(
                FocusSection(listOf(null, key("next"), key("sort")), columns = 3),
                FocusSection(keys("card", 8), columns = 8),
            ),
        )
        val graph = FocusGraph.of(plan)
        assertEquals(key("card3"), graph.neighborsOf(key("next"))!!.down)
        assertEquals(key("sort"), graph.neighborsOf(key("next"))!!.right)
        assertEquals(key("next"), graph.neighborsOf(key("sort"))!!.left)
        // 左边界：第一个可聚焦项的左邻是空槽 → 不跳回行尾
        assertEquals(key("next"), graph.neighborsOf(key("next"))!!.left)
    }

    @Test
    fun `leftmost control goes to the first cell of the row below`() {
        val plan = FocusPlan(
            listOf(
                FocusSection(listOf(key("prev"), key("next"), key("sort")), columns = 3),
                FocusSection(keys("card", 8), columns = 8),
            ),
        )
        assertEquals(key("card0"), FocusGraph.of(plan).neighborsOf(key("prev"))!!.down)
    }

    @Test
    fun `null slots do not appear in the graph order`() {
        val graph = FocusGraph.of(FocusPlan(listOf(FocusSection(listOf(null, key("only")), columns = 2))))
        assertEquals(listOf(key("only")), graph.order)
    }
}
