package com.hongguotv.lite.data.content

import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.DeviceIdentity
import com.hongguotv.core.protocol.DeviceProfile
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.lite.data.http.HttpResponse
import com.hongguotv.lite.data.http.HttpTransport
import com.hongguotv.lite.data.library.KeyValueStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * 设备注册的落盘与"只注册一次"：一台设备在服务端只能是一台设备。
 *
 * 这些断言挡的是旧实现的行为——每次启动随机换一对 id，等于每开机一次就多一台设备。
 */
class DeviceSessionTest {

    private class MemoryStore(initial: Map<String, Any?> = emptyMap()) : KeyValueStore {
        private val values = LinkedHashMap<String, Any?>(initial)
        override fun all(): Map<String, Any?> = LinkedHashMap(values)
        override fun commit(batch: Map<String, Any?>) {
            batch.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }

    private class RegisterTransport(
        /** 第 [calls] 次注册的响应；默认每次下发一对新 id（真实服务端就是这样）。 */
        private val bodyFor: (Int) -> String = { call ->
            """{"device_id":${4175314086266698L + call},"install_id":${4175314086270794L + call},"new_user":1}"""
        },
        private val cookies: List<String> = listOf("install_id=4175314086270795; Path=/", "ttreq=1\$abc; Path=/"),
        private val latencyMs: Long = 0,
    ) : HttpTransport {
        var calls = 0
            private set
        val requests = mutableListOf<SignedRequest>()

        override suspend fun text(request: SignedRequest): String = error("注册要读 Set-Cookie，必须走 response")

        override suspend fun response(request: SignedRequest): HttpResponse {
            calls++
            requests += request
            if (latencyMs > 0) delay(latencyMs)
            return HttpResponse(bodyFor(calls), cookies)
        }
    }

    private fun session(
        store: KeyValueStore,
        transport: HttpTransport,
        clock: () -> Long = { 1_000_000L },
    ): DeviceSession = DeviceSession(store, transport, clock = clock)

