package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.Series
import com.hongguotv.core.usecase.FavoriteCheckReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收藏角标、检查状态行、设置档位。 */
class CatalogLabelsTest {

    private fun entry(
        season: Int = 5,
        latestSeason: Int = 5,
        status: FavoriteStatus = FavoriteStatus.UP_TO_DATE,
    ) = FavoriteEntry(
        series = Series(id = "1", title = "测试书", season = season),
        addedAt = 0L,
        status = status,
        seriesKey = "测试书",
        latestSeason = latestSeason,
    )

    private fun progress(seriesId: String, updatedAt: Long, index: Int = 0) = Progress(
        seriesId = seriesId,
        episodeVid = "v-$seriesId",
        episodeIndex = index,
        positionMs = 1_000L,
        durationMs = 2_000L,
        completed = false,
        updatedAt = updatedAt,
    )

    // ---------- 角标 ----------

    @Test
    fun newerSeasonWinsOverTheCheckStatus() {
        val label = FavoriteLabels.badge(entry(season = 5, latestSeason = 13))
        assertEquals("第十三季已上线", label)
        assertTrue(FavoriteLabels.attention(entry(season = 5, latestSeason = 13)))
    }

    @Test
    fun upToDateAndNeverCheckedReadDifferently() {
        assertEquals("已是最新", FavoriteLabels.badge(entry(latestSeason = 5)))
        assertFalse(FavoriteLabels.attention(entry(latestSeason = 5)))
        assertEquals("尚未检查更新", FavoriteLabels.badge(entry(status = FavoriteStatus.UNCHECKED)))
        assertEquals("检查失败", FavoriteLabels.badge(entry(status = FavoriteStatus.FAILED)))
    }

    @Test
    fun episodeGrowthIsShownButNotAsANewSeason() {
        // 端口用 NEW_SEASON 表达"集数增长"：既没有更新的季，就不要说"第N季已上线"
        val label = FavoriteLabels.badge(entry(latestSeason = 5, status = FavoriteStatus.NEW_SEASON))
        assertEquals("已更新", label)
        assertTrue(FavoriteLabels.attention(entry(latestSeason = 5, status = FavoriteStatus.NEW_SEASON)))
    }

    @Test
    fun unknownCurrentSeasonStillReportsANewSeason() {
        // 老收藏没有季号（season=0）但季线里有多季：以季线为准
        assertEquals("第三季已上线", FavoriteLabels.badge(entry(season = 0, latestSeason = 3)))
    }

    // ---------- 状态行 ----------

    @Test
    fun idleStatusExplainsWhyNothingIsHappening() {
        assertEquals("收藏后可检查有没有新一季上线", FavoriteLabels.idleStatus(favoriteCount = 0, dueCount = 0))
        assertEquals("最近一小时检查过 · 可手动刷新", FavoriteLabels.idleStatus(favoriteCount = 3, dueCount = 0))
        assertEquals("「检查更新」看有没有新的一季上线", FavoriteLabels.idleStatus(favoriteCount = 3, dueCount = 3))
    }

    @Test
    fun progressTextShowsPositionAndTrimsTheTitle() {
        val text = FavoriteLabels.progressText(index = 2, total = 12, title = "这是一个特别长的剧名需要截断")
        assertTrue(text.startsWith("正在检查 3/12 · "))
        assertEquals("正在检查 3/12 · ".length + 14, text.length)
    }

    @Test
    fun summaryCoversEveryOutcome() {
        assertEquals("检查失败 · 稍后联网再试", FavoriteLabels.summary(FavoriteCheckReport(0, 3, 0, true)))
        assertEquals("检查完成 · 2 部有新一季", FavoriteLabels.summary(FavoriteCheckReport(5, 0, 2, false)))
        assertEquals("检查完成 · 6 部已是最新 · 1 部失败", FavoriteLabels.summary(FavoriteCheckReport(6, 1, 0, false)))
        assertEquals("检查完成 · 已是最新", FavoriteLabels.summary(FavoriteCheckReport(1, 0, 0, false)))
        assertEquals("检查完成 · 4 部都已是最新", FavoriteLabels.summary(FavoriteCheckReport(4, 0, 0, false)))
        // 连续失败中止：不能报成"检查完成"
        assertTrue(FavoriteLabels.summary(FavoriteCheckReport(2, 3, 1, true)).startsWith("检查中断"))
    }

