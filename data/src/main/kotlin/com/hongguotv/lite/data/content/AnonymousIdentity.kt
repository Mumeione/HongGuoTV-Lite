package com.hongguotv.lite.data.content

import com.hongguotv.core.protocol.DeviceIdentity
import com.hongguotv.lite.data.library.KeyValueStore

/**
 * 本机的匿名设备身份：**装一次、用到底**。
 *
 * 平台对取流只要求 `device_id` 不在黑名单里，对身份来源不设门槛；但每次启动换一对
 * 会让同一台电视在服务端不断变成"新设备"。落盘一次既满足"不在黑名单"，
 * 又不会把设备数刷起来。
 *
 * 与"账号"无关：这里不登录、不上报任何用户信息，只是一对随机数字。
 * 搜索结果不依赖它（搜索走官网接口）；它只用于取流与内容端点的签名。
 */
object AnonymousIdentity {

    private const val DEVICE_KEY = "anon-device-id"
    private const val INSTALL_KEY = "anon-install-id"

    /** 读已落盘的一对；没有（或曾写坏）就生成一对并落盘。 */
    fun of(store: KeyValueStore): DeviceIdentity {
        val values = store.all()
        val device = values[DEVICE_KEY] as? String
        val install = values[INSTALL_KEY] as? String
        if (device != null && install != null) {
            // 落盘值被改坏时当作没有：一对形状不对的 id 会让每个请求都被拒
            runCatching { DeviceIdentity(device, install) }.getOrNull()?.let { return it }
        }

        val fresh = DeviceIdentity.anonymous()
        store.commit(mapOf(DEVICE_KEY to fresh.deviceId, INSTALL_KEY to fresh.installId))
        return fresh
    }
}
