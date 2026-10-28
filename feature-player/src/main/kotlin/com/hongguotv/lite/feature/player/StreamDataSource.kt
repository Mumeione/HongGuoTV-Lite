package com.hongguotv.lite.feature.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.port.MediaStream
import java.io.IOException

/**
 * 把 [MediaStream]（`MediaPort` 给的已解密随机读流）接成 media3 的 `DataSource`。
 *
 * **这是 media3 与端口之间唯一的桥**：播放器不知道 Range、CENC、moov 的存在，
 * 只知道"给我 offset 处的 size 个字节"。缓冲与三条读取契约都在 [BufferedRangeReader] 里，
 * 本类只负责 media3 的记账与回调顺序。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StreamDataSource(stream: MediaStream) : BaseDataSource(true) {

    private val streamLength = stream.length
    private val reader = BufferedRangeReader(streamLength) { offset, size -> stream.read(offset, size) }

    private var uri: Uri? = null

    override fun open(spec: DataSpec): Long {
        // 边界校验放在任何 transfer* 回调之前：抛出去之后不会留下"初始化了却没结束"的账
        // （BaseDataSource 的 listener 记账要求 initializing/started 与 ended 成对）。
        if (spec.position > streamLength) throw IOException("读取位置超出视频长度")
        transferInitializing(spec)
        uri = spec.uri
        val remaining = reader.open(
            spec.position,
            spec.length.takeIf { it != C.LENGTH_UNSET.toLong() },
        )
        transferStarted(spec)
        return remaining
    }

    override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        val count = reader.read(target, targetOffset, length)
        // 读到头时的 -1 原样返回：与 media3 的 C.RESULT_END_OF_INPUT 同值
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val wasOpen = reader.isOpen
        reader.close()
        uri = null
        if (wasOpen) transferEnded()
    }
}
