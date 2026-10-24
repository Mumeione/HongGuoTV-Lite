package com.hongguotv.core.protocol

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备身份：**只有一个来源——平台注册下发的那一对**（见 `DeviceRegister`）。
 *
 * 仓库内置的固定值（大量第三方共用）已被拉黑，所有取流请求会回 200 空体；
 * 客户端自己随手编一对数字也不行（搜索拿不到数据）。测试这里钉的是"注入的身份
 * 必须同时进入 query 与 x-medusa"——只改 query 会被判成异常客户端。
 */
class DeviceIdentityTest {

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

    @Test
    fun identityMustLookLikeADeviceNumber() {
        // 形状不对的 id 不能落盘：拿一对坏 id 去签名，每个请求都会被拒
        val invalid = runCatching { DeviceIdentity("abc", "800000000000000002") }
        assertTrue("非数字 id 必须被拒绝", invalid.isFailure)
        val tooShort = runCatching { DeviceIdentity("123", "800000000000000002") }
        assertTrue("过短的 id 必须被拒绝", tooShort.isFailure)
    }
}
