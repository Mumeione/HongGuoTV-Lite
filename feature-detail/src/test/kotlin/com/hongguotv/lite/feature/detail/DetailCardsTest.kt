package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailCardsTest {

    private fun series(id: String, season: Int, released: Boolean = true) =
        Series(id = id, title = "测试书第${season}季", cover = "https://x/$id.jpg", season = season, released = released)

    @Test
    fun theCurrentSeasonIsMarkedOnTheArtwork() {
        val card = DetailCards.season(series("6", 6), base = "测试书", current = true, progress = null)
        assertEquals("当前", card.corner)
        assertEquals("第六季", card.title)
    }

    @Test
    fun otherSeasonsCarryNoCorner() {
        val card = DetailCards.season(series("7", 7), base = "测试书", current = false, progress = null)
        assertEquals("", card.corner)
    }

    /**
     * 8 列时每张卡只有九十来 dp：标题只写季号，写全名会被截成"测试书第…"。
     * 没有季号的条目（第 1 季常常直接叫"分宗篇"）才退回篇名。
     */
    @Test
    fun theCardTitleIsJustTheSeasonNumber() {
        val first = Series(id = "1", title = "魏家逆子成首辅分宗篇", season = 0)
        assertEquals("分宗篇", DetailText.seasonCardTitle(first, "魏家逆子成首辅"))

        val ninth = Series(id = "9", title = "魏家逆子成首辅第九季：始计篇", season = 9)
        assertEquals("第九季", DetailText.seasonCardTitle(ninth, "魏家逆子成首辅"))

        // 不知道系列基名也一样：季号直接从标题里读，不依赖前缀能不能切掉
        assertEquals("第九季", DetailText.seasonCardTitle(ninth, ""))
    }

    /** 未上线季暗一档，并且角标本身就写着"未上线"——不看颜色也知道按不进去。 */
    @Test
    fun anUnreleasedSeasonIsDimmedAndLabelled() {
        val card = DetailCards.season(series("10", 10, released = false), base = "测试书", current = false, progress = null)
        assertTrue(card.dim)
        // 第一行已经写了「第十季」，状态行只补状态（2026-10-24 验收：别重复季号）
        assertEquals("未上线", card.badge)
    }

    @Test
    fun aWatchedSeasonReadsAsFinishedInsteadOfAnEpisodeNumber() {
        val progress = Progress("6", "v1", 29, 0L, 100_000L, completed = true, updatedAt = 1L)
        val card = DetailCards.season(series("6", 6), base = "测试书", current = false, progress = progress)
        assertEquals("已看完", card.badge)
        assertFalse(card.dim)
    }

    @Test
    fun aSeasonInProgressShowsTheEpisode() {
        val progress = Progress("6", "v9", 8, 12_000L, 100_000L, completed = false, updatedAt = 1L)
        val card = DetailCards.season(series("6", 6), base = "测试书", current = false, progress = progress)
        assertEquals("上次第 9 集", card.badge)
    }

    // ---------- 系列页「继续播放」的落点 ----------

    private val line = SeasonLine(listOf(series("1", 1), series("6", 6), series("7", 7, released = false)))

    private fun progress(seasonId: String, index: Int, completed: Boolean = false, positionMs: Long = 30_000L) =
        Progress(seasonId, "v$index", index, positionMs, 100_000L, completed, updatedAt = 1L)

    @Test
    fun withoutAnyRecordStartFromTheNewestReleasedSeason() {
        val target = SeasonTargets.of(line, progress = null, fallback = series("6", 6))
        assertEquals("6", target.series?.id)
        assertEquals(0, target.index)
        assertEquals("开始播放", target.label)
        assertTrue(target.direct)
    }

    /** 有记录就跳回记录的那一季那一集，哪怕秒数是 0。 */
    @Test
    fun anUnfinishedRecordResumesThatSeasonAndEpisode() {
        val target = SeasonTargets.of(line, progress("1", 4), fallback = series("6", 6))
        assertEquals("1", target.series?.id)
        assertEquals(4, target.index)
        assertEquals(30_000L, target.positionMs)
        assertEquals("继续播放 · 第 5 集", target.label)
        assertTrue(target.direct)
    }

    /** 「续播 2 秒」没有意义：位置归零，但仍然回那一季那一集。 */
    @Test
    fun aCheckpointUnderFiveSecondsStartsThatEpisodeFromZero() {
        val target = SeasonTargets.of(line, progress("6", 2, positionMs = 2_000L), fallback = series("6", 6))
        assertEquals("6", target.series?.id)
        assertEquals(2, target.index)
        assertEquals(0L, target.positionMs)
    }

    /** 停在片尾 1 秒：这一集算看完 → 进下一集，和详情页给出的落点一致。 */
    @Test
    fun aCheckpointNearTheEndMovesToTheNextEpisodeOfTheSameSeason() {
        val target = SeasonTargets.of(line, progress("6", 4, positionMs = 99_000L), fallback = series("6", 6))
        assertEquals("6", target.series?.id)
        assertEquals(5, target.index)
        assertEquals(0L, target.positionMs)
        assertEquals("继续播放 · 第 6 集", target.label)
        assertTrue(target.direct)
    }

    @Test
    fun aFinishedSeasonHopsToTheNextReleasedOne() {
        val target = SeasonTargets.of(line, progress("6", 29, completed = true), fallback = series("6", 6))
        // 第七季未上线：跳过它，没有更后面的季了 → 记的那一季重新挑集
        assertEquals("重新播放", target.label)
        assertEquals("6", target.series?.id)
        assertFalse(target.direct)
    }

    @Test
    fun aFinishedSeasonWithAReleasedNextSeasonContinuesThere() {
        val withSeventh = SeasonLine(listOf(series("6", 6), series("7", 7)))
        val target = SeasonTargets.of(withSeventh, progress("6", 29, completed = true), fallback = series("6", 6))
        assertEquals("7", target.series?.id)
        assertEquals(0, target.index)
        assertEquals("继续播放 · 下一季", target.label)
        assertTrue(target.direct)
    }

    @Test
    fun aRecordOutsideTheLineFallsBackToTheCallersSeason() {
        val target = SeasonTargets.of(null, progress("99", 3), fallback = series("6", 6))
        assertEquals("6", target.series?.id)
        assertEquals(3, target.index)
    }

    @Test
    fun aLineWithoutAnyReleasedSeasonHasNoTarget() {
        val unreleased = SeasonLine(listOf(series("6", 6, released = false)))
        assertNull(SeasonTargets.of(unreleased, null, fallback = null).series)
    }
}
