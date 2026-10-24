package com.hongguotv.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匿名设备身份。**取流接口只按 device_id 是否在黑名单里放行**：仓库内置的那对固定值
 * （大量第三方共用）已被拉黑，所有取流请求会回 200 空体。客户端因此自己生成一对匿名数字，
 * 并且**必须让 query、x-medusa、设备 proto 三处用同一个身份**——只改 query 会被判成异常客户端。
 */
class DeviceIdentityTest {

    @Test
    fun anonymousIdentityIsSixteenDigits() {
        val identity = DeviceIdentity.anonymous()
        assertTrue("device_id 应是 16 位数字：${identity.deviceId}", Regex("\\d{16}").matches(identity.deviceId))
        assertTrue("install_id 应是 16 位数字：${identity.installId}", Regex("\\d{16}").matches(identity.installId))
        assertNotEquals(identity.deviceId, identity.installId)
    }

    @Test
    fun everyAnonymousIdentityIsDifferent() {
        val first = DeviceIdentity.anonymous()
        val second = DeviceIdentity.anonymous()
        assertNotEquals(first, second)
    }

    @Test
    fun contentQueryUsesTheGivenIdentityInsteadOfTheBuiltInOne() {
        val identity = DeviceIdentity("700000000000000001", "800000000000000002")
        val request = FullSuiteSigner(identity = identity)
            .sign(Endpoints["detail"], mapOf("seriesId" to "123"))
        val query = request.url.substringAfter('?')
        assertTrue("query 必须带自己的 device_id：$query", query.contains("device_id=${identity.deviceId}"))
        assertTrue("query 必须带自己的 iid：$query", query.contains("iid=${identity.installId}"))
        assertTrue("内置黑名单身份不得出现：$query", !query.contains(DeviceIdentity.LEGACY.deviceId))
    }

    @Test
    fun differentIdentitiesProduceDifferentMedusaHeaders() {
        val a = FullSuiteSigner(identity = DeviceIdentity("700000000000000001", "800000000000000002"))
            .sign(Endpoints["detail"], mapOf("seriesId" to "123"))
        val b = FullSuiteSigner(identity = DeviceIdentity("700000000000000003", "800000000000000004"))
            .sign(Endpoints["detail"], mapOf("seriesId" to "123"))
        assertNotEquals("只改 query 不改 x-medusa 会被判成异常客户端", a.headers["x-medusa"], b.headers["x-medusa"])
    }
}
