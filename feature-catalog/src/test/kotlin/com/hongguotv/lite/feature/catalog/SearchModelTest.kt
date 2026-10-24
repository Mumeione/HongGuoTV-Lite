package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索页的分页状态：上游一页 10 条，翻回来的页不重复请求，重复条目去重。
 *
 * 这些是"用户按下一页会发生什么"的全部事实，纯 JVM 可断言。
 */
class SearchModelTest {

    private fun series(id: String, title: String, season: Int = 0) =
        Series(id = id, title = title, season = season)

    private fun page(
        items: List<Series>,
        page: Int = 1,
        pages: Int = 20,
        total: Int = 200,
    ) = SearchResult(items, seasonLine = emptyList(), page = page, totalPages = pages, totalCount = total)

    @Test
    fun `page one shows ten items and knows how many pages follow`() {
        val model = SearchModel()
        model.apply(page((1..10).map { series("$it", "剧$it") }), ascending = false)
        assertEquals(10, model.items.size)
        assertEquals(1, model.page)
        assertEquals(20, model.pageCount)
        assertEquals(200, model.totalCount)
        assertTrue(model.hasSearched)
    }

    @Test
    fun `turning the page replaces the grid with that page's items`() {
        val model = SearchModel()
        model.apply(page((1..10).map { series("$it", "剧$it") }, page = 1), ascending = false)
        model.apply(page((21..30).map { series("$it", "剧$it") }, page = 2), ascending = false)
        assertEquals((21..30).map { "$it" }, model.items.map { it.id })
        assertEquals(2, model.page)
    }

    @Test
    fun `overlapping items from the previous page are dropped`() {
        // 上游翻页会重叠（实测第 2 页开头重复第 1 页末尾的季）
        val model = SearchModel()
        model.apply(page(listOf(series("1", "甲"), series("2", "乙")), page = 1), ascending = false)
        model.apply(page(listOf(series("2", "乙"), series("3", "丙")), page = 2), ascending = false)
        assertEquals(listOf("3"), model.items.map { it.id })
    }

    @Test
    fun `going back to a cached page does not filter it against later pages`() {
        val model = SearchModel()
        model.apply(page(listOf(series("1", "甲"), series("2", "乙")), page = 1), ascending = false)
        model.apply(page(listOf(series("3", "丙")), page = 2), ascending = false)
        val first = model.cached(1)!!
        model.apply(first, ascending = false)
        assertEquals(listOf("1", "2"), model.items.map { it.id })
        assertEquals(1, model.page)
    }

    @Test
    fun `unseen pages are not cached`() {
        val model = SearchModel()
        model.apply(page(emptyList(), page = 1), ascending = false)
        assertNull(model.cached(2))
    }

    @Test
    fun `a new search resets paging and cache`() {
        val model = SearchModel()
        model.apply(page(listOf(series("1", "甲")), page = 3), ascending = false)
        model.clearResult()
        assertEquals(1, model.page)
        assertEquals(1, model.pageCount)
        assertTrue(model.items.isEmpty())
        assertNull(model.cached(3))
    }

    @Test
    fun `ascending order uses the season line of the page`() {
        val june = series("3", "测试书第三季", season = 3)
        val jan = series("1", "测试书第一季", season = 1)
        val other = series("9", "别的剧", season = 0)
        val model = SearchModel()
        model.apply(
            SearchResult(
                items = listOf(june, jan, other),
                seasonLine = listOf(jan, june),
                page = 1,
                totalPages = 1,
                totalCount = 3,
            ),
            ascending = true,
        )
        assertEquals(listOf("1", "3", "9"), model.items.map { it.id })
    }
}
