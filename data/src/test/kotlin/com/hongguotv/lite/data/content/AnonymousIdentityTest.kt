package com.hongguotv.lite.data.content

import com.hongguotv.lite.data.library.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 匿名身份只落盘一次：同一台电视不该每次启动都变成"新设备"。 */
class AnonymousIdentityTest {

    private class MemoryStore(private val values: MutableMap<String, Any?> = mutableMapOf()) : KeyValueStore {
        override fun all(): Map<String, Any?> = values.toMap()
        override fun commit(values: Map<String, Any?>) {
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
        }
    }

    @Test
    fun firstCallGeneratesAndPersists() {
        val store = MemoryStore()
        val identity = AnonymousIdentity.of(store)
        assertTrue(Regex("\\d{16}").matches(identity.deviceId))
        assertEquals(identity.deviceId, store.all()["anon-device-id"])
        assertEquals(identity.installId, store.all()["anon-install-id"])
    }

    @Test
    fun secondCallReusesThePersistedPair() {
        val store = MemoryStore()
        assertEquals(AnonymousIdentity.of(store), AnonymousIdentity.of(store))
    }

    @Test
    fun eachStoreGetsItsOwnIdentity() {
        assertNotEquals(AnonymousIdentity.of(MemoryStore()), AnonymousIdentity.of(MemoryStore()))
    }

    @Test
    fun corruptedValuesAreReplacedInsteadOfFailingEveryRequest() {
        val store = MemoryStore(mutableMapOf("anon-device-id" to "abc", "anon-install-id" to "def"))
        val identity = AnonymousIdentity.of(store)
        assertTrue(Regex("\\d{16}").matches(identity.deviceId))
        assertNotEquals("abc", store.all()["anon-device-id"])
    }
}
