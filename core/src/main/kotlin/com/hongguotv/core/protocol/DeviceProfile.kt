package com.hongguotv.core.protocol

/**
 * 注册与签名共用的设备档案：**一台老旧 WiFi 平板**。
 *
 * 为什么是平板而不是手机（2026-10-24 维护者口径）：
 * - 应用跑在电视上，电视只有 WiFi、没有蜂窝模块；用手机档案注册会带上
 *   `carrier` / `mcc_mnc` / SIM 序列号，与真实环境对不上；
 * - 老旧平板的 Android 版本低（7 / 8），与"客厅里一台旧电视盒子"的画像一致，
 *   也不需要伪造最新系统的能力。
 *
 * [osVersion] 与 [osApi] **必须成对**（`8.1.0` ↔ 27）：注册请求里这两项是同一个事实，
 * 对不上比"设备旧"更容易被风控挑出来。
 *
 * 一台设备只认一份档案：注册成功后档案随身份一起落盘，之后所有签名请求都用它，
 * 不再每次启动换机型（换机型 = 在服务端变成另一台设备）。
 */
data class DeviceProfile(
    /** `device_type` / `device_model`：机型代号（例如 `SM-T580`）。 */
    val model: String,
    /** `device_brand`：品牌（小写、无空格，例如 `samsung`）。 */
    val brand: String,
    /** `device_manufacturer`：制造商。 */
    val manufacturer: String,
    /** `os_version`：必须是字符串（服务端对数字形态直接拒绝）。 */
    val osVersion: String,
    /** `os_api`：与 [osVersion] 成对的 SDK 号。 */
    val osApi: Int,
    /** `Build` 串：UA 与注册请求都用它。 */
    val buildId: String,
    /** 屏幕分辨率：注册用 `1920x1200`，签名 query 用 `1920*1200`（[signingResolution]）。 */
    val resolution: String,
    /** `dpi`。 */
    val dpi: Int,
    /** `rom` / `rom_version`：旧平板的系统版本串。 */
    val rom: String,
    val romVersion: String,
    /** 注册 body 的 `density_dpi` 分类串。 */
    val displayDensity: String = "mdpi",
    /** 分发包渠道；与签名 query 的 `channel` 同源。 */
    val channel: String = "update_64",
    val cpuAbi: String = "arm64-v8a",
) {
    init {
        require(model.isNotBlank()) { "机型不能为空" }
        require(osVersion.isNotBlank()) { "os_version 不能为空（且必须是字符串）" }
        require(osApi >= 23) { "本项目最低 API 23，档案不能低于它：$osApi" }
        require(dpi > 0) { "dpi 必须为正：$dpi" }
    }

    /** `dragon_device_type`：平板固定 pad（手机档案是 phone）。 */
    val dragonDeviceType: String get() = "pad"

    /** 签名 query 里的分辨率写法（`*` 而不是 `x`）。 */
    val signingResolution: String get() = resolution.replace('x', '*')

    /** 签名请求的 UA（内容端点 / 取流共用）。 */
    val userAgent: String
        get() = "com.phoenix.read/73532 (Linux; U; Android $osVersion; zh_CN; $model; Build/$buildId; " +
            "Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    /** 注册请求的 UA：与注册 body 的 7.3.9.32 同一版本号。 */
    val registrationUserAgent: String
        get() = "com.phoenix.read/73932 (Linux; U; Android $osVersion; zh_CN; $model; Build/$buildId; " +
            "Cronet/TTNetVersion:7b7e7290-2026-03-03)"

    companion object {
        /**
         * 备用档案（按"旧 → 更旧"排列）。风控换新时轮换到下一条；
         * 注册与签名用的都是同一份，不要在一台设备上混用。
         */
        val TABLETS: List<DeviceProfile> = listOf(
            // 三星 Galaxy Tab A 10.1（2016）：Android 8.1 / API 27
            DeviceProfile(
                model = "SM-T580",
                brand = "samsung",
                manufacturer = "samsung",
                osVersion = "8.1.0",
                osApi = 27,
                buildId = "M1AJQ.T580XXU4CTK1",
                resolution = "1920x1200",
                dpi = 240,
                rom = "M1AJQ.T580XXU4CTK1",
                romVersion = "T580XXU4CTK1 release-keys",
                displayDensity = "hdpi",
            ),
            // 联想 Tab 4 10（2017）：Android 7.1.1 / API 25
            DeviceProfile(
                model = "Lenovo TB-X304F",
                brand = "Lenovo",
                manufacturer = "LENOVO",
                osVersion = "7.1.1",
                osApi = 25,
                buildId = "TB-X304F_S000123_180919_ROW",
                resolution = "1280x800",
                dpi = 213,
                rom = "TB-X304F_S000123_180919_ROW",
                romVersion = "TB-X304F_ROW release-keys",
                displayDensity = "tvdpi",
            ),
            // 华为 MediaPad T3 10（2017）：Android 7.0 / API 24
            DeviceProfile(
                model = "BAH-W09",
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                osVersion = "7.0",
                osApi = 24,
                buildId = "BAH-W09C00B260",
                resolution = "1280x800",
                dpi = 213,
                rom = "BAH-W09C00B260",
                romVersion = "BAH-W09 release-keys",
                displayDensity = "tvdpi",
            ),
        )

        /** 新装默认档案。 */
        val DEFAULT: DeviceProfile = TABLETS.first()

        /**
         * 旧签名样本（`signing.json`）对应的手机档案。
         *
         * **只给 `Signer(clock, random)` 的测试构造用**：那份样本是用这台手机生成的，
         * 换机型会让 x-medusa 的期望值全部失效。生产永远从 [TABLETS] 取档案。
         */
        internal val SIGNING_SAMPLE: DeviceProfile = DeviceProfile(
            model = "25053RT47C",
            brand = "Redmi",
            manufacturer = "Xiaomi",
            osVersion = "16",
            osApi = 36,
            buildId = "BP2A.250605.031.A3",
            resolution = "1280x2772",
            dpi = 520,
            rom = "BP2A.250605.031.A3",
            romVersion = "25053RT47C release-keys",
            displayDensity = "xhdpi",
        )

        /** 第 [index] 份档案（循环；用来在换新时轮换机型）。 */
        fun of(index: Int): DeviceProfile = TABLETS[((index % TABLETS.size) + TABLETS.size) % TABLETS.size]

        /** 该档案在表里的下标；不在表里（盘上被改坏）时回到 0。 */
        fun indexOf(profile: DeviceProfile): Int = TABLETS.indexOf(profile).coerceAtLeast(0)
    }
}
