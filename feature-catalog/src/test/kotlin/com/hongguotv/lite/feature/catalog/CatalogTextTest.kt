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
    fun ascendingOrderReplaysTheSeasonLineAndKeepsTheRest() {
        val line = listOf(series("1", "剧第一季", 1), series("2", "剧第二季", 2), series("3", "剧第三季", 3))
        // 端口给的展示序：季线新→旧 + 其余原序
        val raw = listOf(line[2], line[1], line[0], series("9", "另一部"))

        assertEquals(raw, CatalogText.ordered(raw, line, ascending = false))
        assertEquals(listOf("1", "2", "3", "9"), CatalogText.ordered(raw, line, ascending = true).map { it.id })
    }

    @Test
    fun ascendingOrderIgnoresLineMembersThatTheFilterRemoved() {
        val line = listOf(series("1", "剧第一季", 1), series("2", "剧第二季", 2))
        val raw = listOf(series("2", "剧第二季", 2))
        assertEquals(listOf("2"), CatalogText.ordered(raw, line, ascending = true).map { it.id })
    }

    @Test
    fun aSingleSeasonLineLeavesTheOrderAlone() {
        val raw = listOf(series("2"), series("1"))
        assertEquals(raw, CatalogText.ordered(raw, listOf(series("1")), ascending = true))
    }

    // ---------- 底部信息栏（B 方案） ----------

    @Test
    fun infoBandOnlyStatesTheSeriesAndItsFacts() {
        // B 方案口径："信息栏只写信息和收藏状态、更新情况，不写观看记录"
        // 搜索/历史结果：片名 + 已收藏 + 分类（我的状态在卡片与详情页说）
        assertEquals(listOf("已收藏", "古装 / 逆袭"), CatalogText.bandSegments(favorite = true, tags = "古装 / 逆袭"))
        assertEquals(listOf("古装 / 逆袭"), CatalogText.bandSegments(favorite = false, tags = "古装 / 逆袭"))
        assertEquals(emptyList<String>(), CatalogText.bandSegments(favorite = false, tags = ""))
    }

    @Test
    fun favoriteInfoBandStatesTheUpdateProgress() {
        // 收藏页：片名 + 已收藏 + 更新到…
        assertEquals(listOf("已收藏", "更新到第九季第352集"), CatalogText.favoriteSegments(update = "更新到第九季第352集"))
        // 还没检查出来时只说收藏状态，不编一句空话
        assertEquals(listOf("已收藏"), CatalogText.favoriteSegments(update = ""))
    }
}
