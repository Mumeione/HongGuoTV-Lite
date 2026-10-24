package com.hongguotv.core.usecase

import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import org.junit.Assert.assertEquals
import org.junit.Test

/** 片尾判定：连播开关、最后一集、跨季、未上线季。 */
class EpisodeFlowTest {

    private fun series(id: String, season: Int, released: Boolean = true) =
        Series(id = id, title = "某剧第${season}季", season = season, released = released)

    @Test
    fun aMiddleEpisodeContinuesToTheNextOne() {
        assertEquals(EndAction.Play(4), EpisodeFlow.after(3, 30, autoNext = true, line = null, currentSeriesId = "a"))
    }

    @Test
    fun autoNextOffStopsEvenInTheMiddle() {
        assertEquals(EndAction.Stop(), EpisodeFlow.after(3, 30, autoNext = false, line = null, currentSeriesId = "a"))
    }

    @Test
    fun theLastEpisodeWithoutASeasonLineStops() {
        // 单季（季号 0）没有季线：这一集就是全部
        assertEquals(EndAction.Stop(seriesEnded = true), EpisodeFlow.after(29, 30, autoNext = true, line = null, currentSeriesId = "a"))
    }

    @Test
    fun aSuffixedSeasonWithoutASeasonLineDoesNotClaimTheSeriesIsOver() {
        // 带季号却拿不到季线：不知道后面还有没有，只能停
        assertEquals(
            EndAction.Stop(seriesEnded = false),
            EpisodeFlow.after(29, 30, autoNext = true, line = null, currentSeriesId = "a", currentSeason = 1),
        )
    }

    @Test
    fun theLastEpisodeJumpsToTheNextSeason() {
        val line = SeasonLine(listOf(series("a", 1), series("b", 2)))
        val action = EpisodeFlow.after(9, 10, autoNext = true, line = line, currentSeriesId = "a")
        assertEquals(EndAction.NextSeason(series("b", 2)), action)
    }

    @Test
    fun unreleasedSeasonsAreSkipped() {
        val line = SeasonLine(listOf(series("a", 1), series("b", 2, released = false), series("c", 3)))
        val action = EpisodeFlow.after(9, 10, autoNext = true, line = line, currentSeriesId = "a")
        assertEquals(EndAction.NextSeason(series("c", 3)), action)
    }

    @Test
    fun theLatestReleasedSeasonStops() {
        val line = SeasonLine(listOf(series("a", 1), series("b", 2)))
        assertEquals(EndAction.Stop(seriesEnded = true), EpisodeFlow.after(9, 10, autoNext = true, line = line, currentSeriesId = "b"))
    }

    @Test
    fun aSeasonMissingFromTheLineStopsInsteadOfGuessing() {
        val line = SeasonLine(listOf(series("b", 2), series("c", 3)))
        assertEquals(EndAction.Stop(), EpisodeFlow.after(9, 10, autoNext = true, line = line, currentSeriesId = "a"))
    }

    @Test
    fun anEmptyCatalogueStops() {
        assertEquals(EndAction.Stop(), EpisodeFlow.after(0, 0, autoNext = true, line = null, currentSeriesId = "a"))
    }

    @Test
    fun anIndexOutsideTheCatalogueStops() {
        assertEquals(EndAction.Stop(), EpisodeFlow.after(30, 30, autoNext = true, line = null, currentSeriesId = "a"))
    }

    @Test
    fun autoNextOffAtTheVeryLastEpisodeStillReportsTheSeriesIsOver() {
        val line = SeasonLine(listOf(series("a", 1), series("b", 2)))
        val action = EpisodeFlow.after(9, 10, autoNext = false, line = line, currentSeriesId = "b")
        assertEquals(EndAction.Stop(seriesEnded = true), action)
    }

    @Test
    fun theOnlyEpisodeOfASingleSeasonStops() {
        assertEquals(EndAction.Stop(seriesEnded = true), EpisodeFlow.after(0, 1, autoNext = true, line = null, currentSeriesId = "a"))
    }
}
