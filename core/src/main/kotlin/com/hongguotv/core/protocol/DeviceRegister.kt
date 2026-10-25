package com.hongguotv.core.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 匿名设备注册（`POST /service/2/device_register/`）。
 *
 * 平台的内容接口**按设备放行**：搜索/详情/取流都认"服务端注册过的
 * `device_id` + `install_id` 这一对"。注册本身不需要账号、密码或短信——
 * 一台设备发一次匿名请求就够：成功后身份与 Cookie 一起落盘，之后复用；被风控才换新。
 *
 * ```
 * POST https://log5-applog-lf.fqnovel.com/service/2/device_register/
 *      ?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1
 * Content-Type: application/octet-stream;tt-data=a
 * log-encode-type: gzip
 * x-ss-dp: 8662
 * x-ss-stub: <body 的 MD5，大写>
 * body: ttEncrypt(gzip(JSON))
 * ```
 *
 * 三个踩过的坑（照文档逐条避开）：
 * 1. **body 必须是原始二进制**，不是 base64 文本（抓包 HAR 里 `postData.text` 是 base64，
 *    照着它发服务端一律回 `device_id=0`）；
 * 2. **`os_version` 必须是字符串**（写数字 0/6 成功，写字符串 6/6 成功）；
 * 3. **不能带签名头**（x-gorgon / x-khronos / x-ladon / x-argus / x-helios / x-medusa 一个都不要），
 *    加了反而被判异常客户端。
 *
 * 这个文件不引用 `android.*`：请求体组装、加密与响应解析都能在 JVM 单测里跑。
 */
object DeviceRegister {

    private const val ENDPOINT = "deviceRegister"

    /** 注册地址从端点表拼：host / path / query 都是协议的一部分，不在调用点手抄。 */
    fun url(): String {
        val endpoint = Endpoints[ENDPOINT]
        val query = endpoint.resolvedQuery(emptyMap())
            .joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
        return endpoint.url(emptyMap()) + if (query.isEmpty()) "" else "?$query"
    }

