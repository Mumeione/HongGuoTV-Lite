package com.hongguotv.lite.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.io.IOException

/**
 * 分段缓冲读取的三条契约：open 的剩余长度、read 的末尾与越界、close 之后不可读。
 *
 * 全部用假流跑：真实的 `MediaStream` 要一条解密过的 HTTP Range 流，而起播黑屏与花屏
 * 恰恰都出在这段算术上——它必须在没有网络、没有设备的地方也能被验证。
 */
class BufferedRangeReaderTest {

    /** 记录每次向"上游"要了哪一段，并按真流的行为在末尾截断。 */
    private class FakeStream(private val bytes: ByteArray) {
        val requests = mutableListOf<Pair<Long, Int>>()
        var truncated = false

        fun fetch(offset: Long, size: Int): ByteArray {
            requests += offset to size
            if (truncated || offset >= bytes.size) return ByteArray(0)
            val end = minOf(offset + size, bytes.size.toLong())
            return bytes.copyOfRange(offset.toInt(), end.toInt())
        }
    }

    private fun readerFor(stream: FakeStream, length: Long, chunk: Int = 4) =
        BufferedRangeReader(length, chunkSize = chunk) { offset, size -> stream.fetch(offset, size) }

    @Test
    fun openReportsTheReadableLengthForBothOpenEndedAndBoundedRequests() {
        val reader = readerFor(FakeStream(ByteArray(1000)), 1000)

        assertEquals(1000L, reader.open(0, null))
        assertEquals(300L, reader.open(200, 300))
        assertEquals(200L, reader.open(800, 500))
        assertEquals(0L, reader.open(1000, null))
    }

    @Test
    fun openRejectsAPositionPastTheEnd() {
        val reader = readerFor(FakeStream(ByteArray(1000)), 1000)

        assertThrows(IOException::class.java) { reader.open(1001, null) }
        assertFalse(reader.isOpen)
    }

    @Test
    fun readsAreByteExactAcrossChunkBoundaries() {
        val bytes = ByteArray(10) { it.toByte() }
        val reader = readerFor(FakeStream(bytes), 10)
        reader.open(0, null)
        val out = ByteArray(16)
        val collected = mutableListOf<Byte>()

        while (true) {
            val n = reader.read(out, 0, 3)
            if (n < 0) break
            for (i in 0 until n) collected += out[i]
        }

        assertEquals(bytes.toList(), collected)
    }

    @Test
    fun theBufferIsReusedBeforeAnotherRequest() {
        val stream = FakeStream(ByteArray(10))
        val reader = readerFor(stream, 10)
        reader.open(0, null)
        val out = ByteArray(1)

        // 一次 4 字节：前四次读都从这一块里切，第五次才需要再向流要
        repeat(4) { reader.read(out, 0, 1) }
        assertEquals(listOf(0L to 4), stream.requests)

        reader.read(out, 0, 1)
        assertEquals(listOf(0L to 4, 4L to 4), stream.requests)
    }

    @Test
    fun readingPastTheEndReturnsEndOfInput() {
        val stream = FakeStream(ByteArray(10))
        val reader = readerFor(stream, 10)
        reader.open(0, null)
        val out = ByteArray(16)

        var total = 0
        while (true) {
            val n = reader.read(out, 0, out.size)
            if (n < 0) break
            total += n
        }

        assertEquals(10, total)
        assertEquals(-1, reader.read(out, 0, out.size))
        assertEquals(3, stream.requests.size)
    }

    @Test
    fun aZeroLengthReadReturnsZeroEvenAtTheEnd() {
        val reader = readerFor(FakeStream(ByteArray(10)), 10)
        reader.open(10, null)

        assertEquals(0, reader.read(ByteArray(4), 0, 0))
    }

    @Test
    fun readsAfterCloseAreRefused() {
        val reader = readerFor(FakeStream(ByteArray(10)), 10)
        reader.open(0, null)
        reader.close()

        assertThrows(IOException::class.java) { reader.read(ByteArray(4), 0, 4) }
    }

    @Test
    fun aClosedReaderCanBeReopenedOnAnotherRange() {
        val bytes = ByteArray(10) { it.toByte() }
        val reader = readerFor(FakeStream(bytes), 10)
        reader.open(0, 4)
        reader.close()

        assertEquals(4L, reader.open(4, 4))
        val out = ByteArray(4)
        assertEquals(4, reader.read(out, 0, 4))
        assertEquals(listOf<Byte>(4, 5, 6, 7), out.toList())
    }

    @Test
    fun anEmptyChunkBeforeTheEndIsATruncatedStream() {
        val stream = FakeStream(ByteArray(10)).apply { truncated = true }
        val reader = readerFor(stream, 10)
        reader.open(0, null)

        assertThrows(EOFException::class.java) { reader.read(ByteArray(4), 0, 4) }
    }
}
