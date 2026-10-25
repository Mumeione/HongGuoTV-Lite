package com.hongguotv.core.usecase

import com.hongguotv.core.usecase.PrefetchPolicy.Decision
import com.hongguotv.core.usecase.PrefetchPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 片尾预取策略：每条用例只回答"这一秒该不该去取下一集的流"。
 *
 * 纯函数，不碰网络、不碰播放器；真机上最难复现的"2 倍速来不及、计费网络不预取、
 * 失败退避"都在这里断言。
 */
class PrefetchPolicyTest {

    private fun input(
        positionMs: Long = 295_000,
        durationMs: Long = 300_000,
        speed: Float = 1f,
        playing: Boolean = true,
        autoNext: Boolean = true,
        hasNext: Boolean = true,
        lowRam: Boolean = false,
        online: Boolean = true,
        metered: Boolean = false,
        readyForMs: Long = 10_000,
        bufferedAheadMs: Long = 20_000,
        attempts: Int = 0,
        sinceLastAttemptMs: Long = Long.MAX_VALUE,
        alreadyPrepared: Boolean = false,
        inFlight: Boolean = false,
    ) = PrefetchPolicy.Input(
        positionMs = positionMs,
        durationMs = durationMs,
        speed = speed,
        playing = playing,
        autoNext = autoNext,
        hasNext = hasNext,
        lowRam = lowRam,
        online = online,
        metered = metered,
        readyForMs = readyForMs,
        bufferedAheadMs = bufferedAheadMs,
        attempts = attempts,
        sinceLastAttemptMs = sinceLastAttemptMs,
        alreadyPrepared = alreadyPrepared,
        inFlight = inFlight,
    )

    private fun reasonOf(input: PrefetchPolicy.Input): Reason =
        (PrefetchPolicy.decide(input) as Decision.Skip).reason

    @Test
    fun startsOnlyInsideTheLastTenSeconds() {
        // 短剧一集约 1 分钟：剩 9 秒才预取，剩 11 秒还太早
        assertEquals(Decision.Fetch, PrefetchPolicy.decide(input(positionMs = 291_000)))
        assertEquals(Reason.TOO_EARLY, reasonOf(input(positionMs = 289_000)))
    }

    @Test
    fun theWindowIsWallClockTimeNotMediaTime() {
        // 2 倍速下还剩 20 秒媒体时间，用户 10 秒后就会撞到片尾：必须按墙钟算
        assertEquals(Decision.Fetch, PrefetchPolicy.decide(input(positionMs = 280_000, speed = 2f)))
        assertEquals(Reason.TOO_EARLY, reasonOf(input(positionMs = 279_000, speed = 2f)))
    }

    @Test
    fun aPausedEpisodeNeverStartsAPrefetch() {
        assertEquals(Reason.NOT_PLAYING, reasonOf(input(playing = false)))
    }

    @Test
    fun autoNextOffOrTheLastEpisodeHasNothingToPrefetch() {
        assertEquals(Reason.AUTO_NEXT_OFF, reasonOf(input(autoNext = false)))
        assertEquals(Reason.NO_NEXT_EPISODE, reasonOf(input(hasNext = false)))
    }

    @Test
    fun constrainedNetworksAndLowMemoryDevicesNeverPrefetch() {
        assertEquals(Reason.LOW_RAM, reasonOf(input(lowRam = true)))
        assertEquals(Reason.OFFLINE, reasonOf(input(online = false)))
        assertEquals(Reason.METERED, reasonOf(input(metered = true)))
    }

    @Test
    fun anAlreadyPreparedOrInFlightStreamIsNotOpenedTwice() {
        assertEquals(Reason.ALREADY_PREPARED, reasonOf(input(alreadyPrepared = true)))
        assertEquals(Reason.IN_FLIGHT, reasonOf(input(inFlight = true)))
    }

    @Test
    fun aPreparedStreamIsKeptEvenIfTheNetworkBecomesMetered() {
        // 已经备好的流不该因为网络变了就被丢掉：它已经付过流量了
        assertEquals(
            Reason.ALREADY_PREPARED,
            reasonOf(input(alreadyPrepared = true, metered = true)),
        )
    }

    @Test
    fun unknownTimingIsSkippedInsteadOfGuessed() {
        assertEquals(Reason.UNKNOWN_TIMING, reasonOf(input(durationMs = 0)))
        assertEquals(Reason.UNKNOWN_TIMING, reasonOf(input(positionMs = 300_000)))
        assertEquals(Reason.UNKNOWN_TIMING, reasonOf(input(speed = 0f)))
        assertEquals(Reason.UNKNOWN_TIMING, reasonOf(input(speed = Float.NaN)))
    }

    @Test
    fun prefetchWaitsUntilTheEpisodeHasSettledAndBufferedAhead() {
        assertEquals(Reason.NOT_READY_LONG_ENOUGH, reasonOf(input(readyForMs = 2_999)))
        assertEquals(Reason.LOW_BUFFER, reasonOf(input(bufferedAheadMs = 1_999)))
    }

    @Test
    fun failedAttemptsBackOffAndThenStop() {
        assertEquals(Reason.RETRY_BACKOFF, reasonOf(input(attempts = 1, sinceLastAttemptMs = 2_999)))
        assertEquals(Decision.Fetch, PrefetchPolicy.decide(input(attempts = 1, sinceLastAttemptMs = 3_000)))
        assertEquals(Reason.ATTEMPTS_EXHAUSTED, reasonOf(input(attempts = 3)))
    }
}
