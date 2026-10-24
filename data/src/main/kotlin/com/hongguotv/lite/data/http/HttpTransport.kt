package com.hongguotv.lite.data.http

import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.SilentDiagnostics
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.RequestDefaults
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.lite.data.diagnostics.elapsedMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 上游 HTTP 的唯一出口。所有运行期网络失败在这里分类成 [UpstreamError]，
 * 调用方（ContentRepository）不再碰 IOException，也不再用中文字符串区分错误。
 */
/** 一次 HTTP 响应里除正文之外还需要的东西：注册接口要读 `Set-Cookie`。 */
data class HttpResponse(val body: String, val setCookies: List<String> = emptyList())

interface HttpTransport {
    /** 执行一个已签名（或声明无签名）的请求，返回响应体文本。失败一律抛 [UpstreamError]。 */
    suspend fun text(request: SignedRequest): String

    /**
     * 需要响应头的场合（设备注册读 `Set-Cookie`）。
     * 默认实现只回正文：既有的假体只关心 body，不必为此改一遍。
     */
    suspend fun response(request: SignedRequest): HttpResponse = HttpResponse(text(request))
}

class OkHttpTransport(
    private val http: OkHttpClient = defaultClient(),
    private val diagnostics: DiagnosticsPort = SilentDiagnostics,
) : HttpTransport {

    override suspend fun text(request: SignedRequest): String = response(request).body

    override suspend fun response(request: SignedRequest): HttpResponse = withContext(Dispatchers.IO) {
        // 日志只到 host + path：query 里有设备号、时间戳与搜索词，不能落进 logcat。
        val target = request.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}${it.encodedPath}" } ?: "invalid-url"
        val started = System.nanoTime()
        var status: Int? = null
        fun report(ok: Boolean, outcome: String) {
            diagnostics.stage(
                DiagnosticsPort.Stage.CONNECT,
                ok,
                "${request.method} $target $outcome ${elapsedMs(started)}ms",
            )
        }
        val builder = Request.Builder().url(request.url)
        // 无签名通道（头集合为空）补默认 UA；签名请求的头集合由 Signer 全权组装，这里不掺手——
        // UA 是风控身份字段，两套签名各用各的，掺手等于伪造身份。
        if (request.headers.isEmpty()) {
            builder.header("User-Agent", RequestDefaults.USER_AGENT)
            builder.header("Accept-Language", RequestDefaults.ACCEPT_LANGUAGE)
        }
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        val bodyBytes = request.body
        if (bodyBytes != null) {
            builder.post(bodyBytes.toRequestBody(request.contentType.toMediaType()))
        }
        try {
            http.newCall(builder.build()).execute().use { response ->
                status = response.code
                if (!response.isSuccessful) throw UpstreamError.HttpStatus(response.code)
                val body = response.body ?: throw UpstreamError.EmptyBody()
                // 超限沿用旧口径：旧实现抛 IOException，在新分类下本来就落 Network；
                // 六分类没有更贴切的位置，不为此扩分类
                val bytes = readCapped(body.byteStream(), MAX_BYTES)
                    ?: throw UpstreamError.Network(IOException("内容响应过大"))
                val text = String(bytes, Charsets.UTF_8)
                // 200 空体是风控的强特征，单独成类，绝不能归成"这部剧没有剧集"
                if (text.isBlank()) throw UpstreamError.EmptyBody()
                report(true, response.code.toString())
                HttpResponse(text, response.headers("Set-Cookie"))
            }
        } catch (e: UpstreamError) {
            // 200 空体的现场必须带上状态码：只写 "empty body" 会让人分不清是风控还是解析失败
            report(false, if (e is UpstreamError.HttpStatus) e.code.toString() else "${status ?: "-"} ${e.message}")
            throw e
        } catch (e: IOException) {
            report(false, "${status ?: "-"} ${e.javaClass.simpleName}")
            throw UpstreamError.Network(e)
        }
    }

    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024

        /** 超时口径与旧实现一致（15s/30s/45s），行为基准不变。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        private fun readCapped(input: InputStream, cap: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > cap) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
