package com.hongguotv.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 平板档案：注册与签名共用的一份事实，配对关系不能错。 */
class DeviceProfileTest {

    @Test
    fun `every preset pairs the android version with its sdk level`() {
        val expected = mapOf("8.1.0" to 27, "7.1.1" to 25, "7.0" to 24)
        DeviceProfile.TABLETS.forEach { profile ->
            assertEquals("${profile.model} 的 os_version/os_api 必须成对", expected[profile.osVersion], profile.osApi)
            assertTrue("档案必须是 WiFi 平板（API 23+）：${profile.model}", profile.osApi >= 23)
            assertTrue("分辨率写法是 宽x高", profile.resolution.matches(Regex("\\d+x\\d+")))
        }
    }

    @Test
    fun `presets are tablets not phones`() {
        DeviceProfile.TABLETS.forEach { profile ->
            assertEquals("平板固定 pad", "pad", profile.dragonDeviceType)
            assertTrue("档案里不能出现手机型号：${profile.model}", !profile.model.startsWith("25053"))
        }
    }

    @Test
    fun `signing resolution uses the star form`() {
        assertEquals("1920*1200", DeviceProfile.DEFAULT.signingResolution)
    }

    @Test
    fun `profile index wraps instead of crashing on a long-lived device`() {
        assertEquals(DeviceProfile.TABLETS[0], DeviceProfile.of(0))
        assertEquals(DeviceProfile.TABLETS[0], DeviceProfile.of(DeviceProfile.TABLETS.size))
        assertEquals(DeviceProfile.TABLETS[1], DeviceProfile.of(DeviceProfile.TABLETS.size + 1))
        assertEquals(DeviceProfile.TABLETS.last(), DeviceProfile.of(-1))
        assertNotEquals(DeviceProfile.of(0), DeviceProfile.of(1))
    }

    @Test
    fun `user agents carry the tablet model and android version`() {
        val profile = DeviceProfile.DEFAULT
        assertTrue(profile.userAgent.contains(profile.model))
        assertTrue(profile.userAgent.contains(profile.osVersion))
        assertTrue(profile.registrationUserAgent.contains(profile.model))
        assertTrue(profile.registrationUserAgent.contains(profile.osVersion))
        assertTrue("注册 UA 与 body 的版本号一致", profile.registrationUserAgent.contains("73932"))
    }
}