    @Test
    fun `first ensure registers once and writes the identity to disk`() = runTest {
        val store = MemoryStore()
        val transport = RegisterTransport()
        val devices = session(store, transport)

        val snapshot = devices.ensure()
        assertEquals("4175314086266699", snapshot.identity.deviceId)
        assertEquals("4175314086270795", snapshot.identity.installId)
        assertEquals(DeviceProfile.DEFAULT, snapshot.profile)
        assertEquals("1\$abc", snapshot.cookies["ttreq"])
        assertEquals(1, transport.calls)
        assertNotNull("注册结果必须落盘", store.all()[DeviceSession.KEY_REGISTRATION])

        // 第二次 ensure 不再发请求
        devices.ensure()
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a restarted app reads the stored identity instead of registering again`() = runTest {
        val store = MemoryStore()
        session(store, RegisterTransport()).ensure()

        val nextTransport = RegisterTransport()
        val restarted = session(store, nextTransport)
        assertNotNull("启动时就能拿到身份", restarted.snapshot())
        assertEquals("4175314086266699", restarted.ensure().identity.deviceId)
        assertEquals("重启不应重新注册", 0, nextTransport.calls)
    }

    @Test
    fun `concurrent ensure collapses into one registration`() = runTest {
        val transport = RegisterTransport(latencyMs = 50)
        val devices = session(MemoryStore(), transport)
        coroutineScope {
            List(8) { async { devices.ensure() } }.awaitAll()
        }
        assertEquals("并发首次请求只注册一次", 1, transport.calls)
    }

    @Test
    fun `a second renew is refused inside the cooldown window`() = runTest {
        var now = 1_000_000L
        val transport = RegisterTransport()
        val devices = session(MemoryStore(), transport) { now }
        devices.ensure()
        assertEquals(1, transport.calls)

        // 第一次被风控：允许换一台（否则这台设备会一直空体）
        now += 60 * 60 * 1000L
        assertNotNull(devices.renew("EmptyBody"))
        assertEquals(2, transport.calls)

        // 冷却期内再失败：不能再注册（一失败就换 = 注册风暴）
        now += 60 * 60 * 1000L
        assertNull(devices.renew("EmptyBody"))
        assertEquals("12 小时内最多换一次", 2, transport.calls)
    }

    @Test
    fun `renew after the cooldown rotates to the next tablet`() = runTest {
        var now = 1_000_000L
        val transport = RegisterTransport()
        val devices = session(MemoryStore(), transport) { now }
        val first = devices.ensure()

        now += DeviceSession.RENEW_COOLDOWN_MS + 1
        val renewed = devices.renew("EmptyBody")
        assertNotNull(renewed)
        assertEquals(2, transport.calls)
        assertEquals("换新要轮换机型，不能还是同一台", DeviceProfile.of(1), renewed!!.profile)
        assertEquals("首次注册时间保留", first.registeredAt, renewed.registeredAt)
        assertEquals("换新必须拿到一对新身份", DeviceIdentity("4175314086266700", "4175314086270796"), renewed.identity)

        // 再换一次 → 第三份档案
        now += DeviceSession.RENEW_COOLDOWN_MS + 1
        assertEquals(DeviceProfile.of(2), devices.renew("EmptyBody")?.profile)
    }

    @Test
    fun `a rejected registration does not clobber an existing identity`() = runTest {
        var now = 1_000_000L
        val store = MemoryStore()
        val good = RegisterTransport()
        val devices = session(store, good) { now }
        val first = devices.ensure()

        now += DeviceSession.RENEW_COOLDOWN_MS + 1
        val rejecting = RegisterTransport(bodyFor = { """{"device_id":0,"install_id":0}""" })
        val failing = DeviceSession(store, rejecting, clock = { now })
        // 新实例从盘上读到的仍是旧身份；换新失败时必须保留它
        assertEquals(first.identity, failing.ensure().identity)
        assertNull(failing.renew("EmptyBody"))
        assertEquals(first.identity, failing.ensure().identity)
    }

    @Test
    fun `a rejected first registration surfaces a device error`() = runTest {
        val devices = session(MemoryStore(), RegisterTransport(bodyFor = { """{"device_id":0,"install_id":0}""" }))
        assertFailsWith<UpstreamError.Device> { devices.ensure() }
        assertNull(devices.snapshot())
    }

    @Test
    fun `a failed registration backs off for a minute`() = runTest {
        var now = 1_000_000L
        val transport = RegisterTransport(bodyFor = { """{"device_id":0,"install_id":0}""" })
        val devices = session(MemoryStore(), transport) { now }
        assertFailsWith<UpstreamError.Device> { devices.ensure() }
        assertEquals(1, transport.calls)

        // 退避期内：下一个内容请求不再打注册接口
        now += 30_000
        assertFailsWith<UpstreamError.Device> { devices.ensure() }
        assertEquals(1, transport.calls)

        // 过了退避期才允许再试一次
        now += 60_000
        assertFailsWith<UpstreamError.Device> { devices.ensure() }
        assertEquals(2, transport.calls)
    }

    @Test
    fun `corrupted stored registration falls back to registering again`() = runTest {
        val store = MemoryStore(mapOf(DeviceSession.KEY_REGISTRATION to "{not-json"))
        val transport = RegisterTransport()
        val devices = session(store, transport)
        assertNull("坏数据不能当身份用", devices.snapshot())
        assertEquals("4175314086266699", devices.ensure().identity.deviceId)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `registration request is an unsigned octet stream to the documented endpoint`() = runTest {
        val transport = RegisterTransport()
        session(MemoryStore(), transport).ensure()
        val request = transport.requests.single()
        assertEquals(HttpMethod.POST, request.method)
        assertTrue(request.url, request.url.startsWith("https://log5-applog-lf.fqnovel.com/service/2/device_register/"))
        assertEquals("application/octet-stream;tt-data=a", request.contentType)
        assertTrue("body 是二进制 ttEncrypt，不是文本", (request.body?.size ?: 0) > 100)
        assertEquals(
            "body 的 MD5 大写",
            java.security.MessageDigest.getInstance("MD5").digest(request.body).joinToString("") { "%02X".format(it) },
            request.headers["x-ss-stub"],
        )
        // 不能带签名头
        assertTrue(request.headers.keys.none { it.startsWith("x-gorgon") || it.startsWith("x-medusa") })
    }

    @Test
    fun `stored json is the only source of truth across instances`() = runTest {
        val store = MemoryStore()
        session(store, RegisterTransport()).ensure()
        val raw = JSONObject(store.all()[DeviceSession.KEY_REGISTRATION] as String)
        assertEquals("4175314086266699", raw.getString("device_id"))
        assertEquals("SM-T580", raw.getJSONObject("profile").getString("model"))
        assertEquals("8.1.0", raw.getJSONObject("profile").getString("os_version"))
        // 身份与档案是一起存的：只存 id 会让下次启动用错机型
        assertEquals(DeviceProfile.DEFAULT.osApi, raw.getJSONObject("profile").getInt("os_api"))
    }

    @Test
    fun `identity shape is validated when loading`() = runTest {
        val store = MemoryStore(
            mapOf(
                DeviceSession.KEY_REGISTRATION to JSONObject()
                    .put("device_id", "not-a-number")
                    .put("install_id", "800000000000000002")
                    .put("profile", JSONObject().put("model", "SM-T580"))
                    .toString(),
            ),
        )
        val devices = session(store, RegisterTransport())
        assertNull(devices.snapshot())
        assertEquals(DeviceIdentity("4175314086266699", "4175314086270795"), devices.ensure().identity)
    }
}
