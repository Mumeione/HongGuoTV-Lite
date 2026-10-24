package com.hongguotv.lite.data.content

import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.DeviceIdentity
import com.hongguotv.core.protocol.DeviceProfile
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.lite.data.http.HttpTransport
import com.hongguotv.lite.data.http.OkHttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

/**
 * 五段探针的接线测试：**只断言"打没打点、成败标没标对"**，不测日志格式。
 *
 * 这里刻意不 mock 日志：生产实现 `LogcatDiagnostics` 依赖 `android.util.Log`，
 * JVM 单测里没有它；探针只认 [DiagnosticsPort] 这个接缝。
 */
class DiagnosticsPipelineTest {

    private class Recording : DiagnosticsPort {
        val stages = mutableListOf<Triple<DiagnosticsPort.Stage, Boolean, String>>()
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun stage(stage: DiagnosticsPort.Stage, ok: Boolean, detail: String) {
            stages += Triple(stage, ok, detail)
        }

        override fun event(name: String, fields: Map<String, Any?>) {
            events += name to fields
        }

        fun has(stage: DiagnosticsPort.Stage, ok: Boolean): Boolean =
            stages.any { it.first == stage && it.second == ok }
    }

    private class FakeTransport(private val respond: (SignedRequest) -> String) : HttpTransport {
        override suspend fun text(request: SignedRequest): String = respond(request)
    }

    private fun repository(record: Recording, respond: (SignedRequest) -> String): ContentRepository =
        ContentRepository(
            http = FakeTransport(respond),
            devices = FixedDeviceIdentity(
                DeviceSnapshot(DeviceIdentity("7123456789012345678", "8123456789012345678"), DeviceProfile.DEFAULT),
            ),
            diagnostics = record,
        )

    // ---------- ContentPort 的四段 ----------

    @Test
    fun detailEmitsSignAndParseStagesAndOneEvent() = runTest {
        val record = Recording()
        val repo = repository(record) { detailBody() }

        repo.detail("7")

        assertTrue(record.has(DiagnosticsPort.Stage.SIGN, ok = true))
        assertTrue(record.has(DiagnosticsPort.Stage.PARSE, ok = true))
        val event = record.events.single { it.first == "detail.loaded" }.second
        assertEquals(2, event["episodes"])
    }

    @Test
    fun detailParseFailureIsReportedAsFailedParse() = runTest {
        val record = Recording()
        val repo = repository(record) { """{"code":0,"data":{}}""" }

        assertFailsWith<UpstreamError.Shape> { repo.detail("7") }

        assertTrue(record.stages.any { it.first == DiagnosticsPort.Stage.PARSE && !it.second })
        // 失败也要说明断在哪一段，detail 里带异常类型
        assertTrue(record.stages.single { it.first == DiagnosticsPort.Stage.PARSE }.third.contains("Shape"))
    }

    @Test
    fun streamEmitsResolveAndDecryptStages() = runTest {
        val record = Recording()
        val repo = repository(record) { request ->
            when {
                request.url.contains("/novel/player/video_model/") -> videoModelBody()
                request.url.startsWith("https://example.com/") -> badSeedFallback()
                else -> throw IOException("未预期的请求：${request.url}")
            }
        }

        assertFailsWith<UpstreamError.Decrypt> { repo.stream("123", 720) }

        assertTrue(record.has(DiagnosticsPort.Stage.SIGN, ok = true))
        assertTrue(record.has(DiagnosticsPort.Stage.PARSE, ok = true))
        assertTrue(record.has(DiagnosticsPort.Stage.RESOLVE, ok = true))
        assertTrue(record.stages.any { it.first == DiagnosticsPort.Stage.DECRYPT && !it.second })
    }

    @Test
    fun resolveFailureIsReportedBeforeDecrypt() = runTest {
        val record = Recording()
        val repo = repository(record) { request ->
            when {
                request.url.contains("/novel/player/video_model/") -> videoModelBody()
                request.url.startsWith("https://example.com/") -> """{"video_info":{}}"""
                else -> throw IOException("未预期的请求：${request.url}")
            }
        }

        assertFailsWith<UpstreamError.Shape> { repo.stream("123", 720) }

        assertTrue(record.stages.any { it.first == DiagnosticsPort.Stage.RESOLVE && !it.second })
        assertFalse(record.stages.any { it.first == DiagnosticsPort.Stage.DECRYPT })
    }

    // ---------- CONNECT：OkHttpTransport 自己打 ----------

    @Test
    fun connectStageRecordsHostPathButNotQuery() = runBlocking {
        val record = Recording()
        val (port, close) = serveOnce("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
        try {
            val transport = OkHttpTransport(diagnostics = record)
            val text = transport.text(
                SignedRequest(HttpMethod.GET, "http://127.0.0.1:$port/reading?secret=1", emptyMap(), null),
            )
            assertEquals("ok", text)
            val stage = record.stages.single { it.first == DiagnosticsPort.Stage.CONNECT }
            assertTrue(stage.second)
            assertTrue(stage.third.contains("http://127.0.0.1:$port/reading"))
            assertFalse(stage.third.contains("secret"))
        } finally {
            close()
        }
    }

    @Test
    fun emptyBodyIsReportedAsFailedConnect() = runBlocking {
        val record = Recording()
        val (port, close) = serveOnce("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        try {
            val transport = OkHttpTransport(diagnostics = record)
            assertFailsWith<UpstreamError.EmptyBody> {
                transport.text(SignedRequest(HttpMethod.GET, "http://127.0.0.1:$port/empty", emptyMap(), null))
            }
            val stage = record.stages.single { it.first == DiagnosticsPort.Stage.CONNECT }
            assertFalse(stage.second)
            // 200 空体必须把状态码留在现场，否则分不清风控还是解析失败
            assertTrue(stage.third.contains("200 empty body"))
        } finally {
            close()
        }
    }

    /** 起一个只回一次的 HTTP 服务；返回端口与关闭函数。 */
    private fun serveOnce(rawResponse: String): Pair<Int, () -> Unit> {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(4096))
                    socket.getOutputStream().apply {
                        write(rawResponse.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            }
            runCatching { server.close() }
        }
        return server.localPort to {
            runCatching { server.close() }
            worker.join(2_000)
        }
    }

    // ---------- 样本 ----------

    private fun detailBody(): String = JSONObject()
        .put("code", 0)
        .put("data", JSONObject().put("video_data", JSONObject()
            .put("series_id", "7").put("series_title", "测试书第三季").put("episode_cnt", 2)
            .put("video_list", JSONArray(listOf(
                JSONObject().put("vid", "100"),
                JSONObject().put("vid", "200"),
            )))))
        .toString()

    private fun videoModelBody(): String = JSONObject()
        .put("code", 0)
        .put("data", JSONObject()
            .put("video_model", JSONObject().put("fallback_api", "https://example.com/stream")))
        .toString()

    /** key_seed 不是合法 base64：能走到 DECRYPT 段，并在那一段失败。 */
    private fun badSeedFallback(): String = JSONObject()
        .put("video_info", JSONObject().put("data", JSONObject()
            // key_seed 在 video_info.data 这一层（不在单个清晰度条目里）
            .put("key_seed", "!!!")
            .put("video_list", JSONObject().put("v0", JSONObject()
                .put("vheight", 720).put("codec_type", "h264")
                .put("main_url", "https://example.com/a.mp4")))))
        .toString()
}
