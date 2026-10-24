package com.hongguotv.core.protocol

import kotlin.random.Random

/**
 * 匿名设备身份（`device_id` + `install_id`）。
 *
 * **为什么要有这个东西**：平台按这一对放行。仓库里内置的那对固定值来自第三方共用档案，
 * 已被拉黑——带它签名的取流请求一律回 `HTTP 200 + 0 字节`，看起来像"接口坏了"。
 * 播放接口的实际门槛只是"`device_id` 不在黑名单里"，因此客户端生成一对匿名数字即可播放。
 *
 * 同一份身份必须同时进入 query、`x-medusa` 的 protobuf 与 `x-medusa` 的环境段；
 * 只在 query 里换值会被判成异常客户端（三处同源由 [Signer] 保证）。
 */
data class DeviceIdentity(val deviceId: String, val installId: String) {

    init {
        require(ID.matches(deviceId)) { "device_id 形状不对：$deviceId" }
        require(ID.matches(installId)) { "install_id 形状不对：$installId" }
    }

    companion object {
        private val ID = Regex("\\d{10,20}")

        /** 旧实现里写死的那一对：保留给表形状测试与"没有注入身份"的默认路径，**生产不得使用**。 */
        val LEGACY = DeviceIdentity("1905892595378490", "1905892595382586")

        /**
         * 一对全新的匿名身份。
         *
         * 实测平台对"从没见过的 16 位数字"是放行的（播放接口 20/20 可播），
         * 只有被拉黑的固定值不行。**不用于搜索**：App 搜索要求服务端注册过的 `device_id` + `iid`，
         * 本工程的搜索走官网接口，不需要身份。
         */
        fun anonymous(random: Random = Random.Default): DeviceIdentity = DeviceIdentity(
            deviceId = (1_000_000_000_000_000L + random.nextLong(8_000_000_000_000_000L)).toString(),
            installId = (1_000_000_000_000_000L + random.nextLong(8_000_000_000_000_000L)).toString(),
        )
    }
}
