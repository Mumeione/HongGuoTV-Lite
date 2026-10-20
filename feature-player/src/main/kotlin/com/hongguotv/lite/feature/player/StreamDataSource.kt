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

    private var position = 0L
    private var remaining = 0L
    private var buffer = ByteArray(0)
    private var offset = 0
    private var opened = false
    private var uri: Uri? = null

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        uri = spec.uri
        position = spec.position
        if (position > stream.length) throw IOException("读取位置超出视频长度")
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
