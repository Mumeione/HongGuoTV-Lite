package com.hongguotv.lite.data.media

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.StreamInfo
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.UpstreamError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 取流传输：Range 读取、前缀缓存、末尾夹住、资源变化、坏容器。
 *
 * 全部用假 [RangeReader] 跑，不起服务器——这几条都是"读错一个字节就是花屏"的逻辑，
 * 值得脱离网络单测。
 */
class StreamTransportTest {

    // ---------- 假件 ----------

    private class FakeContent(private val info: StreamInfo) : ContentPort {
        var qualityAsked = -1
        var vidAsked = ""

        override suspend fun stream(vid: String, maxHeight: Int): StreamInfo {
            vidAsked = vid
            qualityAsked = maxHeight
            return info
        }

        override suspend fun search(keyword: String, filter: ContentFilter): SearchResult = error("未使用")
        override suspend fun suggest(keyword: String): List<Suggestion> = error("未使用")
        override suspend fun detail(seriesId: String): Detail = error("未使用")
        override suspend fun seasons(seriesId: String, anchor: Series?): SeasonLine = error("未使用")
    }

    /** 按闭区间返回字节，行为与 [HttpRangeReader] 一致：末尾夹到资源末尾。 */
    private class FakeReader(
        private val source: ByteArray,
        var total: Long = source.size.toLong(),
    ) : RangeReader {
        var calls = 0
        var closed = false

        override fun read(start: Long, endInclusive: Long): RangeChunk {
            calls++
            if (closed) throw IOException("播放请求已取消")
            val last = minOf(endInclusive, total - 1)
            if (start > last) return RangeChunk(ByteArray(0), total)
            return RangeChunk(source.copyOfRange(start.toInt(), (last + 1).toInt()), total)
        }

        override fun close() {
            closed = true
        }
    }

    /** 一个"文件头在开头、正好 24 字节"的假 MP4：ftyp(16) + moov(8)，其余填 0xAB。 */
    private fun fakeFile(size: Int = 600 * 1024): ByteArray {
        val bytes = ByteArray(size) { 0xAB.toByte() }
        // ftyp box：长度 16
        for (i in 0..3) bytes[i] = 0
        bytes[3] = 0x10
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, 4)
        "isom".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        // moov box：长度 8（data 层的无密钥路径只需要通过 headerEnd，不需要真的解析样本表）
        for (i in 16..19) bytes[i] = 0
        bytes[19] = 0x08
        "moov".toByteArray(Charsets.US_ASCII).copyInto(bytes, 20)
        return bytes
    }

    private fun transport(
        source: ByteArray = fakeFile(),
        key: ByteArray? = null,
    ): Pair<StreamTransport, FakeReader> {
        val reader = FakeReader(source)
        val content = FakeContent(StreamInfo(url = "https://v.example/x.mp4", key = key, height = 720, fellBack = false))
        return StreamTransport(content, readerFor = { reader }) to reader
    }

    // ---------- 用例 ----------

    @Test
    fun theStreamLengthIsTheResourceLength() = runTest {
        val (transport, _) = transport()
        val stream = transport.open("vid-1", 720)
        assertEquals(600L * 1024, stream.length)
    }

    @Test
    fun theQualityAndVidArePassedThroughToThePort() = runTest {
        val reader = FakeReader(fakeFile())
        val content = FakeContent(StreamInfo("https://v.example/x.mp4", null, 480, fellBack = true))
        val transport = StreamTransport(content, readerFor = { reader })
        transport.open("vid-9", 480)
        assertEquals("vid-9", content.vidAsked)
        assertEquals(480, content.qualityAsked)
    }

    @Test
    fun readsAreByteExactAndClampedAtTheEnd() = runTest {
        val source = fakeFile()
        val (transport, _) = transport(source)
        val stream = transport.open("vid-1", 1080)

        assertArrayEquals(source.copyOfRange(0, 64), stream.read(0, 64))
        assertArrayEquals(source.copyOfRange(300_000, 300_032), stream.read(300_000, 32))

        val tail = stream.read(source.size - 8L, 16)
        assertEquals(8, tail.size)
        assertArrayEquals(source.copyOfRange(source.size - 8, source.size), tail)
    }

    @Test
    fun readingPastTheEndReturnsNothing() = runTest {
        val (transport, _) = transport()
        val stream = transport.open("vid-1", 1080)
        assertEquals(0, stream.read(stream.length, 16).size)
        assertEquals(0, stream.read(stream.length + 5_000, 16).size)
    }

    @Test
    fun theWarmUpPrefixIsReusedWithoutAnotherRequest() = runTest {
        val (transport, reader) = transport()
        val stream = transport.open("vid-1", 1080)
        val afterOpen = reader.calls
        stream.read(200_000, 100) // 在前缀（256KB）里
        assertEquals(afterOpen, reader.calls)
        stream.read(300_000, 100) // 前缀之外，必须再请求一次
        assertEquals(afterOpen + 1, reader.calls)
    }

    @Test
    fun aClosedStreamRefusesFurtherReads() = runTest {
        val (transport, reader) = transport()
        val stream = transport.open("vid-1", 1080)
        stream.close()
        assertThrows(IOException::class.java) { stream.read(0, 16) }
        assertTrue(reader.closed)
    }

    @Test
    fun aResourceThatChangedUnderneathIsRejected() = runTest {
        val (transport, reader) = transport()
        val stream = transport.open("vid-1", 1080)
        reader.total = 999L
        assertThrows(IOException::class.java) { stream.read(300_000, 100) }
    }

    @Test
    fun aContainerWithoutAFrontIndexFailsToOpen() {
        val (transport, reader) = transport(ByteArray(1024) { 0x11 })
        assertThrows(UpstreamError.Decrypt::class.java) {
            runBlocking { transport.open("vid-1", 1080) }
        }
        assertTrue(reader.closed)
    }

    // ---------- Content-Range 解析 ----------

    @Test
    fun contentRangeParsesTheOnlyAcceptedForm() {
        assertEquals(ContentRange.Parsed(0, 1023, 20480), ContentRange.parse("bytes 0-1023/20480"))
        assertEquals(ContentRange.Parsed(1024, 20479, 20480), ContentRange.parse(" bytes 1024-20479/20480 "))
    }

    @Test
    fun contentRangeRejectsBrokenHeaders() {
        assertNull(ContentRange.parse(null))
        assertNull(ContentRange.parse(""))
        assertNull(ContentRange.parse("bytes */20480"))
        assertNull(ContentRange.parse("bytes 0-1023"))
        assertNull(ContentRange.parse("items 0-1023/20480"))
        // 区间首尾颠倒、超出总长：都是"服务器给了别的段"，不能拿去解码
        assertNull(ContentRange.parse("bytes 1023-0/20480"))
        assertNull(ContentRange.parse("bytes 0-20480/20480"))
    }
}
