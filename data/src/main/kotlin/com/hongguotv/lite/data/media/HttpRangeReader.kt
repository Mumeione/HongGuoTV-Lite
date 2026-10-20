package com.hongguotv.lite.data.media

import com.hongguotv.core.protocol.RequestDefaults
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 视频分段的 HTTP 实现。
 *
 * 三条硬校验（行为基准：旧仓库 `RemoteVideo.range`）——每一条都对应一次实测事故：
 * - **必须是 206**：200 说明服务器把整个文件塞回来了，不按区间给就说明它不支持分段；
 * - **`Content-Range` 必须与请求逐字节吻合**：错位的字节解出来是花屏；
 * - **总长前后不能变**：变了说明这条播放地址已经指向别的资源，继续播只会越来越乱。
 *
 * UA 用取流那套（`RequestDefaults.USER_AGENT`，版本 73532）：同一个视频地址换 UA 会被判成别的客户端。
 */
class HttpRangeReader(
    private val http: OkHttpClient,
    private val url: String,
) : RangeReader {

    @Volatile
    private var closed = false
    private val calls = mutableSetOf<Call>()

    override fun read(start: Long, endInclusive: Long): RangeChunk {
        require(start >= 0 && endInclusive >= start) { "区间非法：$start-$endInclusive" }
        require(endInclusive - start < MAX_RANGE) { "单次分段过大：$start-$endInclusive" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", RequestDefaults.USER_AGENT)
            .header("Referer", VIDEO_REFERER)
            .header("Range", "bytes=$start-$endInclusive")
            .header("Accept-Encoding", "identity")
            .build()
        val call = http.newCall(request)
        synchronized(calls) {
            if (closed) throw IOException("播放请求已取消")
            calls += call
        }
        try {
            call.execute().use { response ->
                if (response.code != 206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val parsed = ContentRange.parse(response.header("Content-Range"))
                    ?: throw IOException("视频分段响应异常")
                val expectedLast = minOf(endInclusive, parsed.total - 1)
                if (parsed.start != start || parsed.endInclusive != expectedLast) {
                    throw IOException("视频分段范围不匹配")
                }
                val body = response.body ?: throw IOException("视频分段为空")
                val wanted = (expectedLast - start + 1).toInt()
                val bytes = body.source().readByteArray(wanted.toLong())
                if (bytes.size != wanted) throw IOException("视频分段长度不足")
                return RangeChunk(bytes, parsed.total)
            }
        } finally {
            synchronized(calls) { calls -= call }
        }
    }

    override fun close() {
        synchronized(calls) {
            closed = true
            calls.toList().forEach { it.cancel() }
            calls.clear()
        }
    }

    private companion object {
        /** 单次请求上限：8MB。再大就用不上，还容易把老盒子的内存顶穿。 */
        const val MAX_RANGE = 8L * 1024 * 1024
        const val VIDEO_REFERER = "https://novel.snssdk.com/"
    }
}
