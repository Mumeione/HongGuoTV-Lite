package com.hongguotv.lite.feature.player

import java.io.EOFException
import java.io.IOException

/**
 * `MediaStream` 之上的分段缓冲读取。
 *
 * `MediaStream.read` 每调用一次就是一次 HTTP Range，按 [chunkSize] 取齐才划算，所以要有这层缓冲。
 * 把它从 media3 的 `DataSource` 里拆出来，是因为这里面有三条**只能靠单测钉住**的契约：
 *
 * - [open] 的返回值是本次真正可读的字节数：请求区间超出资源长度时夹住，起始位置越界直接拒绝；
 * - [read] 到剩余为 0 时返回 [END_OF_INPUT]，并且不会把越界的那部分算进拷贝长度；
 * - [close] 之后 [read] 一律抛 [IOException]：不能拿 buffer 里的残留字节继续喂播放器
 *   （那会让播放器把上一段的尾部当成新数据）。
 *
 * 线程约束：`read` 在播放器的加载线程上跑，`close` 可能在应用线程上跑。四个被 `close` 改写的
 * 字段标了 `@Volatile` 只为保证可见性，不追求完整互斥——读到 close 前还是 close 后的字节数都行，
 * 唯独不能读到上一段的残留。
 */
internal class BufferedRangeReader(
    private val streamLength: Long,
    private val chunkSize: Int = READ_CHUNK,
    private val fetch: (offset: Long, size: Int) -> ByteArray,
) {

    var isOpen: Boolean = false
        private set

    /** 只由加载线程读写。 */
    private var position = 0L

    @Volatile
    private var remaining = 0L

    @Volatile
    private var buffer = ByteArray(0)

    @Volatile
    private var bufferOffset = 0

    /**
     * 从 [position] 开始读，最多读 [requestedLength] 字节（`null` = 到资源末尾）。
     * 返回本次真正可读的字节数。
     */
    fun open(position: Long, requestedLength: Long?): Long {
        if (position > streamLength) throw IOException("读取位置超出视频长度")
        this.position = position
        remaining = if (requestedLength == null) {
            streamLength - position
        } else {
            minOf(requestedLength, streamLength - position)
        }
        buffer = ByteArray(0)
        bufferOffset = 0
        isOpen = true
        return remaining
    }

    /** 读出至多 [maxBytes] 字节；读到末尾返回 [END_OF_INPUT]。 */
    fun read(into: ByteArray, intoOffset: Int, maxBytes: Int): Int {
        if (!isOpen) throw IOException("数据源已关闭")
        if (maxBytes == 0) return 0
        if (remaining == 0L) return END_OF_INPUT
        if (bufferOffset >= buffer.size) {
            buffer = fetch(position, minOf(chunkSize.toLong(), remaining).toInt())
            bufferOffset = 0
        }
        if (buffer.isEmpty()) throw EOFException("视频分段意外结束")
        val count = minOf(maxBytes, buffer.size - bufferOffset, remaining.toInt())
        buffer.copyInto(into, intoOffset, bufferOffset, bufferOffset + count)
        bufferOffset += count
        position += count
        remaining -= count
        return count
    }

    fun close() {
        buffer = ByteArray(0)
        bufferOffset = 0
        // 不清的话，"关掉之后还读到旧字节"会算成正常数据：remaining 是 read 的唯一闸门
        remaining = 0
        isOpen = false
    }

    private companion object {
        /** 一次从流里取多少字节。512KB 是"少请求"和"占内存少"之间的折中（旧实现同值）。 */
        const val READ_CHUNK = 512 * 1024

        /** 与 media3 的 `C.RESULT_END_OF_INPUT` 同值：读到头时 `read` 的返回值。 */
        const val END_OF_INPUT = -1
    }
}
