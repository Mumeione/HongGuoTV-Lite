package com.hongguotv.lite.data.media

/**
 * `Content-Range` 响应头的解析。**单独成对象是为了能单测**：
 * 真实播放最怕的不是网络慢，而是"服务器说给了 0-1023，其实给了别的一段"——
 * 拿着错位的字节去解码，表现为花屏或莫名崩溃，排查成本极高。
 *
 * 只接受唯一一种写法（HTTP 规范里 `bytes` 之后的区间）：`bytes 0-1023/20480`。
 */
object ContentRange {

    data class Parsed(val start: Long, val endInclusive: Long, val total: Long)

    private val PATTERN = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    fun parse(header: String?): Parsed? {
        val match = PATTERN.matchEntire(header?.trim().orEmpty()) ?: return null
        val (start, end, total) = match.destructured
        val parsed = Parsed(start.toLongOrNull() ?: return null, end.toLongOrNull() ?: return null, total.toLongOrNull() ?: return null)
        if (parsed.start < 0 || parsed.endInclusive < parsed.start || parsed.total <= 0) return null
        if (parsed.endInclusive >= parsed.total) return null
        return parsed
    }
}

/** 一次分段读取的结果：这段字节 + 资源总长。 */
data class RangeChunk(val bytes: ByteArray, val total: Long)

/**
 * 分段读取的接缝。真实实现走 HTTP；单测给一个假实现，就不用起服务器。
 *
 * 约定：区间是**闭区间** `[start, endInclusive]`，越界由调用方先夹好。
 */
interface RangeReader {
    fun read(start: Long, endInclusive: Long): RangeChunk

    /** 取消在途请求。播放页退出时调用，别让后台还挂着连接。 */
    fun close()
}
