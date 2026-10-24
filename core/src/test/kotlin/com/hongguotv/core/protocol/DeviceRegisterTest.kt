package com.hongguotv.core.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** 设备注册：请求体字段、加密形状、响应解析。全是纯 JVM（不依赖 Android）。 */
class DeviceRegisterTest {

    private val salt = hex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58",
    )

    /** 按协议把 ttEncrypt 拆开：magic[6] + rnd[32] + AES-CBC(明文)。 */
    private fun decryptTtEncrypt(body: ByteArray): ByteArray {
        assertEquals(0x74, body[0].toInt() and 0xff)
        val rnd = body.copyOfRange(6, 38)
        val derived = MessageDigest.getInstance("SHA-512").digest(
            MessageDigest.getInstance("SHA-512").digest(rnd) + salt,
        )
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derived.copyOfRange(0, 16), "AES"),
                IvParameterSpec(derived.copyOfRange(16, 32)),
            )
        }
        val padded = cipher.doFinal(body.copyOfRange(38, body.size))
        val padding = padded.last().toInt() and 0xff
        return padded.copyOfRange(0, padded.size - padding)
    }

    private fun gunzip(data: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(data)).use { String(it.readBytes(), Charsets.UTF_8) }

    /** payload = ttEncrypt(SHA512(gzip) || gzip)。 */
    private fun payloadJson(body: ByteArray): String {
        val plain = decryptTtEncrypt(body)
        val hash = plain.copyOfRange(0, 64)
        val gzip = plain.copyOfRange(64, plain.size)
        assertTrue("前缀必须是 gzip 数据的 SHA-512", MessageDigest.getInstance("SHA-512").digest(gzip).contentEquals(hash))
        return gunzip(gzip)
    }

    @Test
    fun `payload is a binary ttEncrypt of gzipped json`() {
        val body = DeviceRegister.payload(
            profile = DeviceProfile.DEFAULT,
            now = 1_700_000_000_000L,
            randomId = { "fixed-uuid" },
            random = Random(7),
        )
        val json = JSONObject(payloadJson(body))
        assertEquals("ss_app_log", json.getString("magic_tag"))
        assertEquals(1_700_000_000_000L, json.getLong("_gen_time"))
        // 首注册不带身份：由服务端下发
        val header = json.getJSONObject("header")
        assertFalse("不能自带 device_id", header.has("device_id"))
        assertFalse("不能自带 install_id", header.has("install_id"))
    }

    @Test
    fun `header describes a wifi tablet with a matching api level`() {
        val header = JSONObject(DeviceRegister.bodyJson(DeviceProfile.DEFAULT, now = 1L)).getJSONObject("header")
        assertEquals("SM-T580", header.getString("device_model"))
        assertEquals("samsung", header.getString("device_brand"))
        // 必须是字符串：写成数字服务端直接回 device_id=0
        assertTrue(header.get("os_version") is String)
        assertEquals("8.1.0", header.getString("os_version"))
        assertEquals(27, header.getInt("os_api"))
        assertEquals("wifi", header.getString("access"))
        // 电视/平板没有蜂窝信息
        assertFalse(header.has("carrier"))
        assertFalse(header.has("mcc_mnc"))
        assertFalse(header.has("sim_region"))
        assertEquals(0, header.getJSONArray("sim_serial_number").length())
        assertEquals("pad", header.getJSONObject("custom").getString("dragon_device_type"))
    }

    @Test
    fun `headers carry the body md5 and no signature headers`() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val headers = DeviceRegister.headers(body, DeviceProfile.DEFAULT, now = 99L)
        assertEquals("99", headers["x-ss-req-ticket"])
        assertEquals("8662", headers["x-ss-dp"])
        assertEquals(
            "body 的 MD5 大写",
            MessageDigest.getInstance("MD5").digest(body).hex().uppercase(),
            headers["x-ss-stub"],
        )
        // 注册请求不能带签名头：带了会被判异常客户端
        listOf("x-gorgon", "x-khronos", "x-ladon", "x-argus", "x-helios", "x-medusa").forEach {
            assertFalse("不该出现 $it", headers.containsKey(it))
        }
    }

    @Test
    fun `parse accepts both numeric and string ids`() {
        val numeric = DeviceRegister.parse("""{"device_id":4175314086266699,"install_id":4175314086270795,"new_user":1}""")
        assertEquals("4175314086266699", numeric?.deviceId)
        assertEquals("4175314086270795", numeric?.installId)
        assertEquals(true, numeric?.newUser)

        val string = DeviceRegister.parse("""{"device_id_str":"700000000000000001","install_id_str":"800000000000000002"}""")
        assertEquals("700000000000000001", string?.deviceId)
        assertEquals("800000000000000002", string?.installId)
    }

    @Test
    fun `parse treats zero ids as a rejected registration`() {
        assertNull(DeviceRegister.parse("""{"device_id":0,"install_id":0}"""))
        assertNull(DeviceRegister.parse("""{"device_id_str":"0","install_id_str":"0"}"""))
        assertNull(DeviceRegister.parse("不是 JSON"))
        // 形状不对（不是纯数字）也不认：拿一对坏 id 去签名，每个请求都会被拒
        assertNull(DeviceRegister.parse("""{"device_id":"abc","install_id":"def"}"""))
    }

    @Test
    fun `parse keeps the cookies the server hands out`() {
        val registered = DeviceRegister.parse(
            """{"device_id":4175314086266699,"install_id":4175314086270795}""",
            listOf(
                "store-region=cn-gd; Path=/; Domain=.fqnovel.com",
                "install_id=4175314086270795; Path=/",
                "ttreq=1\$1c2213245b92; Path=/",
            ),
        )
        assertEquals("cn-gd", registered?.cookies?.get("store-region"))
        assertEquals("4175314086270795", registered?.cookies?.get("install_id"))
        assertEquals("1\$1c2213245b92", registered?.cookies?.get("ttreq"))
    }
}
