package com.hongguotv.lite.data.content

import com.hongguotv.core.port.DevicePort
import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.SilentDiagnostics
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.DeviceIdentity
import com.hongguotv.core.protocol.DeviceProfile
import com.hongguotv.core.protocol.DeviceRegister
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.lite.data.http.HttpTransport
import com.hongguotv.lite.data.library.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * 一台设备在平台上的身份：`device_id` + `install_id` + 注册档案 + 下发的 Cookie。
 *
 * 三者必须一起用：query 里的 `device_id/iid`、x-medusa 里的机型/系统、
 * 注册下发的 Cookie 是"同一台设备"的三份描述。拆开用（比如只换 id 不换机型）
 * 比不注册更容易被风控挑出来。
 */
data class DeviceSnapshot(
    val identity: DeviceIdentity,
    val profile: DeviceProfile,
    val cookies: Map<String, String> = emptyMap(),
    /** 首次注册时间（毫秒）。 */
    val registeredAt: Long = 0,
    /** 最近一次"风控后换新"的时间（0 = 从未换过）。 */
    val renewedAt: Long = 0,
)

/**
 * 内容端点的身份来源。生产是 [DeviceSession]，单测/探针用 [FixedDeviceIdentity]。
 *
 * 单独抽接口而不是直接传 `DeviceIdentity`：身份可能在运行期被**风控换新**，
 * 签名器必须每次取当前值，而不是在构造时固化一份。
 */
interface DeviceIdentitySource {
    /** 当前身份；还没注册过时为 null。 */
    fun snapshot(): DeviceSnapshot?

    /** 确保有身份：没注册就先注册，并发调用收敛成一次请求。失败抛 [UpstreamError]。 */
    suspend fun ensure(): DeviceSnapshot

    /** 风控后换一台设备（新身份 + 下一份平板档案）。冷却期内返回 null，不重复注册。 */
    suspend fun renew(reason: String): DeviceSnapshot?
}

/** 固定身份：单测与真网探针用，不发起注册。 */
class FixedDeviceIdentity(private val value: DeviceSnapshot) : DeviceIdentitySource {
    override fun snapshot(): DeviceSnapshot = value
    override suspend fun ensure(): DeviceSnapshot = value
    override suspend fun renew(reason: String): DeviceSnapshot? = null
}

/**
 * 设备注册与身份落盘：**一台设备只注册一次**（2026-10-24 维护者口径）。
 *
 * 以前本机每次启动随机生成一对数字当身份（`AnonymousIdentity`）——那等于每次启动都
 * 在服务端变成一台新设备，既拿不到搜索数据，也在把设备数刷起来。现在只有一条路：
 *
 * 1. 启动时 [ensure]：本地没有注册结果就发一次匿名 `device_register`，成功后
 *    id / 档案 / Cookie 一起落盘；以后每次启动都读这一份，**不再注册**；
 * 2. 只有被风控（200 空体 / 风控码）时才 [renew]：换下一份平板档案 + 一对新身份，
 *    并且有 [RENEW_COOLDOWN_MS] 的冷却，连续失败不会变成"注册风暴"；
 * 3. 注册请求本身不带账号、不带签名头，模拟的是匿名游客。
 */
