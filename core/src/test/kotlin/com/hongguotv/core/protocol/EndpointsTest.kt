package com.hongguotv.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端点表的结构约束。
 *
 * 这些断言的价值不在"测了解析器"，而在：表是可以手改的数据文件，改错一个字段在编译期
 * 完全看不出来，只会在实机上变成 200 空体。所以把"表必须满足的形状"钉在这里。
 */
class EndpointsTest {

    @Test
    fun bundledTableLoads() {
        assertEquals(setOf("suggest", "seasonLine", "detail", "siteSearch", "videoModel"), Endpoints.all.keys)
        assertEquals(setOf("content", "stream", "site"), Endpoints.hosts.keys)
    }

    @Test
    fun everyEndpointIsWellFormed() {
        Endpoints.all.values.forEach { endpoint ->
            assertTrue("${endpoint.id} 的 base 必须是绝对地址", endpoint.base.startsWith("https://"))
            assertTrue("${endpoint.id} 的 base 不该带 query", !endpoint.base.contains('?'))
            when (endpoint.method) {
                HttpMethod.POST -> assertNotNull("${endpoint.id} 是 POST，必须带 body 模板", endpoint.bodyTemplate)
                HttpMethod.GET -> assertNull("${endpoint.id} 是 GET，不该带 body 模板", endpoint.bodyTemplate)
            }
        }
    }

    /** base 由 host + path 拼好，调用方不必再持有 hosts 表。 */
    @Test
    fun baseIsResolvedFromHostAndPath() {
        assertEquals(
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/",
            Endpoints["videoModel"].base,
        )
        assertEquals(
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_detail/v1/",
            Endpoints["detail"].base,
        )
    }

    @Test
    fun contentEndpointsShareTheDeviceProfile() {
        listOf("seasonLine", "detail", "videoModel").forEach { id ->
            val endpoint = Endpoints[id]
            assertEquals("$id 用完整套件", SignerKind.FULL, endpoint.signer)
            assertEquals("$id 并入公共设备常量", QueryProfile.DEVICE, endpoint.queryProfile)
        }
    }

    @Test
    fun siteEndpointsAreUnsignedAndCarryTheirOwnQuery() {
        listOf("suggest", "siteSearch").forEach { id ->
            val endpoint = Endpoints[id]
            assertEquals("$id 走无签名通道", SignerKind.NONE, endpoint.signer)
            assertEquals("$id 不并入设备常量", QueryProfile.NONE, endpoint.queryProfile)
            assertFalse("$id 是官网接口，query 里不该有 ts", endpoint.query.any { it.first == "ts" })
        }
        assertTrue(Endpoints["suggest"].query.contains("app_id" to "8662"))
        assertTrue(Endpoints["siteSearch"].query.contains("page" to "\$page"))
    }

    @Test
    fun seasonLinePointsAtTheRelatedWorksCell() {
        val endpoint = Endpoints["seasonLine"]
        assertTrue(endpoint.base.contains("plan/v:version"))
        assertTrue(endpoint.query.contains("from" to "detail_page_more_related"))
        assertTrue(endpoint.query.contains("book_id" to "\$seriesId"))
    }

    @Test
    fun missingPlaceholderFailsLoudly() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Endpoints["detail"].resolvedBody(emptyMap())
        }
        assertTrue("报错必须点名缺哪个参数：${error.message}", error.message.orEmpty().contains("seriesId"))
    }

    @Test
    fun postWithoutBodyIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) { Endpoints.load(tableOf(POST_WITHOUT_BODY)) }
        assertTrue(error.message.orEmpty().contains("body"))
    }

    @Test
    fun unknownSignerIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) { Endpoints.load(tableOf(UNKNOWN_SIGNER)) }
        assertTrue(error.message.orEmpty().contains("nope"))
    }

    @Test
    fun pathWithoutLeadingSlashIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Endpoints.load(tableOf(RELATIVE_PATH)) }
    }

    /** path 占位符：表里允许写，最终地址由调用方填充（自建表仍可声明）。 */
    @Test
    fun pathPlaceholderIsFilledAtRequestTime() {
        val table = Endpoints.load(tableOf(PATH_WITH_PLACEHOLDER))
        val endpoint = table.endpoints.getValue("bad")
        assertTrue("base 仍是模板：${endpoint.base}", endpoint.base.endsWith("/search/\$keyword"))
        // url() 只填占位符不编码：编码是调用方的责任（统一走 urlEncode）
        assertEquals("https://example.com/search/%E5%87%A1%E4%BA%BA", endpoint.url(mapOf("keyword" to urlEncode("凡人"))))
        val error = assertThrows(IllegalArgumentException::class.java) { endpoint.url(emptyMap()) }
        assertTrue("报错必须点名缺哪个参数：${error.message}", error.message.orEmpty().contains("keyword"))
    }

    /** 官网搜索端点：无签名通道，query 带 keyword + page。 */
    @Test
    fun siteSearchGoesThroughTheUnsignedChannel() {
        val endpoint = Endpoints["siteSearch"]
        assertEquals(SignerKind.NONE, endpoint.signer)
        assertEquals(QueryProfile.NONE, endpoint.queryProfile)
        assertEquals(HttpMethod.GET, endpoint.method)
        assertTrue(endpoint.base.startsWith("https://hongguoduanju.com/api/search/result"))
        // keyword / page 都是 query 占位符：编码由 PlainSigner 统一做，调用方传原文
        assertEquals(listOf("aid" to "8662", "query" to "凡人", "page" to "2"), endpoint.resolvedQuery(mapOf(
            "keyword" to "凡人",
            "page" to "2",
        )))
    }

    @Test
    fun unknownHostIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) { Endpoints.load(tableOf(UNKNOWN_HOST)) }
        assertTrue(error.message.orEmpty().contains("nope"))
    }

    private fun tableOf(endpoint: String): String =
        """{"hosts":{"h":"https://example.com"},"endpoints":{"bad":$endpoint}}"""

    private companion object {
        const val POST_WITHOUT_BODY =
            """{"host":"h","path":"/p","method":"POST","signer":"full","queryProfile":"device","query":[]}"""
        const val UNKNOWN_SIGNER =
            """{"host":"h","path":"/p","method":"GET","signer":"nope","queryProfile":"device","query":[]}"""
        const val RELATIVE_PATH =
            """{"host":"h","path":"p","method":"GET","signer":"full","queryProfile":"device","query":[]}"""

        // 原始字符串里反斜杠是字面量，所以用模板插入 $ 本身
        val PATH_WITH_PLACEHOLDER =
            """{"host":"h","path":"/search/${'$'}keyword","method":"GET","signer":"full","queryProfile":"device","query":[]}"""

        const val UNKNOWN_HOST =
            """{"host":"nope","path":"/p","method":"GET","signer":"full","queryProfile":"device","query":[]}"""
    }
}
