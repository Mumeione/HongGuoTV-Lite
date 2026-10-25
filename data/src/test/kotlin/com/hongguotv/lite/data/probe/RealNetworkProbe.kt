package com.hongguotv.lite.data.probe

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.StreamInfo
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.MediaStream
import com.hongguotv.lite.data.content.ContentRepository
import com.hongguotv.lite.data.content.DeviceSession
import com.hongguotv.lite.data.http.OkHttpTransport
import com.hongguotv.lite.data.library.KeyValueStore
import com.hongguotv.lite.data.media.HttpRangeReader
import com.hongguotv.lite.data.media.StreamTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真网探针：用生产链路（ContentRepository + OkHttpTransport）打一次上游。
 *
 * **默认跳过**，只在需要确认"上游今天还活着、字段没变"时跑：
 *
 * ```sh
 * $env:HGTV_PROBE = "1"
 * ./gradlew :data:testDebugUnitTest --tests '*RealNetworkProbe*' --info --console=plain
 * ```
 *
 * 它不属于回归测试：网络、上游风控、时段都会让结果变化，不能进 CI。
 * 探针的目的不是断言"一定有结果"，而是把五段里**断在哪一段**打出来。
 */
class RealNetworkProbe {

    @Test
    fun probe() {
        assumeTrue("设置 HGTV_PROBE=1 才跑真网探针", System.getenv("HGTV_PROBE") == "1")
        runBlocking {
            val diagnostics = PrintingDiagnostics()
            val transport = OkHttpTransport(diagnostics = diagnostics)
            // 与生产同一条路：先匿名注册一次（内置那对已被拉黑），之后所有请求都用这一对身份。
            // 探针的 store 只在内存里：每次跑探针注册一台新设备，不污染真机上的注册结果。
            val devices = DeviceSession(MemoryStore(), transport, diagnostics)
            devices.ensure()
            val content = ContentRepository(http = transport, devices = devices, diagnostics = diagnostics)
            val report = ProbeReport()

            // 1) 搜索：官网 /api/search/result（无签名、服务端分页、一页 10 条）
            val keyword = "万妖图录传"
            val found = report.step<SearchResult>("SEARCH", keyword) { content.search(keyword, ContentFilter.ALL) }
            found?.let { result ->
                report.line(
                    "  page=${result.page}/${result.totalPages} total=${result.totalCount} " +
                        "items=${result.items.size}",
                )
                result.items.forEach { report.line("  - ${it.id} ${it.title}") }
            }
            report.step<SearchResult>("SEARCH#2", keyword) { content.search(keyword, ContentFilter.ALL, page = 2) }
                ?.let { second -> second.items.forEach { report.line("  - p2 ${it.id} ${it.title}") } }

            // 搜索首条可能是未上线季（episode_cnt=0，detail 没有 video_list）。
            // 页面会拦住这类卡片（SearchPage.openResult），探针照同样的口径只点已上线的。
            val candidates = found?.items?.filter { it.released }?.take(5) ?: emptyList()
            if (candidates.isEmpty()) return@runBlocking
            var detail: Detail? = null
            for (candidate in candidates) {
                report.line("anchor=${candidate.id} ${candidate.title}")
                detail = report.step<Detail>("DETAIL", candidate.id) { content.detail(candidate.id) }
                if (detail != null) break
            }
            detail?.let {
                report.line("  title=${it.series.title} episodes=${it.episodes.size}")
                it.episodes.take(3).forEach { e -> report.line("  - #${e.index} ${e.vid}") }
            }

            // 3) 季线：plan 接口
            val anchor = detail?.series ?: candidates.first()
            val line = report.step<SeasonLine>("SEASONS", anchor.id) { content.seasons(anchor.id, anchor) }
            line?.let {
                report.line("  seasons=${it.seasons.size}")
                it.seasons.take(5).forEach { s -> report.line("  - ${s.id} ${s.title}") }
            }

            // 4) 取流：video_model（video_id 单值）→ fallback → 解密
            //    身份被拉黑时回 200 空体；这里用匿名身份，重试几次排除偶发网络抖动。
            val vid = detail?.episodes?.firstOrNull()?.vid ?: return@runBlocking
            var stream: StreamInfo? = null
            for (attempt in 1..4) {
                stream = report.step<StreamInfo>("STREAM#$attempt", vid) { content.stream(vid, maxHeight = 1080) }
                if (stream != null) break
                kotlinx.coroutines.delay(200L * attempt)
            }
            stream?.let { streamInfo ->
                report.line("  height=${streamInfo.height} fellBack=${streamInfo.fellBack} key=${streamInfo.key != null}")
                report.line("  host=${streamInfo.url.substringAfter("://").substringBefore('/')}")

                // 再走一遍 MediaPort：这一步才会真正解析 CENC 索引（DECRYPT 的 stream.header/index）
                val fixed = object : ContentPort by content {
                    override suspend fun stream(vid: String, maxHeight: Int): StreamInfo = streamInfo
                }
                val media = StreamTransport(
                    content = fixed,
                    readerFor = { url -> HttpRangeReader(OkHttpTransport.defaultClient(), url) },
                    diagnostics = diagnostics,
                )
                val opened = report.step<MediaStream>("MEDIA", vid) { media.open(vid, 1080) }
                opened?.let { s ->
                    val head = report.step<ByteArray>("MEDIA.READ", "0..1023") { s.read(0, 1024) }
                    report.line("  length=${s.length} read=${head?.size}")
                    s.close()
                }
            }

            // 5) 联想：输入辅助，允许为空
            val suggestions = report.step<List<Suggestion>>("SUGGEST", "庆") { content.suggest("庆") }
            report.line("  count=${suggestions?.size}")

            report.print()
        }
    }

    /** 探针用的内存键值库：注册结果不落盘，跑一次算一次。 */
    private class MemoryStore : KeyValueStore {
        private val values = LinkedHashMap<String, Any?>()
        override fun all(): Map<String, Any?> = LinkedHashMap(values)
        override fun commit(batch: Map<String, Any?>) {
            batch.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }

    /** 把五段探针打到 stdout，真网跑一次就能看到断在哪一段。 */
    private class PrintingDiagnostics : DiagnosticsPort {
        override fun stage(stage: DiagnosticsPort.Stage, ok: Boolean, detail: String) {
            println("    [${stage.name}] ${if (ok) "ok" else "FAIL"} $detail")
        }

        override fun event(name: String, fields: Map<String, Any?>) {
            println("    [EVENT] $name ${fields.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
        }
    }

    private class ProbeReport {
        private val lines = mutableListOf<String>()

        fun line(text: String) {
            lines += text
            println(text)
        }

        suspend fun <T> step(name: String, input: String, block: suspend () -> T): T? {
            val started = System.nanoTime()
            return try {
                val value = block()
                val ms = (System.nanoTime() - started) / 1_000_000
                line("[$name] ok ${ms}ms ($input)")
                value
            } catch (e: Throwable) {
                val ms = (System.nanoTime() - started) / 1_000_000
                line("[$name] FAIL ${ms}ms ($input): ${e::class.java.simpleName} ${e.message}")
                null
            }
        }

        fun print() {
            println("──────── 探针汇总 ────────")
            lines.forEach(::println)
        }
    }
}
