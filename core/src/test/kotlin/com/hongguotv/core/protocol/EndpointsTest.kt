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
        assertEquals(setOf("suggest", "seasonLine", "detail", "preload"), Endpoints.all.keys)
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
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1/",
            Endpoints["preload"].base,
        )
        assertEquals(
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_detail/v1/",
            Endpoints["detail"].base,
        )
    }

    @Test
    fun contentEndpointsShareTheDeviceProfile() {
        listOf("suggest", "seasonLine", "detail").forEach { id ->
            val endpoint = Endpoints[id]
            assertEquals("$id 用完整套件", SignerKind.FULL, endpoint.signer)
            assertEquals("$id 并入公共设备常量", QueryProfile.DEVICE, endpoint.queryProfile)
        }
    }

    @Test
    fun preloadKeepsItsOwnQueryAndNoSharedConstants() {
        val endpoint = Endpoints["preload"]
        assertEquals(SignerKind.PRELOAD, endpoint.signer)
        assertEquals(QueryProfile.NONE, endpoint.queryProfile)
        assertEquals("preload 的 query 是 20 个键", 20, endpoint.query.size)
        // 简化签名自己会追加 _rticket；表里再出现 ts / _rticket 会被重复拼进 query
        assertFalse("表里不该有 ts", endpoint.query.any { it.first == "ts" })
        assertFalse("表里不该有 _rticket", endpoint.query.any { it.first == "_rticket" })
        assertTrue(endpoint.query.contains("device_id" to "\$deviceId"))
        assertTrue(endpoint.query.contains("iid" to "\$installId"))
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

    /** path 带占位符现在不支持：宁可响亮失败，也不要拼出一个错的 URL。 */
    @Test
    fun pathPlaceholderIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Endpoints.load(tableOf(PATH_WITH_PLACEHOLDER)) }
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
