package com.hongguotv.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整签名套件的**请求组装**测试。
 *
 * [SignerTest] 只覆盖四个底层原语（hash / gorgon / helios / medusa）的向量比对，
 * 不覆盖 `sign()` 本身的组装逻辑：128 次 `_rticket` 试探、头集合、body 指纹。
 * 这一层出一个错，搜索与详情就会全线 403，而底层原语测试依然全绿。
 */
class FullSuiteSigningTest {

    private val now = 1_800_500_000_000L
    private fun signer() = Signer({ now }) { 0.5 }

    @Test
    fun producesCompleteHeaderSetForBodyRequest() {
        val body = """{"series_id":"123"}""".toByteArray()
        val headers = signer().sign("https://example.com/api", mapOf("q" to "测试"), body).headers

        listOf("x-gorgon", "x-helios", "x-medusa", "x-ladon", "x-argus", "x-tt-dt").forEach {
            assertTrue("完整套件缺少 $it", headers.containsKey(it))
        }
        assertTrue("带 body 必须声明 Content-Type", headers.containsKey("Content-Type"))
        assertEquals("x-khronos 必须是秒", (now / 1000).toString(), headers["x-khronos"])
        assertEquals("x-ss-stub 必须是大写 MD5", digest("MD5", body).hex().uppercase(), headers["x-ss-stub"])
    }

    /** 只有 GET 才不带 body 相关头；带了反而会被拒。 */
    @Test
    fun omitsBodyHeadersForGetRequest() {
        val headers = signer().sign("https://example.com/api", mapOf("q" to "x"), null).headers
        assertFalse("无 body 不得带 x-ss-stub", headers.containsKey("x-ss-stub"))
        assertFalse("无 body 不得带 Content-Type", headers.containsKey("Content-Type"))
    }

    /**
     * ticket 循环是这套签名最反直觉的一处：`_rticket` 要一直试到 query 落在 hash branch 0。
     * 不满足时服务端直接拒绝，所以这里把它钉住。
     */
    @Test
    fun ticketLoopLandsOnBranchZero() {
        val body = """{"series_id":"123"}""".toByteArray()
        val request = signer().sign("https://example.com/api", mapOf("q" to "测试"), body)
        val query = request.url.substringAfter('?')

        assertEquals("签名分支必须为 0", 0, signer().branch(query, body, (now / 1000).toInt()))
        assertTrue("query 必须带 ts", query.split('&').any { it.startsWith("ts=") })
        assertTrue("query 必须带 _rticket", query.split('&').any { it.startsWith("_rticket=") })
    }

    /** 两套套件的 query 组成必须不同：这是风控区分入口的依据，不能互相污染。 */
    @Test
    fun fullSuiteAndPreloadSuitesStayDistinct() {
        val full = signer().sign("https://example.com/api", mapOf("q" to "x"), null)
        val preload = signer().signSimple("https://example.com/api", mapOf("q" to "x"), "{}".toByteArray())

        assertTrue(full.headers.containsKey("x-medusa"))
        assertFalse(preload.headers.containsKey("x-medusa"))
        assertFalse(full.headers.containsKey("x-ss-stub"))
        assertTrue(preload.headers.containsKey("x-ss-stub"))
    }
}
