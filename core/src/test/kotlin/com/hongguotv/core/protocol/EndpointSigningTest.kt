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

    private val vid = "7100000000000000001"

    /** 旧实现 `apiValues(vararg pairs)` 的形状：公共设备常量在前，端点参数在后。 */
    private fun legacyContentValues(vararg pairs: Pair<String, String>) = LinkedHashMap<String, String>().apply {
        putAll(VendorConstants.device(DeviceIdentity.LEGACY))
        pairs.forEach { (key, value) -> put(key, value) }
    }

    /** 表形状测试沿用内置身份；生产路径已不允许缺省身份。 */
    private fun fullSuite() = FullSuiteSigner(crypto(), DeviceIdentity.LEGACY)

    @Test
    fun detailRequestMatchesDirectCall() {
        val endpoint = Endpoints["detail"]
        val values = mapOf("seriesId" to "123")

        val viaTable = fullSuite().sign(endpoint, values)
        val direct = crypto().sign(endpoint.base, legacyContentValues(), endpoint.resolvedBody(values))

        assertEquals(direct.url, viaTable.url)
        assertEquals(direct.headers, viaTable.headers)
        assertArrayEquals(direct.body!!, viaTable.body!!)
        assertEquals("detail 是 POST", HttpMethod.POST, viaTable.method)
    }

    @Test
    fun siteEndpointsAreEncodedByThePlainSigner() {
        val request = PlainSigner().sign(Endpoints["siteSearch"], mapOf("keyword" to "魏家 逆子", "page" to "1"))
        assertEquals(HttpMethod.GET, request.method)
        assertTrue("中文与空格必须编码：${request.url}", request.url.contains("query=%E9%AD%8F%E5%AE%B6%20%E9%80%86%E5%AD%90"))
        assertTrue(request.url.contains("page=1"))
    }

    @Test
    fun seasonLineRequestMatchesDirectCall() {
        val seriesId = "7123456789012345678"
        val viaTable = fullSuite().sign(Endpoints["seasonLine"], mapOf("seriesId" to seriesId))
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
    fun videoModelBodyUsesTheSingleVideoIdShape() {
        val body = String(Endpoints["videoModel"].resolvedBody(mapOf("vid" to vid))!!)
        assertEquals(
            """{"video_id":"$vid","biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}}""",
            body,
        )
        assertTrue("新路由只认 video_id 单值", !body.contains("mixed_video_id_map"))
    }

    @Test
    fun registryDispatchesByEndpointDeclaration() {
        val registry = SignerRegistry(listOf(fullSuite(), PlainSigner()))
        assertEquals(SignerKind.FULL, registry.forEndpoint("detail").kind)
        assertEquals(SignerKind.NONE, registry.forEndpoint("suggest").kind)
        assertEquals(SignerKind.FULL, registry.forEndpoint("seasonLine").kind)
        assertEquals(SignerKind.FULL, registry.forEndpoint("videoModel").kind)
    }

    @Test
    fun wrongSignerForEndpointIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PlainSigner().sign(Endpoints["detail"], mapOf("seriesId" to "1"))
        }
        assertTrue("报错必须点名端点：${error.message}", error.message.orEmpty().contains("detail"))
    }

    @Test
    fun unregisteredSignerKindIsRejected() {
        val registry = SignerRegistry(listOf(fullSuite()))
        val error = assertThrows(IllegalStateException::class.java) { registry.forEndpoint("siteSearch") }
        assertTrue(error.message.orEmpty().contains("none"))
    }
}
