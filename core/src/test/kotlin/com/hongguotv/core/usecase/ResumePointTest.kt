package com.hongguotv.core.usecase

import com.hongguotv.core.model.Episode
import com.hongguotv.core.model.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePointTest {

    private val episodes = List(5) { Episode(vid = "${1000 + it}", index = it) }

    private fun progress(
        vid: String,
        index: Int,
        positionMs: Long,
        durationMs: Long = 90_000L,
        completed: Boolean = false,
    ) = Progress("s1", vid, index, positionMs, durationMs, completed, 0L)

    @Test
    fun withoutRecordStartFromTheVeryFirstEpisode() {
        val target = ResumePoint.of(episodes, null)
        assertEquals(ResumeTarget(0, 0L, false), target)
    }

    @Test
    fun anEmptyEpisodeListFallsBackToTheStart() {
        assertEquals(ResumeTarget(0, 0L, false), ResumePoint.of(emptyList(), progress("1002", 2, 30_000L)))
    }

    /** 「续播 2 秒」没有意义：位置太靠前就归零，但记录本身仍然在。 */
    @Test
    fun aCheckpointUnderFiveSecondsCountsAsNotStarted() {
        val checkpoint = ResumePoint.checkpoint(4_999L, 90_000L, completed = false)
        assertEquals(0L, checkpoint.positionMs)
        assertFalse(checkpoint.completed)
        assertEquals(5_000L, ResumePoint.checkpoint(5_000L, 90_000L, completed = false).positionMs)
    }

    /** 距片尾 3 秒内算看完 → 落点挪到下一集。 */
    @Test
    fun aCheckpointNearTheEndMovesToTheNextEpisode() {
        val target = ResumePoint.of(episodes, progress("1002", 2, 88_500L, durationMs = 90_000L))
        assertEquals(2 + 1, target.index)
        assertEquals(0L, target.positionMs)
        assertTrue(target.finished)
    }

    @Test
    fun aCompletedEpisodeAlsoMovesToTheNextOne() {
        val target = ResumePoint.of(episodes, progress("1001", 1, 12_000L, completed = true))
        assertEquals(2, target.index)
        assertEquals(0L, target.positionMs)
        assertTrue(target.finished)
    }

    /** 最后一集看完没有"下一集"：停在原集第 0 秒（重看本集），不越界。 */
    @Test
    fun theLastEpisodeStaysOnItselfWhenFinished() {
        val target = ResumePoint.of(episodes, progress("1004", 4, 89_000L, completed = true))
        assertEquals(4, target.index)
        assertEquals(0L, target.positionMs)
    }

    @Test
    fun aRecordWhoseVidIsGoneFallsBackToTheFirstEpisode() {
        val target = ResumePoint.of(episodes, progress("9999", 2, 40_000L))
        assertEquals(0, target.index)
        assertEquals(0L, target.positionMs)
    }

    @Test
    fun aNormalRecordKeepsItsEpisodeAndPosition() {
        val target = ResumePoint.of(episodes, progress("1003", 3, 42_000L))
        assertEquals(3, target.index)
        assertEquals(42_000L, target.positionMs)
        assertFalse(target.finished)
    }
}
