package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索页的内存聚合状态（2026-10-24 下午规格）：
 * 条目按服务端页拉、拉过即缓存；完整剧名聚合季线后，展示序 = 季线（默认新→旧）+ 其余条目，
 * 翻页与正/倒序都在内存视图上滑窗/重排，窗口缺数据才补拉。
 *
 * 这些是"用户按下一页/切排序会发生什么"的全部事实，纯 JVM 可断言。
 */
class SearchModelTest {

    private fun series(id: String, title: String, season: Int = 0) =
        Series(id = id, title = title, season = season)

    /** 「测试书第N季」家族（seasonKey 同基名），供锚点/季线用例使用。 */
    private fun seasonSeries(n: Int) = series("$n", "测试书第${n}季", season = n)

    private fun page(
        items: List<Series>,
        page: Int = 1,
        pages: Int = 3,
        total: Int = 30,
        hasMore: Boolean = page < pages,
    ) = SearchResult(items, page = page, totalPages = pages, totalCount = total, hasMore = hasMore)

    // ---------- 纯网页结果（不聚合） ----------

    @Test
    fun `page one shows ten items and knows the estimate`() {
        val model = SearchModel()
        model.keyword = "随便"
        model.absorb(page((1..10).map { series("$it", "剧$it") }, total = 200))
        model.present(1, ascending = false)
        assertEquals(10, model.items.size)
        assertEquals(1, model.page)
        assertTrue(model.hasSearched)
        assertFalse(model.aggregated)
        assertEquals(200, model.estimatedTotal)
        assertEquals(20, model.pageCount)
    }

    @Test
    fun `turning to a fetched window reads memory without refetching`() {
        val model = SearchModel()
        model.absorb(page((1..10).map { series("$it", "剧$it") }, page = 1, pages = 3, total = 30))
        model.absorb(page((11..20).map { series("$it", "剧$it") }, page = 2, pages = 3, total = 30))
        assertTrue(model.hasWindow(2))
        // 服务端还有第 3 页：下一页仍可拉
        assertEquals(3, model.nextServerPage())
        model.present(2, ascending = false)
        assertEquals((11..20).map { "$it" }, model.items.map { it.id })
    }

    @Test
    fun `overlapping items from later pages are dropped against earlier data`() {
        // 上游翻页会重叠（实测第 2 页开头重复第 1 页末尾的季）
        val model = SearchModel()
        model.absorb(page((1..10).map { series("$it", "剧$it") }, page = 1, pages = 3, total = 30))
        model.absorb(page(listOf(series("10", "剧10"), series("11", "剧11")), page = 2, pages = 3, total = 30))
        model.present(2, ascending = false)
        // 第 1 页 10 条 + 第 2 页去重后 1 条，窗口第 2 页只多出 "11"
        assertEquals(listOf("11"), model.items.map { it.id })
    }

    @Test
    fun `re-absorbing the same page is stable`() {
        val model = SearchModel()
        model.absorb(page((1..10).map { series("$it", "剧$it") }, page = 1, pages = 2, total = 20))
        model.absorb(page(listOf(series("11", "剧11")), page = 2, pages = 2, total = 20, hasMore = false))
        model.absorb(page(listOf(series("11", "剧11")), page = 2, pages = 2, total = 20, hasMore = false))
        assertTrue(model.hasWindow(2))
        model.present(2, ascending = false)
        assertEquals(listOf("11"), model.items.map { it.id })
    }

    @Test
    fun `a new search resets paging and cache`() {
        val model = SearchModel()
        model.absorb(page(listOf(series("1", "甲")), page = 2))
        model.clearResult()
        assertEquals(1, model.page)
        assertEquals(1, model.pageCount)
        assertTrue(model.items.isEmpty())
        assertFalse(model.hasSearched)
    }

    // ---------- 锚点与聚合 ----------

    @Test
    fun `anchor matches only the full base name`() {
        val model = SearchModel()
        model.keyword = "测试书"
        model.absorb(page(listOf(seasonSeries(3), seasonSeries(1), series("9", "别的剧"))))
        assertEquals("3", model.anchor()?.id)

        // 带季号的词不是完整基名：旧仓库口径，直接回网页结果
        model.keyword = "测试书第三季"
        assertNull(model.anchor())
        model.keyword = "不相关的词"
        assertNull(model.anchor())
    }

    /** 同 IP 的无季号条目也会命中系列键：锚点必须优先取带季号的那张（它才在季线上）。 */
    @Test
    fun `anchor prefers the card that carries a season tag`() {
        val model = SearchModel()
        model.keyword = "测试书"
        model.absorb(page(listOf(series("9", "测试书"), seasonSeries(2))))
        assertEquals("2", model.anchor()?.id)
    }

