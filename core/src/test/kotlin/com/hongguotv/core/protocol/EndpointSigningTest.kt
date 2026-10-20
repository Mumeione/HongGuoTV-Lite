package com.hongguotv.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表驱动出来的请求，必须与旧实现里内联拼出来的**完全一致**。
 *
 * 这是 S1 的验收线：把协议形状挪进表，不能顺手改了形状。任何一处顺序、编码、
 * 常量的漂移都会让签名对不上，而上游只回一个 200 空体。
 *
 * 四个端点都要比：只有 detail（query 为空）不足以证明"设备常量 + 端点参数"的拼接顺序，
 * 而顺序正是签名覆盖的东西。
 */
class EndpointSigningTest {

    private val now = 1_800_500_000_000L

    private fun crypto() = Signer({ now }) { 0.5 }

    private val deviceId = "712345678901234567"
    private val installId = "812345678901234567"
    private val vid = "7100000000000000001"

    /** 旧实现 `stream()` 里的 query 字面量。顺序即协议。 */
    private val legacyPreloadQuery = listOf(
        "aid" to "8662", "app_name" to "novelread",
        "version_code" to "73532", "version_name" to "7.3.5.32",
        "manifest_version_code" to "73532", "update_version_code" to "73532",
        "channel" to "update_64", "device_platform" to "android", "os" to "android",
        "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi",
        "language" to "zh", "os_api" to "36", "os_version" to "16",
        "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
        "device_id" to deviceId, "iid" to installId,
    )

    /** 旧实现 `apiValues(vararg pairs)` 的形状：公共设备常量在前，端点参数在后。 */
    private fun legacyContentValues(vararg pairs: Pair<String, String>) = LinkedHashMap<String, String>().apply {
        putAll(VendorConstants.device)
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun preloadQueryMatchesLegacyShapeExactly() {
        val resolved = Endpoints["preload"].resolvedQuery(
            mapOf("deviceId" to deviceId, "installId" to installId),
        )
        assertEquals(legacyPreloadQuery, resolved)
    }

    @Test
    fun preloadRequestMatchesDirectCall() {
        val endpoint = Endpoints["preload"]
        val values = mapOf("deviceId" to deviceId, "installId" to installId, "vid" to vid)

        val viaTable = PreloadSigner(crypto()).sign(endpoint, values)
        val direct = crypto().signSimple(
            endpoint.base,
            LinkedHashMap<String, String>().apply { legacyPreloadQuery.forEach { (k, v) -> put(k, v) } },
            endpoint.resolvedBody(values)!!,
        )

        assertEquals(direct.url, viaTable.url)
        assertEquals(direct.headers, viaTable.headers)
        assertArrayEquals(direct.body!!, viaTable.body!!)
        assertEquals("preload 是 POST", HttpMethod.POST, viaTable.method)
    }

    @Test
    fun detailRequestMatchesDirectCall() {
        val endpoint = Endpoints["detail"]
        val values = mapOf("seriesId" to "123")

        val viaTable = FullSuiteSigner(crypto()).sign(endpoint, values)
        val direct = crypto().sign(endpoint.base, legacyContentValues(), endpoint.resolvedBody(values))

        assertEquals(direct.url, viaTable.url)
        assertEquals(direct.headers, viaTable.headers)
        assertArrayEquals(direct.body!!, viaTable.body!!)
        assertEquals("detail 是 POST", HttpMethod.POST, viaTable.method)
    }

    /** 有 query 的端点才验得出"设备常量 + 端点参数"的拼接顺序。 */
    @Test
    fun suggestRequestMatchesDirectCall() {
        val keyword = "魏家逆子成首辅"
        val viaTable = FullSuiteSigner(crypto()).sign(Endpoints["suggest"], mapOf("keyword" to keyword))
        val direct = crypto().sign(
            Endpoints["suggest"].base,
            legacyContentValues(
                "q" to keyword, "search_source" to "1", "tab_name" to "feed",
                "count" to "0", "need_preload" to "true",
            ),
            null,
        )
        assertEquals(direct.url, viaTable.url)
        assertEquals(HttpMethod.GET, viaTable.method)
    }

    @Test
    fun seasonLineRequestMatchesDirectCall() {
        val seriesId = "7123456789012345678"
        val viaTable = FullSuiteSigner(crypto()).sign(Endpoints["seasonLine"], mapOf("seriesId" to seriesId))
        val direct = crypto().sign(
            Endpoints["seasonLine"].base,
            legacyContentValues(
                "scene" to "10", "from" to "detail_page_more_related",
                "book_id" to seriesId, "limit" to "0", "offset" to "0",
            ),
            null,
        )
        assertEquals(direct.url, viaTable.url)
    }

    /** 详情 body 与旧实现的内联 JSON 逐字一致——这是最容易在搬运时抄错的一处。 */
    @Test
    fun detailBodyMatchesLegacyJson() {
        val body = String(Endpoints["detail"].resolvedBody(mapOf("seriesId" to "123"))!!)
        assertEquals(
            """{"biz_param":{"detail_page_version":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":false,"need_mp4_align":false,"screen_width_px":"1920","source":5,"use_os_player":false,"use_server_dns":false,"video_id_type":1},"series_id":"123"}""",
            body,
        )
    }

    @Test
    fun preloadBodyMatchesLegacyJson() {
        val body = String(Endpoints["preload"].resolvedBody(
            mapOf("deviceId" to deviceId, "installId" to installId, "vid" to vid),
        )!!)
        assertEquals(
            """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$vid"]}}""",
            body,
        )
    }

    @Test
    fun preloadRequestStaysOnTheSimplifiedSuite() {
        val request = PreloadSigner(crypto()).sign(
            Endpoints["preload"],
            mapOf("deviceId" to deviceId, "installId" to installId, "vid" to vid),
        )
        listOf("x-ladon", "x-argus", "x-helios", "x-medusa").forEach {
            assertFalse("preload 请求不得带 $it", request.headers.containsKey(it))
        }
        assertTrue("preload 请求必须带 x-gorgon", request.headers.containsKey("x-gorgon"))
        assertTrue("query 不得含 ts", !request.url.substringAfter('?').split('&').any { it.startsWith("ts=") })
    }

    @Test
    fun registryDispatchesByEndpointDeclaration() {
        val registry = SignerRegistry()
        assertEquals(SignerKind.PRELOAD, registry.forEndpoint("preload").kind)
        assertEquals(SignerKind.FULL, registry.forEndpoint("detail").kind)
        assertEquals(SignerKind.FULL, registry.forEndpoint("suggest").kind)
        assertEquals(SignerKind.FULL, registry.forEndpoint("seasonLine").kind)
    }

    @Test
    fun wrongSignerForEndpointIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PreloadSigner(crypto()).sign(Endpoints["detail"], mapOf("seriesId" to "1"))
        }
        assertTrue("报错必须点名端点：${error.message}", error.message.orEmpty().contains("detail"))
    }

    @Test
    fun unregisteredSignerKindIsRejected() {
        val registry = SignerRegistry(listOf(FullSuiteSigner(crypto())))
        val error = assertThrows(IllegalStateException::class.java) { registry.forEndpoint("preload") }
        assertTrue(error.message.orEmpty().contains("preload"))
    }
}
