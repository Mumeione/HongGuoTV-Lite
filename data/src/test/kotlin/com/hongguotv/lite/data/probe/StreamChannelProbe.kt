package com.hongguotv.lite.data.probe

import com.hongguotv.core.protocol.Endpoint
import com.hongguotv.core.protocol.FullSuiteSigner
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.QueryProfile
import com.hongguotv.core.protocol.SignerKind
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 取流通道诊断（2026-10-21）：判断"preload 全空体"是端点下线还是设备被风控。
 *
 * 默认跳过，`HGTV_PROBE=1` 时跑。只打印状态码/长度/前 200 字节，不打印签名头。
 */
class StreamChannelProbe {

    @Test
    fun probe() {
        assumeTrue("设置 HGTV_PROBE=1 才跑真网探针", System.getenv("HGTV_PROBE") == "1")
        runBlocking {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val full = FullSuiteSigner()

            val seriesId = System.getenv("SERIES_ID") ?: "7690163276214176792"
            val vid = System.getenv("VID") ?: "7690223053078268953"
            val a = "https://api5-normal-sinfonlinea.fqnovel.com"
            val b = "https://api5-normal-sinfonlineb.fqnovel.com"
            val preloadBody = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$vid"]}}"""

            fun post(name: String, base: String, body: String) {
                val endpoint = Endpoint(
                    id = name,
                    base = base,
                    method = HttpMethod.POST,
                    signer = SignerKind.FULL,
                    queryProfile = QueryProfile.DEVICE,
                    query = emptyList(),
                    bodyTemplate = body,
                )
                val signed = full.sign(endpoint, emptyMap())
                val builder = Request.Builder().url(signed.url)
                    .post(signed.body!!.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                signed.headers.forEach { (key, value) -> builder.header(key, value) }
                val started = System.nanoTime()
                try {
                    client.newCall(builder.build()).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        val ms = (System.nanoTime() - started) / 1_000_000
                        println("[$name] HTTP ${response.code} ${text.length}B ${ms}ms")
                        if (text.isNotEmpty()) println("    ${text.take(300)}")
                        if (response.code == 200 && text.isEmpty()) println("    >>> 200 空体")
                    }
                } catch (e: Throwable) {
                    println("[$name] 异常 ${e::class.java.simpleName}: ${e.message}")
                }
            }

            // 对照：详情接口（已知可用）
            post("detail@A", "$a/novel/player/video_detail/v1/", """{"biz_param":{"detail_page_version":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":false,"need_mp4_align":false,"screen_width_px":"1920","source":5,"use_os_player":false,"use_server_dns":false,"video_id_type":1},"series_id":"$seriesId"}""")
            // 对照：详情接口打 B host（判断 b 域名是否整体下线）
            post("detail@B", "$b/novel/player/video_detail/v1/", """{"biz_param":{"detail_page_version":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":false,"need_mp4_align":false,"screen_width_px":"1920","source":5,"use_os_player":false,"use_server_dns":false,"video_id_type":1},"series_id":"$seriesId"}""")

            // 当前实现（完整签名版；Lite 生产用的是简化签名，两者都在退化）
            post("model-preload@B", "$b/novel/player/multi_video_model/preload/v1/", preloadBody)
            post("model-preload@A", "$a/novel/player/multi_video_model/preload/v1/", preloadBody)
            // 老入口
            post("video-model@B", "$b/novel/player/multi_video_model/v1/", preloadBody)
            // 官方 7.3.9.32 抓包里的入口（按 series_id）
            post("detail-preload@A", "$a/novel/player/multi_video_detail/preload/v1/", """{"dr_scene":"preload","series_id":"$seriesId"}""")
            post("detail-preload@B", "$b/novel/player/multi_video_detail/preload/v1/", """{"dr_scene":"preload","series_id":"$seriesId"}""")

            // ---- 真正的播放地址接口（research/第三轮抓包：记录 448 是 player/v1） ----
            val playerBody = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":true,"use_server_dns_scene":"cold_start_stage","video_platform":1024},"dr_scene":"player","mixed_video_id_map":{"1004":["$vid"]}}"""
            post("player@A", "$a/novel/player/multi_video_model/player/v1/", playerBody)
            post("player@B", "$b/novel/player/multi_video_model/player/v1/", playerBody)
            // 预取接口 + 官方 body 形态（use_server_dns=true）——排除 body 参数差异
            val preloadOfficial = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":true,"use_server_dns_scene":"cold_start_stage","video_platform":1024},"dr_scene":"preload","mixed_video_id_map":{"1004":["$vid"]}}"""
            post("preload-official@A", "$a/novel/player/multi_video_model/preload/v1/", preloadOfficial)
        }
    }
}
