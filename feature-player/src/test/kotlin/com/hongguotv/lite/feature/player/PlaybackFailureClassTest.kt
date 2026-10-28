package com.hongguotv.lite.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误码分类决定"要不要自动重试"：解码类重试没有意义，其余过一会儿可能好。
 * 这里只钉区间边界——区间错一位，界面上就会出现"解不了的编码被自动重试三次"。
 */
class PlaybackFailureClassTest {

    @Test
    fun decodingCodesAreInRange() {
        assertTrue(isDecodeFailure(3_000))
        assertTrue(isDecodeFailure(4_000))
        assertTrue(isDecodeFailure(4_999))
    }

    @Test
    fun networkAndIoFailuresAreNotDecodingFailures() {
        assertFalse(isDecodeFailure(1))
        assertFalse(isDecodeFailure(2_000))
        assertFalse(isDecodeFailure(2_999))
        assertFalse(isDecodeFailure(5_000))
    }

    @Test
    fun anUnsetOrUnknownCodeIsNotADecodingFailure() {
        // media3 在拿不到具体错误时会给 0 / 负数，不能当成解码失败（那会直接掐掉重试）
        assertFalse(isDecodeFailure(0))
        assertFalse(isDecodeFailure(-1))
    }
}
