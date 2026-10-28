package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.usecase.ResumeTarget
import com.hongguotv.lite.uikit.ui.SpecRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun playLabelFollowsTheResumeTarget() {
        assertEquals("开始观看", DetailText.playLabel(null, ResumeTarget(0, 0L, false)))
        assertEquals("继续播放 · 第 9 集", DetailText.playLabel(progress(8, 754_000), ResumeTarget(8, 754_000L, false)))
        // 看完了 → 落点挪到下一集，文案也要挪
        assertEquals("开始下一集", DetailText.playLabel(progress(8, 0, completed = true), ResumeTarget(9, 0L, true)))
        // 最后一集看完：落点没有前移，是重看本集
        assertEquals("重新播放", DetailText.playLabel(progress(29, 0, completed = true), ResumeTarget(29, 0L, true)))
    }

    /**
     * 主按钮两行：动作一行、集数一行。
     *
     * 「继续播放 · 第 100 集」在 172dp 的胶囊里会折成「…第 100 / 集」——断在量词上；
     * 拆成两行后第二行小一号、浅一档，三位数也放得下。
     */
    @Test
    fun playLabelSplitsTheActionFromTheEpisode() {
        assertEquals("继续播放" to "第 100 集", DetailText.playLabelLines("继续播放 · 第 100 集"))
        assertEquals("继续播放" to "下一季", DetailText.playLabelLines("继续播放 · 下一季"))
        assertEquals("开始观看" to "", DetailText.playLabelLines("开始观看"))
        assertEquals("重新播放" to "", DetailText.playLabelLines("重新播放"))
    }

    @Test
    fun theSeasonFooterIsOneLine() {
        // 说明句拆两行会把信息块顶出封面底；一行是 2026-10-24 的验收口径
        assertTrue(DetailText.SEASON_FOOTER.none { it == '\n' })
        assertTrue(DetailText.SEASON_FOOTER.contains("各季进度分别保存"))
        assertTrue(DetailText.SEASON_FOOTER.contains("继续播放会回到最近看的那一季"))
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
    fun seasonStateCoversAllFourCases() {
        val season = Series("10", "测试书第十季", season = 10, released = false)
        // 第一行已经写了季号，第二行再写一遍就是「第十季 · 未上线」（维护者截图）
        assertEquals("未上线", DetailText.seasonState(season, progress = null))
        assertEquals("已看完", DetailText.seasonState(series(), progress(29, 0, completed = true)))
        assertEquals("上次第 9 集", DetailText.seasonState(series(), progress(8, 0)))
        assertEquals("未观看", DetailText.seasonState(series(), progress = null))
    }

    // ---------- B 方案：两行 hero 的规格表与片名副行 ----------

    /** 片名副行（mockup 画面3/5 的「共 10 季」）。单季剧不写——季号已经在标题里了。 */
    @Test
    fun seasonNoteCountsSeasonsAndStatesReleasedOnes() {
        assertEquals("共 10 季", DetailText.seasonNote(seasonCount = 10, releasedCount = 10, withReleased = false))
        assertEquals("共 10 季 · 9 季已上线", DetailText.seasonNote(seasonCount = 10, releasedCount = 9, withReleased = true))
        assertEquals("单季不写副行", "", DetailText.seasonNote(seasonCount = 1, releasedCount = 1, withReleased = true))
    }

    /** 剧集详情的四行规格表（mockup 画面3）：「记录」是 strong——那是用户回来的理由。 */
    @Test
    fun `update label only exists when the season has episodes`() {
        // 规格表「更新」行的唯一写法；空格与量词漂了，详情页和卡片就会各说各的
        assertEquals("更新到 第 352 集", DetailText.updateLabel(352))
        assertEquals("空剧集不画这一行", "", DetailText.updateLabel(0))
    }

    @Test
    fun episodeSpecsPutTheRecordInStrong() {        val rows = DetailText.episodeSpecs(
            update = "更新到 第 352 集",
            total = 352,
            watchedCount = 20,
            tags = "古装 / 逆袭",
            record = "第 20 集 · 05:00",
        )
        assertEquals(
            listOf("更新", "集数", "分类", "记录"),
            rows.map { it.label },
        )
        assertEquals(listOf("更新到 第 352 集", "全 352 集 · 已看 20 集", "古装 / 逆袭", "第 20 集 · 05:00"), rows.map { it.value })
        assertEquals("只有「记录」一行是 strong", listOf(false, false, false, true), rows.map { it.strong })
    }

    @Test
    fun episodeSpecsDropWhatTheyDoNotKnow() {
        // 没看过的剧没有"已看"半句、没有记录行
        val rows = DetailText.episodeSpecs(update = "", total = 12, watchedCount = 0, tags = "", record = "")
        assertEquals(listOf("集数"), rows.map { it.label })
        assertEquals(listOf("全 12 集"), rows.map { it.value })
    }

    /** 收藏详情的四行规格表（mockup 画面5）：季数行说"收藏整个系列"。 */
    @Test
    fun seasonSpecsStateTheWholeSeries() {
        val rows = DetailText.seasonSpecs(
            update = "更新到 第九季第352集",
            tags = "古装 / 逆袭",
            record = "第九季 第 20 集 · 05:00",
        )
        assertEquals(listOf("更新", "季数", "分类", "记录"), rows.map { it.label })
        // 季数在副行已经写过（「共 37 季」），这一行只回答"收藏了什么"（2026-10-24 验收）
        assertEquals("已收藏整个系列", rows[1].value)
        assertEquals("记录行 strong", true, rows[3].strong)
    }

    /** 记录行：详情页只说"第几集 · 几分几秒"，手动标已看说「已看完」。 */
    @Test
    fun episodeRecordIsTheResumePoint() {
        assertEquals("第 20 集 · 05:00", DetailText.episodeRecord(progress(19, 300_000)))
        assertEquals("", DetailText.episodeRecord(null))
    }

    /** 系列页的记录行带季名：不写季名就分不清是哪一季的"第 20 集"。 */
    @Test
    fun seasonRecordPutsTheSeasonInFront() {
        val line = SeasonLine(
            listOf(
                Series("6", "测试书第六季", season = 6),
                Series("9", "测试书第九季", season = 9),
            ),
        )
        val row = Progress("9", "v20", episodeIndex = 19, positionMs = 300_000, durationMs = 0L, completed = false, updatedAt = 1L)
        assertEquals("第九季 第 20 集 · 05:00", DetailText.seasonRecord(row, line, fallbackSeason = 0))
        assertEquals("第六季 第 20 集 · 05:00", DetailText.seasonRecord(row, null, fallbackSeason = 6))
        assertEquals("", DetailText.seasonRecord(null, line, fallbackSeason = 0))
    }

    @Test
    fun specRowsNeverBlankLabels() {
        // 空值整行去掉，不画"更新：（空）"这种行；「季数」是固定文案，永远在
        val rows = DetailText.seasonSpecs(update = "", tags = "", record = "")
        assertTrue(rows.none { it.value.isBlank() })
        assertEquals(listOf("季数"), rows.map { it.label })
    }

    @Test
    fun seasonLineTitleOnlyCountsWhenThereAreSeveral() {
        assertEquals("所有季 · 共 4 季", DetailText.seasonLineTitle(4))
        assertEquals("所有季", DetailText.seasonLineTitle(1))
    }
}
