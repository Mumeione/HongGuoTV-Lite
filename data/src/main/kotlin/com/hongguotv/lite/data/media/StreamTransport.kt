package com.hongguotv.lite.data.media

import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.MediaPort
import com.hongguotv.core.port.MediaStream
import com.hongguotv.core.port.SilentDiagnostics
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.MediaCrypto
import com.hongguotv.lite.data.diagnostics.stageTimed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * `MediaPort` 的实现：把"播放标识"变成一条**可随机读取的已解密流**。
 *
 * 它把三件事串起来（旧仓库 `RemoteVideo` 的职责，不 import 旧代码）：
 * 1. `ContentPort.stream` 换地址与内容密钥（签名、设备身份、清晰度选择都在端口背后）；
 * 2. 预热文件头，解析 MP4 索引（CENC 的样本表在 `moov` 里）；
 * 3. 按区间读字节，并在**绝对偏移**上逐样本解密——解密器要的是文件坐标，不是这段数据的坐标。
 *
 * 对外只有 `length` / `read(offset,size)` / `close()`：调用方（播放器）不知道 Range、
 * CENC、moov 的存在，也就不可能因为上游换封装而改动。
 */
class StreamTransport(
    private val content: ContentPort,
    /** 为一条播放地址造分段读取器。测试注入假实现，生产注入 [HttpRangeReader]。 */
    private val readerFor: (url: String) -> RangeReader,
    private val diagnostics: DiagnosticsPort = SilentDiagnostics,
) : MediaPort {

    override suspend fun open(vid: String, maxHeight: Int): MediaStream {
        var opened: Session? = null
        return try {
            withContext(Dispatchers.IO) {
                val info = content.stream(vid, maxHeight)
                // 换地址是网络调用：取消就到此为止，不必再拉 256KB 头、解一遍全量索引
                ensureActive()
                // 预热（拉头 + 解索引）放在 open 里：它慢，但不该慢在播放器的读取线程上。
                // checkActive 让两段阻塞读之间也能响应取消（用户连按跳集时取消的是上一个 vid）。
                val session = Session(readerFor(info.url), info.key, diagnostics, checkActive = { ensureActive() })
                opened = session
                // 交出去之前的最后一次检查：withContext 只在**返回点**才发现取消的话，
                // 这条已经建好的流没人接得住，reader 与 Range 连接就漏在连接池里了。
                ensureActive()
                diagnostics.event("stream.open", mapOf("length" to session.length, "encrypted" to (info.key != null)))
                session
            }
        } catch (cancelled: CancellationException) {
            // 取消不是失败：原样抛给调用方，但先把自己开出来的连接收掉
            opened?.close()
            throw cancelled
        }
    }

    companion object {
        /** 生产组装：`app`（S7）用它把 `MediaPort` 塞进端口表。 */
        fun of(
            content: ContentPort,
            http: OkHttpClient,
            diagnostics: DiagnosticsPort = SilentDiagnostics,
            /** 分段请求的 UA：与注册档案同源（默认平板档案）。 */
            userAgent: () -> String = { com.hongguotv.core.protocol.RequestDefaults.USER_AGENT },
        ): StreamTransport = StreamTransport(
            content,
            { url -> HttpRangeReader(http, url, userAgent()) },
            diagnostics,
        )
    }

    /**
     * 一条播放流。读取是**同步**的：调用方是播放器的加载线程，
     * 它本来就是为阻塞读取准备的（旧实现同样如此）。
     */
    private class Session(
        private val reader: RangeReader,
        private val key: ByteArray?,
        private val diagnostics: DiagnosticsPort,
        /** 取消检查：两段阻塞读之间问一次，被取消的那次取流不该把整条索引解完。 */
        private val checkActive: () -> Unit = {},
    ) : MediaStream {

        override var length: Long = 0L
            private set

        private val prepared: MediaCrypto.Prepared
        /** 已读到的连续前缀（0 起）。首屏与片头反复读同一段，缓存住省一半请求。 */
        private val prefix: ByteArray
        @Volatile
        private var closed = false

        init {
            val ready = prepare()
            prepared = ready.index
            prefix = ready.prefix
        }

        private class Prepared(val index: MediaCrypto.Prepared, val prefix: ByteArray)

        /**
         * 拉文件头 + 解索引。
         *
         * **失败分类要分得开**：读不到字节是网络问题（`UpstreamError.Network`），
         * 读到了但解不开容器/索引才是"上游换了封装"（`UpstreamError.Decrypt`）。
         * 把断网说成"内容解析失败"，用户会去换清晰度而不是去检查网络。
         */
        private fun prepare(): Prepared = try {
            val first = readOrFail(0L, PREFIX_BYTES - 1L)
            length = first.total
            var cache = first.bytes
            // 头拉回来之后先问一次：被取消的取流不必再往下解索引（那是 CPU 最重的一段）
            checkActive()
            val headerEnd = diagnostics.stageTimed(DiagnosticsPort.Stage.DECRYPT, "stream.header") {
                try {
                    MediaCrypto.headerEnd(cache, length)
                } catch (error: IOException) {
                    throw UpstreamError.Decrypt("index")
                }
            }
            if (headerEnd > cache.size) {
                cache += readOrFail(cache.size.toLong(), headerEnd - 1L).bytes
            }
            checkActive()
            val index = diagnostics.stageTimed(DiagnosticsPort.Stage.DECRYPT, "stream.index") {
                try {
                    MediaCrypto.prepare(cache.copyOf(headerEnd), key)
                } catch (error: IOException) {
                    throw UpstreamError.Decrypt("index")
                }
            }
            if (index.samples.any { it.end > length }) throw UpstreamError.Decrypt("index")
            diagnostics.event(
                "stream.index",
                mapOf("samples" to index.samples.size, "encrypted" to (key != null), "length" to length),
            )
            Prepared(index, cache)
        } catch (error: Throwable) {
            reader.close()
            throw error
        }

        /** 网络层的失败原样归到 Network，不要混进"解密失败"。 */
        private fun readOrFail(start: Long, endInclusive: Long): RangeChunk = try {
            reader.read(start, endInclusive)
        } catch (error: IOException) {
            throw UpstreamError.Network(error)
        }

        override fun read(offset: Long, size: Int): ByteArray {
            // DataSource 的契约里 0 是合法请求（"这次没东西要读"），回空数组而不是抛
            if (size == 0) return ByteArray(0)
            require(size in 1..MAX_READ) { "单次读取大小非法：$size" }
            if (closed) throw IOException("播放请求已取消")
            // 先判 EOF 再判偏移上限：读超长偏移在短流上就是"读到结尾"，按契约回空数组
            if (offset >= length) return ByteArray(0)
            // 偏移是文件坐标，下面要按 Int 切片；超过 2GB 的资源不在这个应用的目标里（单集短剧），
            // 但静默截断会切成错位的字节喂给播放器，宁可报错也不要放半截花屏
            require(offset <= Int.MAX_VALUE) { "读取偏移超出单段上限：$offset" }
            val end = minOf(length, offset + size)
            val cachedBytes = (minOf(end, prefix.size.toLong()) - offset).coerceAtLeast(0).toInt()
            val head = if (cachedBytes > 0) prefix.copyOfRange(offset.toInt(), offset.toInt() + cachedBytes) else ByteArray(0)
            val raw = if (offset + cachedBytes < end) {
                val chunk = try {
                    reader.read(offset + cachedBytes, end - 1L)
                } catch (error: IOException) {
                    throw IOException("视频分段读取失败", error)
                }
                // 资源换了就别接着放：半截旧数据 + 半截新数据比直接报错更难查
                if (chunk.total != length) throw IOException("视频资源已变化，请重试")
                head + chunk.bytes
            } else {
                head
            }
            return try {
                MediaCrypto.decrypt(raw, offset, key, prepared)
            } catch (error: Exception) {
                throw IOException("视频解密失败", error)
            }
        }

        override fun close() {
            closed = true
            reader.close()
        }

        private companion object {
            const val PREFIX_BYTES = 256 * 1024
            const val MAX_READ = 1024 * 1024
        }
    }
}
