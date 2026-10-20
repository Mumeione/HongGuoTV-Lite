package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.usecase.ResumeTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** 两个页面的文案：用户看到什么，就在这些断言里。 */
class DetailTextTest {

    private fun series(badge: String = "全 30 集", tags: String = "爱情 · 都市") =
        Series(id = "1", title = "测试书第六季", badge = badge, tags = tags, season = 6)

    private fun progress(index: Int, positionMs: Long, completed: Boolean = false) = Progress(
        seriesId = "6",
        episodeVid = "v$index",
        episodeIndex = index,
        positionMs = positionMs,
        durationMs = 100_000L,
        completed = completed,
        updatedAt = 1L,
    )

    @Test
    fun heroMetaReadsAsEpisodeCountPlusCategory() {
        assertEquals("全 30 集  ·  爱情 / 都市", DetailText.heroMeta(series(), episodeCount = 30))
        // 集数未知时退回 badge；tags 为空则只剩一段
        assertEquals("全 12 集", DetailText.heroMeta(series(badge = "全 12 集", tags = ""), episodeCount = 0))
        assertEquals("全 30 集", DetailText.heroMeta(series(tags = ""), episodeCount = 30))
    }

    @Test
    fun heroStatusAlwaysSaysWhereTheUserLeft() {
        assertEquals("上次看到 第 9 集 · 12:34", DetailText.heroStatus(progress(8, 754_000), watched = false))
        assertEquals("上次已看完整季", DetailText.heroStatus(progress(29, 0, completed = true), watched = false))
        assertEquals("整剧已看完", DetailText.heroStatus(null, watched = true))
        assertEquals("", DetailText.heroStatus(null, watched = false))
    }

    /** 多季剧的状态行必须带季名，否则"上次看到第 9 集"分不清是哪一季。 */
    @Test
    fun seriesStatusPutsTheSeasonInFrontOfTheEpisode() {
        val line = SeasonLine(
            listOf(
                Series("6", "测试书第六季", season = 6),
                Series("7", "测试书第七季", season = 7),
            ),
        )
        val row = Progress("7", "v8", episodeIndex = 8, positionMs = 754_000, durationMs = 0L, completed = false, updatedAt = 1L)
        assertEquals("上次看到 第七季 第 9 集 · 12:34", DetailText.seriesStatus(row, line))
        // 记录所属的季不在季线里：退回调用方给的季号，仍然要能读出季名
        assertEquals("上次看到 第六季 第 9 集 · 12:34", DetailText.seriesStatus(row, null, fallbackSeason = 6))
        assertEquals("", DetailText.seriesStatus(null, line))
    }

    @Test
    fun playLabelFollowsTheResumeTarget() {
        assertEquals("开始观看", DetailText.playLabel(null, ResumeTarget(0, 0L, false), watched = false))
        assertEquals("继续播放 · 第 9 集", DetailText.playLabel(progress(8, 754_000), ResumeTarget(8, 754_000L, false), watched = false))
        // 看完了 → 落点挪到下一集，文案也要挪
        assertEquals("开始下一集", DetailText.playLabel(progress(8, 0, completed = true), ResumeTarget(9, 0L, true), watched = false))
        // 最后一集看完：落点没有前移，是重看本集
        assertEquals("重新播放", DetailText.playLabel(progress(29, 0, completed = true), ResumeTarget(29, 0L, true), watched = false))
        // 手动标"已看"优先：从头看
        assertEquals("重新观看", DetailText.playLabel(progress(8, 754_000), ResumeTarget(0, 0L, false), watched = true))
    }

    @Test
    fun rangeLabelsUseTheInclusiveEndpoints() {
        assertEquals("第 1—100 集", DetailText.rangeLabel(0..99))
        assertEquals("第 101—150 集", DetailText.rangeLabel(100..149))
        assertEquals("", DetailText.rangeLabel(IntRange.EMPTY))
    }

    /** 季线的范围按**卡片上的季号**写：倒序时下标 1—8 对应的是第 40—33 季。 */
    @Test
    fun seasonRangeUsesSeasonNumbersNotIndexes() {
        assertEquals("第 33—40 季", DetailText.seasonRangeLabel(firstSeason = 40, lastSeason = 33))
        assertEquals("第 1—8 季", DetailText.seasonRangeLabel(firstSeason = 1, lastSeason = 8))
        // 季号取不到时不写错的季号
        assertEquals("", DetailText.seasonRangeLabel(firstSeason = 0, lastSeason = 0))
    }

    @Test
    fun nextSeasonHintDisappearsWhenThereIsNoNextSeason() {
        assertEquals("", DetailText.nextSeasonHint(null))
        assertEquals("本季看完后会接着播《测试书第七季》", DetailText.nextSeasonHint(Series("7", "测试书第七季", season = 7)))
    }

    @Test
    fun seasonStateCoversAllFiveCases() {
        val season = Series("10", "测试书第十季", season = 10, released = false)
        assertEquals("第十季 · 未上线", DetailText.seasonState(season, progress = null, watched = false))
        assertEquals("已看完", DetailText.seasonState(series(), progress(29, 0, completed = true), watched = false))
        assertEquals("上次第 9 集", DetailText.seasonState(series(), progress(8, 0), watched = false))
        assertEquals("已看完", DetailText.seasonState(series(), progress = null, watched = true))
        assertEquals("未观看", DetailText.seasonState(series(), progress = null, watched = false))
    }

    @Test
    fun seriesMetaCarriesTheSeasonCountOnlyWhenThereAreSeveral() {
        assertEquals("收藏的剧  ·  共 4 季  ·  全 30 集  ·  爱情/都市", DetailText.seriesMeta(series(), seasonCount = 4, favorite = true))
        assertEquals("共 2 季  ·  全 30 集  ·  爱情/都市", DetailText.seriesMeta(series(), seasonCount = 2, favorite = false))
        assertEquals("收藏的剧  ·  全 30 集", DetailText.seriesMeta(series(tags = ""), seasonCount = 1, favorite = true))
    }

    @Test
    fun seasonLineTitleOnlyCountsWhenThereAreSeveral() {
        assertEquals("所有季 · 共 4 季", DetailText.seasonLineTitle(4))
        assertEquals("所有季", DetailText.seasonLineTitle(1))
    }
}
