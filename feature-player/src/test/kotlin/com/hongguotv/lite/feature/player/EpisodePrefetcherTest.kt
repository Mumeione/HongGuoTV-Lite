package com.hongguotv.lite.feature.player

import com.hongguotv.core.port.MediaPort
import com.hongguotv.core.port.MediaStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 片尾预取的编排：什么时候去开流、失败怎么退避、换上目标时旧流归谁。
 *
 * 用未限定调度器 + 假端口把异步压成立即执行，所以不需要真网络、也不需要等待。
 * 判定条件本身另有 `core` 的 `PrefetchPolicyTest` 逐条覆盖。
 */
class EpisodePrefetcherTest {

    private class FakeStream : MediaStream {
        var closed = false
        override val length: Long = 1_000L
        override fun read(offset: Long, size: Int): ByteArray = ByteArray(size)
        override fun close() {
            closed = true
        }
    }

    private class FakeMedia : MediaPort {
        val opens = mutableListOf<Pair<String, Int>>()
        val streams = mutableListOf<FakeStream>()
        var nextFailure: Exception? = null

        override suspend fun open(vid: String, maxHeight: Int): MediaStream {
            opens += vid to maxHeight
            nextFailure?.let {
                nextFailure = null
                throw it
            }
            return FakeStream().also { streams += it }
        }
    }

    private var now = 10_000L
    private val media = FakeMedia()
    private val prefetcher = EpisodePrefetcher(
        scope = CoroutineScope(Dispatchers.Unconfined),
        media = media,
        clock = { now },
    )

    private fun playing(index: Int = 0) = PlaybackState.Playing(index, null, buffering = false)

    private fun conditions(
        positionMs: Long = 295_000,
        durationMs: Long = 300_000,
        bufferedPositionMs: Long = 299_000,
        playing: Boolean = true,
        autoNext: Boolean = true,
        lowRam: Boolean = false,
        online: Boolean = true,
        metered: Boolean = false,
    ) = EpisodePrefetcher.Conditions(
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = bufferedPositionMs,
        speed = 1f,
        playing = playing,
        autoNext = autoNext,
        lowRam = lowRam,
        online = online,
        metered = metered,
    )

    /** 起播满 3 秒后才允许预取；这几条用例都从"已经顺畅播了一会儿"开始。 */
    private fun ready() {
        prefetcher.onSessionState(playing())
        now += 5_000
    }

    @Test
    fun nearTheEndTheNextEpisodeIsOpenedAndHandedOver() {
        ready()
        prefetcher.tick("v1", 1080, conditions())
        assertEquals(listOf("v1" to 1080), media.opens)
        val stream = media.streams.single()
        assertTrue(prefetcher.take("v1", 1080) === stream)
        // 领走之后归播放器，槽位不该把它关掉
        assertFalse(stream.closed)
    }

    @Test
    fun nothingIsOpenedBeforeTheWindow() {
        ready()
        prefetcher.tick("v1", 1080, conditions(positionMs = 100_000))
        assertTrue(media.opens.isEmpty())
    }

    @Test
    fun pausingDropsThePreparedStreamInsteadOfHoldingIt() {
        ready()
        prefetcher.tick("v1", 1080, conditions())
        val stream = media.streams.single()
        prefetcher.tick("v1", 1080, conditions(playing = false))
        assertTrue(stream.closed)
        assertNull(prefetcher.take("v1", 1080))
    }

    @Test
    fun aFailureBacksOffAndThenRetriesInsideTheWindow() {
        ready()
        media.nextFailure = IOException("empty body")
        prefetcher.tick("v1", 1080, conditions(positionMs = 293_000))
        assertEquals(1, media.opens.size)

        now += 1_000
        prefetcher.tick("v1", 1080, conditions(positionMs = 294_000))
        assertEquals("退避期间不重发", 1, media.opens.size)

        now += 3_000
        prefetcher.tick("v1", 1080, conditions(positionMs = 297_000))
        assertEquals(2, media.opens.size)
        assertTrue(prefetcher.take("v1", 1080) === media.streams.single())
    }

    @Test
    fun changingTheTargetDropsTheOldPreparedStream() {
        ready()
        prefetcher.tick("v1", 1080, conditions())
        val old = media.streams.single()
        prefetcher.tick("v2", 1080, conditions())
        assertTrue(old.closed)
        assertEquals(2, media.opens.size)
    }

    @Test
    fun noNextEpisodeClearsTheSlot() {
        ready()
        prefetcher.tick("v1", 1080, conditions())
        val stream = media.streams.single()
        prefetcher.tick(null, 1080, conditions())
        assertTrue(stream.closed)
    }

    @Test
    fun lowRamAndMeteredNetworksNeverOpenTheNextStream() {
        ready()
        prefetcher.tick("v1", 1080, conditions(lowRam = true))
        prefetcher.tick("v1", 1080, conditions(metered = true))
        prefetcher.tick("v1", 1080, conditions(autoNext = false))
        assertTrue(media.opens.isEmpty())
    }

    @Test
    fun clearingCancelsTheAttemptAndDropsWhatArrived() {
        ready()
        media.nextFailure = IOException("empty body")
        prefetcher.tick("v1", 1080, conditions())
        assertEquals(1, media.opens.size)
        prefetcher.clear()

        // clear 之后重新起算：下一次不必等退避间隔也可以立刻试（例如暂停后继续）
        prefetcher.onSessionState(playing())
        now += 5_000
        prefetcher.tick("v1", 1080, conditions(positionMs = 290_000))
        assertEquals(2, media.opens.size)
    }
}
