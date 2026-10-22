package com.hongguotv.lite.uikit.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridGeometryTest {
    /** 1080p 电视上的典型值：目标卡宽 150、间距 14（密度 1 时 dp 与 px 同值）。 */
    private fun columnsAt(width: Int): Int =
        GridGeometry.columns(availableWidthPx = width, targetCardWidthPx = 150, gapPx = 14, minColumns = 3, maxColumns = 6)

    @Test
    fun `columns are derived from the available width`() {
        // 1920 屏减掉 5% 页边距后约 1730 可用：放得下 6 列。
        assertEquals(6, columnsAt(1730))
        // 窄屏放不下 6 列时自动降列，而不是把卡片压扁。
        assertEquals(4, columnsAt(700))
    }

    @Test
    fun `columns are clamped to the configured range`() {
        assertEquals(3, columnsAt(100))
        assertEquals(6, columnsAt(4_000))
    }

    @Test
    fun `card width plus gaps never exceeds the available width`() {
        for (width in listOf(640, 960, 1280, 1730, 1920, 3840)) {
            val columns = columnsAt(width)
            val card = GridGeometry.cardWidth(width, columns, gapPx = 14)
            assertTrue("宽度 $width / $columns 列：总宽超出可用宽度", columns * card + (columns - 1) * 14 <= width)
            assertTrue("卡宽必须为正", card > 0)
        }
    }

    @Test
    fun `a row that just barely fits rounds up instead of dropping a card`() {
        // B 方案内容区 800dp：5×149 + 4×14 = 801，差 1dp——按四舍五入取 5 列（实际卡宽 148），
        // 截断的话只剩 4 列，与 mockup 的"一行 5 张"对不上
        assertEquals(5, GridGeometry.columns(availableWidthPx = 800, targetCardWidthPx = 149, gapPx = 14, minColumns = 3, maxColumns = 6))
        assertEquals(148, GridGeometry.cardWidth(availableWidthPx = 800, columns = 5, gapPx = 14))
    }

    @Test
    fun `rows keep a partial last row`() {
        assertEquals(listOf(3, 3, 1), GridGeometry.rowsOf(itemCount = 7, columns = 3))
        assertEquals(listOf(6), GridGeometry.rowsOf(itemCount = 6, columns = 6))
        assertEquals(emptyList<Int>(), GridGeometry.rowsOf(itemCount = 0, columns = 6))
    }

    @Test
    fun `invalid configuration is rejected loudly`() {
        assertTrue(runCatching { GridGeometry.columns(100, 150, 14, minColumns = 0, maxColumns = 6) }.isFailure)
        assertTrue(runCatching { GridGeometry.columns(100, 150, 14, minColumns = 6, maxColumns = 3) }.isFailure)
        assertTrue(runCatching { GridGeometry.columns(100, 0, 14, minColumns = 3, maxColumns = 6) }.isFailure)
        assertTrue(runCatching { GridGeometry.cardWidth(100, 0, 14) }.isFailure)
    }

    // ---------- 聚光放大的外扩 ----------

    @Test
    fun `focus overflow covers half of the scaled growth`() {
        // 149×247 的卡片放大 1.06：横向 4.47、纵向 7.41 → 要留 8dp 白框才不会被网格裁掉
        assertEquals(8, GridGeometry.focusOverflow(149, 247, 1.06f))
    }

    @Test
    fun `focus overflow is driven by the longer side`() {
        // 3 列时卡约 480×734：纵向外扩 734×0.03 = 22.02 → 23
        assertEquals(23, GridGeometry.focusOverflow(480, 734, 1.06f))
    }

    @Test
    fun `focus overflow is zero when nothing scales`() {
        assertEquals(0, GridGeometry.focusOverflow(149, 247, 1f))
        assertEquals(0, GridGeometry.focusOverflow(149, 247, 0.5f))
    }
}

class ScrollAlignTest {

    @Test
    fun `item above the viewport scrolls up with margin`() {
        val target = ScrollAlign.target(itemTop = 120, itemBottom = 420, viewportTop = 300, viewportHeight = 600, margin = 8)
        assertEquals(112, target)
    }

    @Test
    fun `item below the viewport scrolls just enough to be visible`() {
        val target = ScrollAlign.target(itemTop = 800, itemBottom = 1_100, viewportTop = 300, viewportHeight = 600, margin = 8)
        assertEquals(508, target)
    }

    @Test
    fun `item already visible does not move the list`() {
        val target = ScrollAlign.target(itemTop = 400, itemBottom = 700, viewportTop = 300, viewportHeight = 600)
        assertEquals(null, target)
    }

    @Test
    fun `scrolling up never goes negative`() {
        val target = ScrollAlign.target(itemTop = 5, itemBottom = 200, viewportTop = 300, viewportHeight = 600, margin = 20)
        assertEquals(0, target)
    }

    @Test
    fun `unknown viewport height leaves scrolling to the layout pass`() {
        assertEquals(null, ScrollAlign.target(itemTop = 900, itemBottom = 1_200, viewportTop = 0, viewportHeight = 0))
    }
}