    // ---------- 系列进度 ----------
    // 「系列最近一条记录」的规则已上提到 core 的 SeasonProgress（S5），用例在 core 的 SeasonProgressTest；
    // 这里只留"页面把这条记录写成什么字"的部分。

    @Test
    fun aFinishedSeasonReadsAsFinishedInsteadOfZeroSeconds() {
        val finished = HistoryEntry(
            series = Series("1", "测试书"),
            progress = progress("1", updatedAt = 0L).copy(completed = true),
        )
        // 看完的一季停在"第 1 集 · 00:01"既没用也不准确
        assertEquals("整剧已看完", CatalogCards.history(finished, watched = false).badge)
        assertEquals(
            "第 1 集 · 00:01",
            CatalogCards.history(finished.copy(progress = finished.progress.copy(completed = false)), watched = false).badge,
        )
        assertEquals(
            "整剧已看完",
            CatalogCards.history(finished.copy(progress = finished.progress.copy(completed = false)), watched = true).badge,
        )
    }

    // ---------- 设置档位 ----------

    @Test
    fun everyCycleWrapsBackToTheFirstOption() {
        assertEquals(720, SettingRows.next(480, SettingRows.qualities))
        assertEquals(1080, SettingRows.next(720, SettingRows.qualities))
        assertEquals(480, SettingRows.next(1080, SettingRows.qualities))

        assertEquals(1.25f, SettingRows.next(1f, SettingRows.speeds))
        assertEquals(0.75f, SettingRows.next(2f, SettingRows.speeds))

        assertEquals(FrameMode.ZOOM, SettingRows.next(FrameMode.FIT, SettingRows.frames))
        assertEquals(FrameMode.FIT, SettingRows.next(FrameMode.FILL, SettingRows.frames))

        assertEquals(ContentFilter.SHORT, SettingRows.next(ContentFilter.ALL, SettingRows.filters))
        assertEquals(ContentFilter.ALL, SettingRows.next(ContentFilter.COMIC, SettingRows.filters))
    }

    @Test
    fun aValueOutsideTheOptionsFallsBackToTheFirstOption() {
        // 盘上的值被改坏（例如 999P）时不要崩，也不要跳过一个档
        assertEquals(480, SettingRows.next(999, SettingRows.qualities))
        assertEquals(0.75f, SettingRows.next(3f, SettingRows.speeds))
    }

    @Test
    fun labelsMatchTheOldAppCopy() {
        assertEquals("1080P", SettingRows.qualityLabel(1080))
        assertEquals("1×", SettingRows.speedLabel(1f))
        assertEquals("1.25×", SettingRows.speedLabel(1.25f))
        assertEquals("完整画面", SettingRows.frameLabel(FrameMode.FIT))
        assertEquals("等比铺满", SettingRows.frameLabel(FrameMode.ZOOM))
        assertEquals("拉伸铺满", SettingRows.frameLabel(FrameMode.FILL))
        assertEquals("全部", SettingRows.filterLabel(ContentFilter.ALL))
        assertEquals("短剧", SettingRows.filterLabel(ContentFilter.SHORT))
        assertEquals("漫剧", SettingRows.filterLabel(ContentFilter.COMIC))
        assertEquals("倒序", SettingRows.sortLabel(ascending = false))
        assertEquals("正序", SettingRows.sortLabel(ascending = true))
        assertEquals("历史", SettingRows.tabLabel(2))
    }
}
