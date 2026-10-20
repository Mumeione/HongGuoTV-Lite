package com.hongguotv.core.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 定时停止：分钟/集数两种模式，时间优先，到点只触发一次。 */
class SleepTimerTest {

    private var now = 1_000_000L
    private fun timer() = SleepTimer { now }

    @Test
    fun minutesCountDownOnTheInjectedClock() {
        val sleep = timer()
        sleep.afterMinutes(15)
        assertEquals(15 * 60_000L, sleep.remainingMs)
        now += 60_000L
        assertEquals(14 * 60_000L, sleep.remainingMs)
        assertFalse(sleep.poll())
    }

    @Test
    fun pollFiresOnceAndCancelsItself() {
        val sleep = timer()
        sleep.afterMinutes(1)
        now += 60_000L
        assertTrue(sleep.poll())
        // 到点即取消：再轮询不会重复触发，用户按了"继续播放"之后不该又被停一次
        assertFalse(sleep.poll())
        assertNull(sleep.remainingMs)
    }

    @Test
    fun episodesCountDownPerFinishedEpisode() {
        val sleep = timer()
        sleep.afterEpisodes(3)
        assertFalse(sleep.episodeEnded())
        assertEquals(2, sleep.episodesLeft)
        assertFalse(sleep.episodeEnded())
        assertTrue(sleep.episodeEnded())
        assertEquals(0, sleep.episodesLeft)
        // 已经停止之后，后续集结束不再触发
        assertFalse(sleep.episodeEnded())
    }

    @Test
    fun timeWinsOverEpisodeCount() {
        val sleep = timer()
        sleep.afterMinutes(1)
        now += 60_000L
        assertTrue(sleep.episodeEnded())
    }

    @Test
    fun settingMinutesClearsTheEpisodeCounterAndViceVersa() {
        val sleep = timer()
        sleep.afterEpisodes(5)
        sleep.afterMinutes(30)
        assertEquals(0, sleep.episodesLeft)
        sleep.afterEpisodes(2)
        assertNull(sleep.remainingMs)
    }

    @Test
    fun cancelClearsEverything() {
        val sleep = timer()
        sleep.afterMinutes(10)
        sleep.cancel()
        assertNull(sleep.remainingMs)
        assertEquals(0, sleep.episodesLeft)
        assertFalse(sleep.episodeEnded())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroMinutes() {
        timer().afterMinutes(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooManyEpisodes() {
        timer().afterEpisodes(11)
    }
}