class DeviceSession(
    private val store: KeyValueStore,
    private val http: HttpTransport,
    private val diagnostics: DiagnosticsPort = SilentDiagnostics,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : DeviceIdentitySource, DevicePort {

    private val mutex = Mutex()

    @Volatile
    private var current: DeviceSnapshot? = load()

    /** 上次注册失败的现场：失败后 60 秒内不再发请求，避免每个内容请求都重注册一遍。 */
    @Volatile
    private var failureAt = 0L

    @Volatile
    private var failure: Throwable? = null

    override fun snapshot(): DeviceSnapshot? = current

    override suspend fun ensure(): DeviceSnapshot {
        current?.let { return it }
        blocked()?.let { throw it }
        return mutex.withLock {
            current ?: run {
                blocked()?.let { throw it }
                register(nextProfileIndex = 0, renewed = false)
            }
        }
    }

    /** 退避期内的上次失败；过了退避期返回 null（允许再试一次）。 */
    private fun blocked(): Throwable? =
        failure?.takeIf { clock() - failureAt < FAILURE_BACKOFF_MS }

    override suspend fun renew(reason: String): DeviceSnapshot? = rotate(reason, forced = false)

    /**
     * 手动换一台设备：**忽略 12 小时冷却**，但不忽略"注册失败后 60 秒退避"。
     *
     * 冷却挡的是"一失败就换设备"；用户按下「重新注册」是明确的人工意图，冷却期内静默
     * 返回 null 会让人以为按钮坏了。退避不同——它挡的是失败风暴，连点按钮同样会打出一串
     * 注册请求，所以照旧生效（这一分钟内返回 false，界面提示稍后重试）。
     */
    override suspend fun reRegister(): Boolean = rotate(MANUAL_REASON, forced = true) != null

    private suspend fun rotate(reason: String, forced: Boolean): DeviceSnapshot? {
        // 刚失败过的这一分钟不发请求：自动换新与手动点击都一样（见 reRegister 的说明）
        blocked()?.let { return null }
        val base = current ?: try {
            ensure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return null
        }
        if (!forced && !canRenew(base)) return null
        return mutex.withLock {
            val fresh = current ?: base
            if (!forced && !canRenew(fresh)) return@withLock null
            // 换新失败不要把旧身份丢掉：旧身份还能继续试，丢掉只会让下次请求连签名都没有
            val next = try {
                register(nextProfileIndex = DeviceProfile.indexOf(fresh.profile) + 1, renewed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.event("device.renew_failed", mapOf("reason" to reason))
                return@withLock null
            }
            diagnostics.event("device.renewed", mapOf("reason" to reason, "model" to next.profile.model))
            next
        }
    }

    /** 冷却：同一台设备 12 小时内最多换一次，避免"一失败就重新注册"。 */
    private fun canRenew(snapshot: DeviceSnapshot): Boolean {
        if (snapshot.renewedAt <= 0L) return true
        return clock() - snapshot.renewedAt >= RENEW_COOLDOWN_MS
    }

    private suspend fun register(nextProfileIndex: Int, renewed: Boolean): DeviceSnapshot = try {
        registerOnce(nextProfileIndex, renewed).also {
            failure = null
            failureAt = 0
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failure = error
        failureAt = clock()
        throw error
    }

    private suspend fun registerOnce(nextProfileIndex: Int, renewed: Boolean): DeviceSnapshot {
        val profile = DeviceProfile.of(nextProfileIndex)
        val now = clock()
        val body = DeviceRegister.payload(
            profile = profile,
            now = now,
            randomId = { UUID.randomUUID().toString() },
            random = random,
        )
        val request = SignedRequest(
            method = HttpMethod.POST,
            url = DeviceRegister.url(),
            headers = DeviceRegister.headers(body, profile, now),
            // Content-Type 必须带 `tt-data=a`：OkHttp 会用 RequestBody 的 media type 覆盖同名头，
            // 只写 `application/octet-stream` 会把参数丢掉（服务端可能因此回 device_id=0）
            contentType = "application/octet-stream;tt-data=a",
            body = body,
        )
        val response = http.response(request)
        val registered = DeviceRegister.parse(response.body, response.setCookies)
            ?: throw UpstreamError.Device("rejected")
        val snapshot = DeviceSnapshot(
            identity = DeviceIdentity(registered.deviceId, registered.installId),
            profile = profile,
            cookies = registered.cookies,
            registeredAt = if (renewed) current?.registeredAt ?: now else now,
            renewedAt = if (renewed) now else current?.renewedAt ?: 0L,
        )
        persist(snapshot)
        diagnostics.event(
            "device.registered",
            mapOf(
                "new_user" to registered.newUser,
                "model" to profile.model,
                "api" to profile.osApi,
                "renewed" to renewed,
            ),
        )
        return snapshot
    }

    /** 写内存与磁盘只在注册成功后发生：失败时旧身份保持可用。 */
    private suspend fun persist(snapshot: DeviceSnapshot) {
        current = snapshot
        val encoded = encode(snapshot)
        // 注册一次才写一次，但不要在 Main 上落盘：SharedPreferences.commit 是同步磁盘写。
        // 落盘失败不反悔内存态（current 已是新身份）：磁盘满时本次会话照常用新身份、
        // 重启后重新注册；抛出去反而会把一次成功的注册变成内容请求失败。
        withContext(Dispatchers.IO) {
            runCatching { store.commit(mapOf(KEY_REGISTRATION to encoded)) }
                .onFailure {
                    diagnostics.event("device.persist", mapOf("error" to it.javaClass.simpleName))
                }
        }
    }

    private fun load(): DeviceSnapshot? {
        val raw = store.all()[KEY_REGISTRATION] as? String ?: return null
        return decode(raw)
    }

    private fun encode(snapshot: DeviceSnapshot): String = JSONObject().apply {
        put("device_id", snapshot.identity.deviceId)
        put("install_id", snapshot.identity.installId)
        put("registered_at", snapshot.registeredAt)
        put("renewed_at", snapshot.renewedAt)
        put(
            "cookies",
            JSONObject().apply { snapshot.cookies.forEach { (name, value) -> put(name, value) } },
        )
        put("profile", JSONObject().apply {
            put("model", snapshot.profile.model)
            put("brand", snapshot.profile.brand)
            put("manufacturer", snapshot.profile.manufacturer)
            put("os_version", snapshot.profile.osVersion)
            put("os_api", snapshot.profile.osApi)
            put("build_id", snapshot.profile.buildId)
            put("resolution", snapshot.profile.resolution)
            put("dpi", snapshot.profile.dpi)
            put("rom", snapshot.profile.rom)
            put("rom_version", snapshot.profile.romVersion)
            put("display_density", snapshot.profile.displayDensity)
            put("channel", snapshot.profile.channel)
            put("cpu_abi", snapshot.profile.cpuAbi)
        })
    }.toString()

    private fun decode(raw: String): DeviceSnapshot? = runCatching {
        val json = JSONObject(raw)
        val p = json.getJSONObject("profile")
        val profile = DeviceProfile(
            model = p.getString("model"),
            brand = p.getString("brand"),
            manufacturer = p.getString("manufacturer"),
            osVersion = p.getString("os_version"),
            osApi = p.getInt("os_api"),
            buildId = p.getString("build_id"),
            resolution = p.getString("resolution"),
            dpi = p.getInt("dpi"),
            rom = p.getString("rom"),
            romVersion = p.getString("rom_version"),
            displayDensity = p.optString("display_density", "mdpi"),
            channel = p.optString("channel", "update_64"),
            cpuAbi = p.optString("cpu_abi", "arm64-v8a"),
        )
        val cookies = json.optJSONObject("cookies")?.let { obj ->
            buildMap { obj.keys().forEach { key -> obj.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) } } }
        }.orEmpty()
        DeviceSnapshot(
            identity = DeviceIdentity(json.getString("device_id"), json.getString("install_id")),
            profile = profile,
            cookies = cookies,
            registeredAt = json.optLong("registered_at", 0L),
            renewedAt = json.optLong("renewed_at", 0L),
        )
    }.getOrNull()

    companion object {
        /** 注册结果在本地库里的键。改名 = 已装机的用户全部重新注册一遍，不要动。 */
        const val KEY_REGISTRATION = "device-registration"

        /** 风控换新的冷却：比"重试"慢得多，比"永远不换"快得多。 */
        const val RENEW_COOLDOWN_MS = 12 * 60 * 60 * 1000L

        /** 手动重新注册的诊断口径：日志里要能分清"风控自动换的"与"用户按的"。 */
        private const val MANUAL_REASON = "manual"

        /** 注册失败后的退避：一分钟内不再为下一个内容请求重试注册。 */
        const val FAILURE_BACKOFF_MS = 60 * 1000L
    }
}
