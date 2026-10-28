package com.hongguotv.core.protocol

/**
 * 设备身份（`device_id` + `install_id`）。
 *
 * **只有一个来源**：平台注册（[DeviceRegister]）下发的那一对。仓库内置的固定值来自
 * 第三方共用档案，已被拉黑——带它签名的取流请求一律回 `HTTP 200 + 0 字节`；
 * 客户端自己随手编一对也拿不到搜索数据。一台设备注册一次、落盘复用，被封了才换。
 *
 * 同一份身份必须同时进入 query、`x-medusa` 的 protobuf 与 `x-medusa` 的环境段；
 * 只在 query 里换值会被判成异常客户端（三处同源由 [Signer] 保证）。
 */
data class DeviceIdentity(val deviceId: String, val installId: String) {

    init {
        // 身份是风控字段：异常消息里**不带原值**。这个消息会随未捕获异常进 logcat / 崩溃上报，
        // 写全等于把 device_id 公开。留长度与前 2 位，够判断"形状错在哪"，不够拿来冒用。
        require(ID.matches(deviceId)) { "device_id 形状不对：${masked(deviceId)}" }
        require(ID.matches(installId)) { "install_id 形状不对：${masked(installId)}" }
    }

    private fun masked(value: String): String =
        if (value.length <= 2) "长度 ${value.length}" else "长度 ${value.length} · 前缀 ${value.take(2)}…"

    companion object {
        private val ID = Regex("\\d{10,20}")

        /**
         * 旧实现里写死的那一对：已被平台拉黑，**生产不得使用**。
         * 只留给本模块的表形状测试与签名样本 oracle——不是 public API，
         * 生产代码不会再有任何一处把它当默认值（装配漏注入在编译期就报错）。
         */
        internal val LEGACY = DeviceIdentity("1905892595378490", "1905892595382586")
    }
}
