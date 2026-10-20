package com.hongguotv.core.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * preload 取流这一条路径的守门测试。
 *
 * 为什么单独一个类：这是 2026-10 那次「所有剧都不能播」事故的主角。它用的是简化签名套件
 * （x-gorgon 0x40 0x1c 变体 + 精简 query），与完整套件完全不同——多带一个签名头、
 * query 里多一个 ts，都会变成 HTTP 200 + 空体，看起来像「这部剧没有剧集」。
 *
 * 断言方式是**端到端**的：给定固定时钟，直接检查请求 URL 与各头的**值**，
 * 与参考实现（drpys，GPL-3.0）产出的向量逐字比对。这样即使某天有人把 signSimple
 * 错接成完整套件的 gorgon，也会立刻变红。
 */
class PreloadSigningTest {

    @Test
    fun matchesReferenceVectorsEndToEnd() {
        val rows = JSONObject(resource("/preload-signing.json")).getJSONArray("rows")
        assertTrue("向量组数过少，覆盖不足", rows.length() >= 8)

        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val ticket = row.getLong("ticket")
            val base = row.getString("base")
            val body = row.getString("body").toByteArray()

            // 按向量里的顺序还原参数表：signSimple 用 LinkedHashMap，顺序决定 query
            val pairs = row.getJSONArray("values")
            val values = LinkedHashMap<String, String>(pairs.length())
            for (j in 0 until pairs.length()) {
                val pair = pairs.getJSONArray(j)
                values[pair.getString(0)] = pair.getString(1)
            }

            val signer = Signer({ ticket }) { 0.5 }
            val request = signer.signSimple(base, values, body)

            assertEquals("row $i url", "$base?${row.getString("query")}", request.url)
            assertEquals("row $i x-gorgon", row.getString("gorgon"), request.headers["x-gorgon"])
            assertEquals("row $i x-ss-stub", row.getString("stub"), request.headers["x-ss-stub"])
            assertEquals("row $i x-khronos", row.getInt("ts").toString(), request.headers["x-khronos"])
            assertEquals("row $i x-ss-req-ticket", ticket.toString(), request.headers["x-ss-req-ticket"])
        }
    }

    /** 简化套件只允许这九个头；少一个上游不认，多一个就判风控。 */
    @Test
    fun emitsExactlyTheSimplifiedHeaderSet() {
        val request = Signer({ 1_800_500_000_000L }) { 0.5 }
            .signSimple("https://example.com/preload", mapOf("aid" to "8662"), "{}".toByteArray())

        assertEquals(
            setOf(
                "User-Agent", "Accept", "x-xs-from-web", "sdk-version", "x-khronos",
                "x-gorgon", "x-ss-req-ticket", "Content-Type", "x-ss-stub",
            ),
            request.headers.keys,
        )
        listOf("x-ladon", "x-argus", "x-helios", "x-medusa", "x-tt-dt").forEach {
            assertFalse("简化套件不得包含 $it", request.headers.containsKey(it))
        }
    }

    /** query 组成是风控判据的一部分：简化套件只有 _rticket，没有 ts。 */
    @Test
    fun simplifiedQueryCarriesTicketButNoTimestamp() {
        val request = Signer({ 1_800_500_000_000L }) { 0.5 }
            .signSimple("https://example.com/preload", mapOf("aid" to "8662"), "{}".toByteArray())

        val query = request.url.substringAfter('?')
        assertEquals("aid=8662&_rticket=1800500000000", query)
        assertFalse("简化 query 不得含 ts", query.split('&').any { it.startsWith("ts=") })
    }

    private fun resource(name: String): String =
        javaClass.getResource(name)?.readText()
            ?: error("缺少测试资源 $name；用 tools/diag/signing-oracle.cjs 重新生成，见 tools/diag/README.md")
}
