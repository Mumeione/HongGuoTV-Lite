package com.hongguotv.lite.feature.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.port.MediaStream
import java.io.EOFException
import java.io.IOException

/**
 * 把 [MediaStream]（`MediaPort` 给的已解密随机读流）接成 media3 的 `DataSource`。
 *
 * **这是 media3 与端口之间唯一的桥**：播放器不知道 Range、CENC、moov 的存在，
 * 只知道"给我 offset 处的 size 个字节"。旧实现（`VideoDataSource`）做的是同一件事，
 * 这里多一层 `read` 缓冲——`MediaStream.read` 每次都是一次 HTTP Range，按 512KB 取齐才划算。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StreamDataSource(private val stream: MediaStream) : BaseDataSource(true) {

    /** 只由加载线程读写。 */
    private var position = 0L

    // 下面四个字段 `close()` 可能在应用线程上改（清零 / 换空数组），`read()` 在加载线程上读。
    // 至少要保证可见性，否则"关掉之后还从 buffer 里读到旧字节"就成立了；
    // 不追求完整互斥——读到的是 close 前还是 close 后的字节数都可以，唯独不能读到上一段的残留。
    @Volatile
    private var remaining = 0L

    @Volatile
    private var buffer = ByteArray(0)

    @Volatile
    private var offset = 0

    @Volatile
    private var opened = false

    private var uri: Uri? = null

    override fun open(spec: DataSpec): Long {
        // 边界校验放在任何 transfer* 回调之前：抛出去之后不会留下"初始化了却没结束"的账
        // （BaseDataSource 的 listener 记账要求 initializing/started 与 ended 成对）。
        if (spec.position > stream.length) throw IOException("读取位置超出视频长度")
        transferInitializing(spec)
        uri = spec.uri
        position = spec.position
        remaining = if (spec.length == C.LENGTH_UNSET.toLong()) {
            stream.length - position
        } else {
            minOf(spec.length, stream.length - position)
        }
        buffer = ByteArray(0)
        offset = 0
        opened = true
        transferStarted(spec)
        return remaining
    }

    override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        // 关掉之后再被读到就报错，不能拿 buffer 里的旧字节接着喂给播放器
        if (!opened) throw IOException("数据源已关闭")
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (offset >= buffer.size) {
            buffer = stream.read(position, minOf(READ_CHUNK, remaining).toInt())
            offset = 0
        }
        if (buffer.isEmpty()) throw EOFException("视频分段意外结束")
        val count = minOf(length, buffer.size - offset, remaining.toInt())
        buffer.copyInto(target, targetOffset, offset, offset + count)
        offset += count
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        buffer = ByteArray(0)
        offset = 0
        // 不清的话，"关掉之后还读到旧字节"会算成正常数据：remaining 是 read 的唯一闸门
        remaining = 0
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        /** 一次从流里取多少字节。512KB 是"少请求"和"占内存少"之间的折中（旧实现同值）。 */
        const val READ_CHUNK = 512L * 1024
    }
}
