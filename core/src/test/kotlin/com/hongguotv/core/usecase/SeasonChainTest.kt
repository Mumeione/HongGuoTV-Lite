package com.hongguotv.core.usecase

import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonChainTest {

    private fun season(id: String, season: Int, released: Boolean = true) =
        Series(id = id, title = "某剧第${season}季", season = season, released = released)

    private fun line(vararg seasons: Series) = SeasonLine(seasons.toList())

    @Test
    fun nextIsTheNewerNeighbourInTheLine() {
        val line = line(season("1", 1), season("2", 2), season("3", 3))
        assertEquals("2", SeasonChain.next(line, "1")?.id)
        assertEquals("3", SeasonChain.next(line, "2")?.id)
    }

    /** 未上线季仍占季线里的位置，但连播链要跳过它。 */
    @Test
    fun unreleasedSeasonsAreSkippedButNotTreatedAsTheEnd() {
        val line = line(season("1", 1), season("2", 2, released = false), season("3", 3))
        assertEquals("3", SeasonChain.next(line, "1")?.id)
        assertEquals(listOf("1", "3"), SeasonChain.released(line).map { it.id })
    }

    @Test
    fun theLastSeasonHasNoNext() {
        val line = line(season("1", 1), season("2", 2))
        assertNull(SeasonChain.next(line, "2"))
    }

    /** 当前季不在季线里（无季号的锚点、季线缓存缺本尊）：不猜，返回 null。 */
    @Test
    fun aSeasonOutsideTheLineHasNoNext() {
        val line = line(season("1", 1), season("2", 2))
        assertNull(SeasonChain.next(line, "99"))
    }

    @Test
    fun anEmptyOrSingleLineHasNoNext() {
        assertNull(SeasonChain.next(SeasonLine(emptyList()), "1"))
        assertNull(SeasonChain.next(line(season("1", 1)), "1"))
        assertNull(SeasonChain.latest(SeasonLine(emptyList())))
    }

    @Test
    fun latestIsTheNewestReleasedSeason() {
        val line = line(season("1", 1), season("2", 2), season("3", 3, released = false))
        assertEquals("2", SeasonChain.latest(line)?.id)
    }

    @Test
    fun aLineWithOnlyUnreleasedSeasonsHasNothingToPlay() {
        val line = line(season("1", 1, released = false))
        assertNull(SeasonChain.latest(line))
    }
}
