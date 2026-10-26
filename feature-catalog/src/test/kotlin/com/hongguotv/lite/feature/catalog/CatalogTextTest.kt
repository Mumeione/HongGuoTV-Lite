package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.lite.uikit.text.TvText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目录页的文案派生：用户看到什么，就在这些断言里。时间/季号/失败文案的口径在 uikit 的 `TvTextTest`。 */
class CatalogTextTest {

    private fun series(id: String, title: String = "剧$id", season: Int = 0) = Series(id = id, title = title, season = season)

    @Test
    fun emptySearchSuggestsSwitchingTheFilterFirst() {
        assertEquals("输入完整剧名，按确认开始搜索", CatalogText.emptySearch("", ContentFilter.ALL))
        assertTrue(CatalogText.emptySearch("凡人", ContentFilter.SHORT).contains("切换到「全部」"))
        assertEquals("没有找到相关剧集，试试输入完整剧名", CatalogText.emptySearch("凡人", ContentFilter.ALL))
    }

    @Test
    fun riskControlEmptyBodyIsNeverReportedAsNoResult() {
        val blocked = TvText.error(UpstreamError.EmptyBody())
        assertEquals("请求被临时限制，请稍后重试。", blocked)
        // 风控与"没有结果"必须是两句不同的话，否则用户会去换剧名而不是等一会儿
        assertNotEquals(CatalogText.emptySearch("凡人", ContentFilter.ALL), blocked)
    }

    @Test
    fun searchBadgeKeepsThePlainCountForWebResults() {
        assertEquals("全 352 集", CatalogText.searchBadge(Series(id = "1", title = "剧", episodeCount = 352), aggregated = false))
        // 没有集数的条目不编一句空话
        assertEquals("", CatalogText.searchBadge(series("2"), aggregated = false))
    }

    @Test
    fun searchBadgeMarksTheSeasonForAggregatedCards() {
        // 聚合态：季线卡带「第N季」，不用聚焦就能分清同一部剧的各季
        val s9 = Series(id = "9", title = "剧第九季", season = 9, episodeCount = 120)
        assertEquals("第九季 · 全 120 集", CatalogText.searchBadge(s9, aggregated = true))
        assertEquals("第九季", CatalogText.searchBadge(Series(id = "9", title = "剧第九季", season = 9), aggregated = true))
        // 单季剧（无季号）维持"全 N 集"
        assertEquals("全 80 集", CatalogText.searchBadge(Series(id = "5", title = "剧", episodeCount = 80), aggregated = true))
    }

    @Test
    fun searchBadgeKeepsUnreleasedAsIs() {
        // 未上线永远只写「未上线」，聚合与否都不变
        val unreleased = Series(id = "16", title = "剧第十六季", season = 16, released = false)
        assertEquals("未上线", CatalogText.searchBadge(unreleased, aggregated = true))
        assertEquals("未上线", CatalogText.searchBadge(unreleased, aggregated = false))
    }

    // ---------- 底部信息栏（B 方案） ----------

    @Test
    fun infoBandOnlyStatesTheSeriesAndItsFacts() {
        // B 方案口径："信息栏只写信息和收藏状态、更新情况，不写观看记录"
        // 搜索/历史结果：片名 + 集数 + 已收藏 + 分类（我的状态在卡片与详情页说）
        assertEquals(
            listOf("全 90 集", "已收藏", "古装 / 逆袭"),
            CatalogText.bandSegments(favorite = true, tags = "古装 / 逆袭", badge = "全 90 集"),
        )
        assertEquals(listOf("古装 / 逆袭"), CatalogText.bandSegments(favorite = false, tags = "古装 / 逆袭"))
        assertEquals(emptyList<String>(), CatalogText.bandSegments(favorite = false, tags = ""))
        // 集数段没有就跳过（早期记录/plan 卡可能没有 badge）
        assertEquals(listOf("已收藏", "古装 / 逆袭"), CatalogText.bandSegments(favorite = true, tags = "古装 / 逆袭"))
        // 上游给的分隔符是「 · 」（详情页那套），信息栏按 mockup 用「 / 」读作"分类"
        assertEquals(listOf("逆袭 / 朝堂权谋"), CatalogText.bandSegments(favorite = false, tags = "逆袭 · 朝堂权谋"))
    }

    @Test
    fun favoriteInfoBandStatesTheWatchProgress() {
        // 收藏页：片名 + 观看到… + 分类（2026-10-24 验收：不再写更新集数）
        assertEquals(
            listOf("观看到 第九季 第20集", "玄幻 / 逆袭"),
            CatalogText.favoriteSegments(progress = "观看到 第九季 第20集", tags = "玄幻 · 逆袭"),
        )
        // 没有观看记录时只报分类，不编一句空话
        assertEquals(listOf("玄幻 / 逆袭"), CatalogText.favoriteSegments(progress = "", tags = "玄幻 · 逆袭"))
        assertEquals(emptyList<String>(), CatalogText.favoriteSegments(progress = "", tags = ""))
    }

    @Test
    fun historyBandTagsFallBackToTheFavoriteCopy() {
        // 历史记录的 tags 经常是空串（早期记录只存了片名/封面），
        // 分类回退到收藏那份；两处都没有就不显示——角标（「全 90 集」）不是分类，不能兜底
        assertEquals("古装 / 逆袭", CatalogText.historyTags(historyTags = "古装 / 逆袭", favoriteTags = "已丢失的旧分类"))
        assertEquals("古装 / 逆袭", CatalogText.historyTags(historyTags = "", favoriteTags = "古装 / 逆袭"))
        assertEquals("", CatalogText.historyTags(historyTags = "", favoriteTags = ""))
    }

    @Test
    fun searchBandUsesTheSameBadgeAsTheCard() {
        // 聚合态：卡片写「第N季 · 全 N 集」，信息栏不能再写"全 N 集"（同一部剧两种说法）
        val s9 = Series(id = "9", title = "剧第九季", season = 9, episodeCount = 120)
        assertEquals(
            listOf("第九季 · 全 120 集", "已收藏", "古装 / 逆袭"),
            CatalogText.searchSegments(s9, aggregated = true, favorite = true, tags = "古装 · 逆袭"),
        )
        // 模糊词的网页结果维持"全 N 集"
        assertEquals(
            listOf("全 120 集"),
            CatalogText.searchSegments(s9, aggregated = false, favorite = false, tags = ""),
        )
    }

    @Test
    fun searchRangeSaysPageAndTotal() {
        assertEquals("第 1 / 20 页 · 共 200 条", CatalogText.searchRange(page = 1, totalPages = 20, totalCount = 200))
        assertEquals("第 3 / 20 页 · 共 200 条", CatalogText.searchRange(page = 3, totalPages = 20, totalCount = 200))
    }

    @Test
    fun searchRangeFallsBackToPageNumbersWithoutATotal() {
        assertEquals("第 2 / 20 页", CatalogText.searchRange(page = 2, totalPages = 20, totalCount = 0))
        assertEquals("第 1 / 1 页", CatalogText.searchRange(page = 5, totalPages = 0, totalCount = 0))
    }
}