    @Test
    fun `aggregated view shows only the season line`() {
        val model = SearchModel()
        model.keyword = "测试书"
        model.absorb(page(listOf(seasonSeries(3), series("9", "别的剧"), seasonSeries(1)), total = 3))
        // 季线（旧→新）来自端口；存进模型后是新→旧。聚合态**只显示季线**：
        // 完整剧名的目的就是只看这部剧，第 1 页里的杂项（"别的剧"）不混进来
        model.setSeasonLine(listOf(seasonSeries(1), seasonSeries(3)))
        model.present(1, ascending = false)
        assertEquals(listOf("3", "1"), model.items.map { it.id })
        // 聚合后不再拉服务端的杂项页
        assertNull(model.nextServerPage())
        assertFalse(model.serverHasNext)
        assertEquals(2, model.estimatedTotal)
        assertEquals(1, model.pageCount)
        // 已缓存的第一页杂项也不会在排序切换后漏回来
        model.reorder(ascending = true)
        assertEquals(listOf("1", "3"), model.items.map { it.id })
        model.reorder(ascending = false)
        assertEquals(listOf("3", "1"), model.items.map { it.id })
    }

    @Test
    fun `ascending puts oldest season first`() {
        val model = SearchModel()
        model.keyword = "测试书"
        model.absorb(page(listOf(seasonSeries(3), seasonSeries(1)), total = 3))
        model.setSeasonLine(listOf(seasonSeries(1), seasonSeries(3)))
        model.present(1, ascending = true)
        assertEquals(listOf("1", "3"), model.items.map { it.id })
    }

    @Test
    fun `season line spanning pages shows newest ten first then older ones`() {
        val model = SearchModel()
        model.keyword = "测试书"
        // 15 季、服务端到头：第 1 页 = 第 15–6 季，第 2 页 = 第 5–1 季
        val line = (1..15).map { seasonSeries(it) }
        model.absorb(page(listOf(line.last()), pages = 1, total = 15, hasMore = false))
        model.setSeasonLine(line)
        model.present(1, ascending = false)
        assertEquals((15 downTo 6).map { "$it" }, model.items.map { it.id })
        // 末页允许不满一页：窗口 2 = 季线第 11–15 项 = 第 5–1 季
        assertTrue(model.hasWindow(2))
        model.present(2, ascending = false)
        assertEquals((5 downTo 1).map { "$it" }, model.items.map { it.id })
    }

    @Test
    fun `aggregated model ignores rest pages absorbed afterwards`() {
        // 聚合后窗口缺口只可能来自"季线不足一页"：杂项页即使被吸收也不进视图
        val model = SearchModel()
        model.keyword = "测试书"
        model.absorb(page(listOf(seasonSeries(1)), pages = 3, total = 30))
        model.setSeasonLine(listOf(seasonSeries(1), seasonSeries(2)))
        model.absorb(page(listOf(series("9", "别的剧")), page = 2))
        model.present(1, ascending = false)
        assertEquals(listOf("2", "1"), model.items.map { it.id })
    }

    @Test
    fun `season line request failing falls back to plain web results`() {
        val model = SearchModel()
        model.keyword = "测试书"
        model.absorb(page(listOf(seasonSeries(1), series("9", "别的剧"))))
        // 季线请求失败 = 不调 setSeasonLine：视图就是网页原序
        model.present(1, ascending = false)
        assertFalse(model.aggregated)
        assertEquals(listOf("1", "9"), model.items.map { it.id })
    }

    @Test
    fun `single season line does not aggregate`() {
        // 页面侧判 line.seasons.size < 2 才聚合；模型只负责存，回归由页面保证
        val model = SearchModel()
        model.setSeasonLine(listOf(seasonSeries(1)))
        assertTrue(model.aggregated)
        model.clearResult()
        assertFalse(model.aggregated)
    }

    // ---------- 补拉与估计 ----------

    @Test
    fun `window beyond cache needs fetching while the server has pages`() {
        val model = SearchModel()
        model.absorb(page((1..10).map { series("$it", "剧$it") }, page = 1, pages = 5, total = 50))
        assertFalse(model.hasWindow(2))
        assertEquals(2, model.nextServerPage())
        model.absorb(page((11..20).map { series("$it", "剧$it") }, page = 2, pages = 5, total = 50))
        assertTrue(model.hasWindow(2))
        assertEquals(3, model.nextServerPage())
    }

    @Test
    fun `estimated total follows the season size when aggregated`() {
        val model = SearchModel()
        model.keyword = "测试书"
        // 服务端 total=30 与季线在第 1 页重叠，聚合后总量就是季线长度
        model.absorb(page(listOf(seasonSeries(1), seasonSeries(2), series("9", "别的剧")), total = 30))
        model.setSeasonLine(listOf(seasonSeries(1), seasonSeries(2)))
        assertEquals(2, model.estimatedTotal)
        assertEquals(1, model.pageCount)
    }

    @Test
    fun `hasNext survives a missing total via server hasMore`() {
        val model = SearchModel()
        model.absorb(page(listOf(series("1", "甲")), page = 1, pages = 9, total = 0, hasMore = true))
        assertTrue(model.hasNext)
        // 服务端到头、已知数据不足一页：没有下一页
        model.absorb(page(emptyList(), page = 9, pages = 9, total = 0, hasMore = false))
        model.present(1, ascending = false)
        assertFalse(model.hasNext)
    }
}
