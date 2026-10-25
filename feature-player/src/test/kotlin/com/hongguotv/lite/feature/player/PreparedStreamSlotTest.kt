package com.hongguotv.lite.feature.player

import com.hongguotv.core.port.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预备流槽位：一条流只该被关闭一次，而且关在"谁最后持有它"那一方。
 *
 * 用假流记录 `close()` 次数——泄漏与重复关闭都会在这里现形。
 */
class PreparedStreamSlotTest {

    private class FakeStream : MediaStream {
        var closed = false
        override val length: Long = 1_000L
        override fun read(offset: Long, size: Int): ByteArray = ByteArray(size)
        override fun close() {
            closed = true
        }
    }

    private var now = 1_000L
    private val slot = PreparedStreamSlot({ now })

    @Test
    fun aPreparedStreamIsHandedOverExactlyOnce() {
        val stream = FakeStream()
        slot.offer("v2:1080", stream)
        assertTrue(slot.isFresh("v2:1080"))
        assertTrue(slot.take("v2:1080") === stream)
        // 领走之后槽位空了，再领只能拿到 null；流本身不该被关（播放器接手）
        assertFalse(stream.closed)
        assertNull(slot.take("v2:1080"))
        assertFalse(stream.closed)
    }

    @Test
    fun aDifferentTargetClosesTheOldStream() {
        val old = FakeStream()
        slot.offer("v2:1080", old)
        val new = FakeStream()
        slot.offer("v3:1080", new)
        assertTrue(old.closed)
        assertFalse(new.closed)
        assertFalse(slot.isFresh("v2:1080"))
        assertTrue(slot.isFresh("v3:1080"))
    }

    @Test
    fun takingAnotherTargetClosesThePreparedOne() {
        val stream = FakeStream()
        slot.offer("v2:1080", stream)
        assertNull(slot.take("v2:720"))
        assertTrue(stream.closed)
    }

    @Test
    fun anExpiredStreamIsNotHandedOver() {
        val stream = FakeStream()
        slot.offer("v2:1080", stream)
        now += 120_001
        assertFalse(slot.isFresh("v2:1080"))
        assertNull(slot.take("v2:1080"))
        assertTrue(stream.closed)
    }

    @Test
    fun clearingTheSlotClosesTheStream() {
        val stream = FakeStream()
        slot.offer("v2:1080", stream)
        slot.clear()
        assertTrue(stream.closed)
        assertNull(slot.take("v2:1080"))
    }

    @Test
    fun offeringNeverLeaksTheStreamItReplaces() {
        val first = FakeStream()
        val second = FakeStream()
        slot.offer("v2:1080", first)
        slot.offer("v2:1080", second)
        assertTrue(first.closed)
        assertFalse(second.closed)
        // 同一个目标再收一条：新的留下、旧的关掉
        assertEquals(second, slot.take("v2:1080"))
    }
}
