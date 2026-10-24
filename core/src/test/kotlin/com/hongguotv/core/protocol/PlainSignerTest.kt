package com.hongguotv.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无签名通道：官网 SSR 请求的 URL 拼装 + "UA 也必须是注册档案那份"。 */
class PlainSignerTest {

    private fun endpoint(json: String): Endpoint =
        Endpoints.load("""{"hosts":{"h":"https://example.com"},"endpoints":{"p":$json}}""").endpoints.getValue("p")

    @Test
    fun buildsUrlWithEncodedQuery() {
        val endpoint = endpoint(
            """{"host":"h","path":"/list/${'$'}name","method":"GET","signer":"none","queryProfile":"none","query":[["page","1"],["q","${'$'}term"]]}"""
        )
        val signed = PlainSigner().sign(endpoint, mapOf("name" to urlEncode("凡人"), "term" to "a b"))
        assertEquals(HttpMethod.GET, signed.method)
        assertEquals("https://example.com/list/%E5%87%A1%E4%BA%BA?page=1&q=a%20b", signed.url)
        assertFalse(
            "无签名通道不带签名头：${signed.headers.keys}",
            signed.headers.keys.any { it.startsWith("x-") },
        )
        assertEquals("UA 与注册档案同源", DeviceProfile.DEFAULT.userAgent, signed.headers["User-Agent"])
        assertNull(signed.body)
    }

    @Test
    fun missingPathPlaceholderFailsLoudly() {
        val endpoint = endpoint(
            """{"host":"h","path":"/search/${'$'}keyword","method":"GET","signer":"none","queryProfile":"none","query":[]}"""
        )
        val error = assertThrows(IllegalArgumentException::class.java) { PlainSigner().sign(endpoint, emptyMap()) }
        assertTrue(error.message.orEmpty().contains("keyword"))
    }

    @Test
    fun refusesEndpointsDeclaredForAnotherSigner() {
        val endpoint = endpoint(
            """{"host":"h","path":"/p","method":"GET","signer":"full","queryProfile":"device","query":[]}"""
        )
        val error = assertThrows(IllegalArgumentException::class.java) { PlainSigner().sign(endpoint, emptyMap()) }
        assertTrue(error.message.orEmpty().contains("full"))
    }

    @Test
    fun registryShipsWithTheUnsignedChannel() {
        assertEquals(SignerKind.NONE, SignerRegistry().forKind(SignerKind.NONE).kind)
    }
}
