package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Progress
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

    private fun progress(index: Int, positionMs: Long, completed: Boolean = false) = Progress(
        seriesId = "1",
        episodeVid = "v1",
        episodeIndex = index,
        positionMs = positionMs,
        durationMs = 100_000L,
        completed = completed,
        updatedAt = 1L,
    )

    @Test
    fun previewStatusPutsTheSeasonInFrontOfTheEpisode() {
        val status = CatalogText.previewStatus(favorite = true, season = 7, progress = progress(8, 754_000), watched = false)
        assertEquals("已收藏 · 上次看到 第七季 第 9 集 · 12:34", status)
    }

    @Test
    fun previewStatusCoversFinishedSeasonAndWholeSeries() {
        assertEquals("上次已看完整季", CatalogText.previewStatus(false, 0, progress(3, 0, completed = true), false))
        assertEquals("整剧已看完", CatalogText.previewStatus(false, 0, null, watched = true))
        assertEquals("", CatalogText.previewStatus(false, 0, null, watched = false))
    }

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
}
