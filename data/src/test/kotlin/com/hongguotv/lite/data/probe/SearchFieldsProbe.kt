package com.hongguotv.lite.data.probe

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.lite.data.content.ContentRepository
import com.hongguotv.lite.data.http.OkHttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 搜索字段探针（2026-10-21）：确认分类是否每条都有、季线卡片是否带分类。
 *
 * 默认跳过，`HGTV_PROBE=1` 时跑。只打印卡片摘要，不打印请求体。
 */
class SearchFieldsProbe {

    @Test
    fun probe() {
        assumeTrue("设置 HGTV_PROBE=1 才跑真网探针", System.getenv("HGTV_PROBE") == "1")
        runBlocking {
            val content = ContentRepository(http = OkHttpTransport(diagnostics = Quiet))
            val keywords = System.getenv("KEYWORDS")?.split('|')
                ?: listOf("十八岁的二次回响", "魏家逆子成首辅", "万妖图录传")
            keywords.forEach { keyword ->
                val result = runCatching { content.search(keyword, ContentFilter.ALL) }.getOrElse {
                    println("[$keyword] 失败 ${it::class.java.simpleName}: ${it.message}")
                    return@forEach
                }
                println("[$keyword] items=${result.items.size} seasons=${result.seasonLine.size}")
                result.items.take(6).forEach { series ->
                    println("    ${series.id} | ${series.title} | tags='${series.tags}' | ${series.badge} | season=${series.season} | released=${series.released}")
                }
            }
        }
    }

    private object Quiet : DiagnosticsPort {
        override fun stage(stage: DiagnosticsPort.Stage, ok: Boolean, detail: String) = Unit
        override fun event(name: String, fields: Map<String, Any?>) = Unit
    }
}
