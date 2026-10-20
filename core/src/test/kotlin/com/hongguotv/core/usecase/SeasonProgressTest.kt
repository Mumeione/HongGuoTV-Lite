package com.hongguotv.core.usecase

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonProgressTest {

    private fun season(id: String, season: Int) = Series(id = id, title = "某剧第${season}季", season = season)

    private fun progress(seriesId: String, index: Int, updatedAt: Long) =
        Progress(seriesId, "vid-$seriesId", index, 12_000L, 90_000L, false, updatedAt)

    private val line = SeasonLine(listOf(season("1", 1), season("6", 6), season("7", 7)))
    private val entry = FavoriteEntry(season("6", 6), addedAt = 0L, status = FavoriteStatus.UP_TO_DATE, seriesKey = "某剧")

    @Test
    fun latestPicksTheMostRecentlyWatchedSeasonAcrossTheLine() {
        val rows = mapOf(
            "1" to progress("1", 0, updatedAt = 100L),
            "6" to progress("6", 4, updatedAt = 300L),
            "7" to progress("7", 2, updatedAt = 200L),
        )
        val latest = SeasonProgress.latest(entry, line) { rows[it] }
        assertEquals("6", latest?.seriesId)
        assertEquals(4, latest?.episodeIndex)
    }

    /** 收藏的那一季自己没有记录、但季线其它季有：仍要答出来（"继续播放"回的是看过的那一季）。 */
    @Test
    fun aLineMemberWithTheOnlyRecordWins() {
        val rows = mapOf("1" to progress("1", 8, updatedAt = 500L))
        assertEquals("1", SeasonProgress.latest(entry, line) { rows[it] }?.seriesId)
    }

    @Test
    fun noRecordAnywhereReturnsNull() {
        assertNull(SeasonProgress.latest(entry, line) { null })
    }

    @Test
    fun aRecordOutsideTheLineStillCountsForTheFavoriteSeasonOnly() {
        // 记录属于收藏的那一季本身（entry.series.id），季线里没有它也要取到
        val orphan = FavoriteEntry(season("9", 9), 0L, FavoriteStatus.UNCHECKED, "某剧")
        val rows = mapOf("9" to progress("9", 1, updatedAt = 10L))
        assertEquals("9", SeasonProgress.latest(orphan, null) { rows[it] }?.seriesId)
    }

    @Test
    fun seasonOfMapsTheRecordBackToItsSeasonNumber() {
        assertEquals(6, SeasonProgress.seasonOf(progress("6", 0, 0L), line, fallback = 0))
        assertEquals(7, SeasonProgress.seasonOf(progress("7", 0, 0L), line, fallback = 0))
    }

    @Test
    fun seasonOfFallsBackWhenTheLineIsMissingOrUnknown() {
        assertEquals(3, SeasonProgress.seasonOf(progress("99", 0, 0L), line, fallback = 3))
        assertEquals(3, SeasonProgress.seasonOf(progress("99", 0, 0L), null, fallback = 3))
    }

    @Test
    fun seriesOfReturnsTheSeasonItself() {
        assertEquals("1", SeasonProgress.seriesOf(progress("1", 0, 0L), line)?.id)
        assertNull(SeasonProgress.seriesOf(progress("99", 0, 0L), line))
    }
}