    /** ttEncrypt 的固定前缀（`libEncryptor.so` 的魔数）。 */
    private val MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)

    /** ttEncrypt 的 KDF 盐：与 `MediaCrypto.decryptUrl` 同一族常量。 */
    private val KDF_SALT = hex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58",
    )

    private const val SIG_HASH = "56a962410c494bbaf0b58dba20cae56f"

    /** 注册结果：平台下发的设备身份 + 需要回写的那几个 Cookie。 */
    data class Registered(
        val deviceId: String,
        val installId: String,
        val deviceToken: String = "",
        /** `new_user=1` 表示平台这次新建了一台设备。 */
        val newUser: Boolean = false,
        /** `Set-Cookie` 里可直接回写的那几项（`install_id` / `ttreq` / `store-region` …）。 */
        val cookies: Map<String, String> = emptyMap(),
    )

    /**
     * ttEncrypt：`MAGIC || rnd[32] || AES-128-CBC(key, iv, PKCS7(SHA512(data) || data))`。
     * `key || iv = SHA512(SHA512(rnd) || KDF_SALT)` 的前 32 字节。
     */
    fun ttEncrypt(data: ByteArray, random: Random = Random.Default): ByteArray {
        val rnd = ByteArray(32).also { random.nextBytes(it) }
        val derived = digest("SHA-512", digest("SHA-512", rnd) + KDF_SALT)
        val plain = digest("SHA-512", data) + data
        val padding = 16 - plain.size % 16
        val padded = plain + ByteArray(padding) { padding.toByte() }
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derived.copyOfRange(0, 16), "AES"),
                IvParameterSpec(derived.copyOfRange(16, 32)),
            )
            doFinal(padded)
        }
        return MAGIC + rnd + encrypted
    }

    /** 注册请求体：`ttEncrypt(gzip(JSON))`。`device_id` / `install_id` 不带，由服务端下发。 */
    fun payload(
        profile: DeviceProfile,
        now: Long = System.currentTimeMillis(),
        randomId: () -> String = { UUID.randomUUID().toString() },
        random: Random = Random.Default,
    ): ByteArray = ttEncrypt(gzip(bodyJson(profile, now, randomId).toByteArray(Charsets.UTF_8)), random)

    /** 明文 JSON（拆出来是为了单测能直接断言字段，不必每次解密）。 */
    fun bodyJson(
        profile: DeviceProfile,
        now: Long = System.currentTimeMillis(),
        randomId: () -> String = { UUID.randomUUID().toString() },
    ): String {
        val header = JSONObject().apply {
            put("display_name", "红果免费短剧")
            put("aid", 8662)
            put("channel", profile.channel)
            put("package", "com.phoenix.read")
            put("version_code", 73932)
            put("app_version", "7.3.9.32")
            put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
            put("sdk_target_version", 29)
            put("git_hash", "3a88f4e")
            put("release_build", "b5e187f")
            put("os", "Android")
            // 必须是字符串：服务端对数字形态直接回 device_id=0
            put("os_version", profile.osVersion)
            put("os_api", profile.osApi)
            put("device_model", profile.model)
            put("device_brand", profile.brand)
            put("device_manufacturer", profile.manufacturer)
            put("cpu_abi", profile.cpuAbi)
            put("density_dpi", profile.dpi)
            put("display_density", profile.displayDensity)
            put("resolution", profile.resolution)
            put("language", "zh")
            put("timezone", 8)
            // 电视/平板都是 WiFi：不写 carrier / mcc_mnc / sim_region，SIM 列表留空
            put("access", "wifi")
            put("sim_serial_number", JSONArray())
            put("rom", profile.rom)
            put("rom_version", profile.romVersion)
            put("cdid", randomId())
            put("sig_hash", SIG_HASH)
            put("openudid", randomId().replace("-", "").take(16).padEnd(16, '0'))
            put("clientudid", randomId())
            put("region", "CN")
            put("tz_name", "Asia/Shanghai")
            put("tz_offset", 28800)
            put("oaid_may_support", false)
            put("req_id", randomId())
            put("device_platform", "android")
            put(
                "custom",
                JSONObject().apply {
                    put("account_region_src", "fallback")
                    put("host_bit", 64)
                    put("account_region", "cn")
                    put("dragon_device_type", profile.dragonDeviceType)
                },
            )
            put("pre_installed_channel", "")
            put("apk_first_install_time", now)
            put("is_system_app", 0)
            put("sdk_flavor", "china")
            put("guest_mode", 0)
        }
        return JSONObject().apply {
            put("magic_tag", "ss_app_log")
            put("header", header)
            put("_gen_time", now)
        }.toString()
    }

    /** 注册请求头。**刻意不加签名头**（见类注释第 3 条）。 */
    fun headers(
        body: ByteArray,
        profile: DeviceProfile,
        now: Long = System.currentTimeMillis(),
    ): Map<String, String> = linkedMapOf(
        "User-Agent" to profile.registrationUserAgent,
        "Content-Type" to "application/octet-stream;tt-data=a",
        "log-encode-type" to "gzip",
        "x-ss-dp" to "8662",
        "x-ss-req-ticket" to now.toString(),
        "x-vc-bdturing-sdk-version" to "4.0.3.cn",
        "lc" to "101",
        "sdk-version" to "2",
        "passport-sdk-version" to "5051452",
        // 不手动设 accept-encoding：OkHttp 只在自己加这个头时才透明解压
        "x-neptune" to "-8|50:51:59:30:40:47:49:39",
        "x-ss-stub" to digest("MD5", body).hex().uppercase(),
    )

    /**
     * 解析注册响应。
     *
     * `device_id` / `install_id` 平台有时给数字、有时给字符串，两种都认；
     * 全为 `0` 表示这次注册被拒（body 形态或头不对），返回 null。
     */
    fun parse(body: String, setCookies: List<String> = emptyList()): Registered? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val device = json.opt("device_id_str")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: json.opt("device_id")?.toString().orEmpty()
        val install = json.opt("install_id_str")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: json.opt("install_id")?.toString().orEmpty()
        if (device.isBlank() || install.isBlank() || device == "0" || install == "0") return null
        // 形状不对的 id 不能落盘：拿一对坏 id 去签名，每个请求都会被拒
        if (!runCatching { DeviceIdentity(device, install) }.isSuccess) return null
        val cookies = linkedMapOf<String, String>()
        setCookies.forEach { header ->
            val pair = header.split(';').firstOrNull()?.trim().orEmpty()
            if (pair.contains('=')) {
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
        }
        return Registered(
            deviceId = device,
            installId = install,
            deviceToken = json.optString("device_token", ""),
            newUser = json.optInt("new_user", 0) == 1,
            cookies = cookies,
        )
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()
}
