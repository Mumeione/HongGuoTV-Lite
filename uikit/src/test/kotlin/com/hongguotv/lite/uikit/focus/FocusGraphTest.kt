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
}
