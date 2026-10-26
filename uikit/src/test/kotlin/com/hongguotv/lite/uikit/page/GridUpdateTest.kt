package com.hongguotv.lite.uikit.page

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同 id 集合只有顺序变化时，网格应当**重排**而不是整树重建：
 * 重建会暂离焦点视图、触发一次被动落焦，滚动位置也可能被重新夹取。
 *
 * 只有集合、列数或卡宽真的变了才重建（卡宽变了封面解码尺寸也就变了）。
 */
class GridUpdateTest {

    private val a = "a"
    private val b = "b"
    private val c = "c"

    private fun mode(
        lastIds: List<String>,
        ids: List<String>,
        lastColumns: Int = 5,
        columns: Int = 5,
        lastCardWidth: Int = 200,
        cardWidth: Int = 200,
        hasCards: Boolean = true,
    ) = GridUpdate.mode(lastIds, ids, lastColumns, columns, lastCardWidth, cardWidth, hasCards)

    @Test
    fun `same ids in the same order only rebind cards`() {
        assertEquals(GridUpdate.Mode.UPDATE, mode(listOf(a, b, c), listOf(a, b, c)))
    }

    @Test
    fun `an order-only change reorders the existing cards`() {
        assertEquals(GridUpdate.Mode.REORDER, mode(listOf(a, b, c), listOf(c, a, b)))
        assertEquals(GridUpdate.Mode.REORDER, mode(listOf(a, b, c), listOf(c, b, a)))
    }

    @Test
    fun `a different id set rebuilds`() {
        assertEquals(GridUpdate.Mode.REBUILD, mode(listOf(a, b, c), listOf(a, b, "d")))
        assertEquals(GridUpdate.Mode.REBUILD, mode(listOf(a, b), listOf(a, b, c)))
    }

    @Test
    fun `a column or card width change rebuilds even with the same ids`() {
        assertEquals(GridUpdate.Mode.REBUILD, mode(listOf(a, b, c), listOf(c, a, b), columns = 4))
        assertEquals(GridUpdate.Mode.REBUILD, mode(listOf(a, b, c), listOf(c, a, b), cardWidth = 180))
    }

    @Test
    fun `the first render always builds`() {
        assertEquals(GridUpdate.Mode.REBUILD, mode(emptyList(), listOf(a), hasCards = false))
        assertEquals(GridUpdate.Mode.REBUILD, mode(emptyList(), emptyList(), hasCards = false))
    }
}
