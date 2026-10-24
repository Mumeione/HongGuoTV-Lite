package com.hongguotv.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 签名必须与注册档案同源：注册是平板、签名是手机，风控一眼就能看出来。 */
class SignerDeviceTest {

    private val identity = DeviceIdentity("700000000000000001", "800000000000000002")

    @Test
    fun `query carries the registered tablet profile`() {
        val profile = DeviceProfile.of(1) // Lenovo TB-X304F / Android 7.1.1
        val signed = FullSuiteSigner(identity, profile).sign(Endpoints["detail"], mapOf("seriesId" to "7"))
        val query = signed.url.substringAfter('?')
        assertTrue("机型：$query", query.contains("device_type=Lenovo%20TB-X304F"))
        assertTrue("系统版本：$query", query.contains("os_version=7.1.1"))
        assertTrue("SDK：$query", query.contains("os_api=25"))
        assertTrue("平板类型：$query", query.contains("dragon_device_type=pad"))
        assertTrue("平板标记：$query", query.contains("is_android_pad_screen=1"))
        assertTrue("分辨率：$query", query.contains("resolution=1280*800"))
        assertTrue("身份：$query", query.contains("device_id=${identity.deviceId}"))
    }

    @Test
    fun `user agent follows the profile instead of the old phone constant`() {
        val signed = FullSuiteSigner(identity, DeviceProfile.of(2)).sign(Endpoints["detail"], mapOf("seriesId" to "7"))
        val ua = signed.headers.getValue("User-Agent")
        assertTrue(ua, ua.contains("BAH-W09"))
        assertTrue(ua, ua.contains("Android 7.0"))
        assertFalse("不能还是旧的小米手机 UA", ua.contains("25053RT47C"))
    }

    @Test
    fun `registration cookies ride along with signed requests`() {
        val cookies = mapOf("install_id" to identity.installId, "ttreq" to "1$1c2213245b92")
        val signed = FullSuiteSigner(identity, DeviceProfile.DEFAULT, cookies)
            .sign(Endpoints["detail"], mapOf("seriesId" to "7"))
        assertEquals(
            "install_id=${identity.installId}; ttreq=1\$1c2213245b92",
            signed.headers["Cookie"],
        )
    }

    @Test
    fun `no cookies means no empty cookie header`() {
        val signed = FullSuiteSigner(identity, DeviceProfile.DEFAULT).sign(Endpoints["seasonLine"], mapOf("seriesId" to "7"))
        assertFalse(signed.headers.containsKey("Cookie"))
    }
}
