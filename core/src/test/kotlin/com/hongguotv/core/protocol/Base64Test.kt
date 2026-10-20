package com.hongguotv.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 编解码器的守门测试。
 *
 * 这个类存在的理由：Base64 在签名链里被用到（x-ladon / x-argus、medusa 的载荷），
 * 在解密链里被用到（spade_a 派生内容密钥、key_seed 解播放地址）。它一旦算错，
 * 表现为"所有剧都不能播"，而被替换的动机（支持 Android 6）跟它一点关系都没有——
 * 所以必须有一组不依赖业务上下文的向量把它钉住。
 *
 * 向量取自 RFC 4648 §10，覆盖 0/1/2 字节余数的三种补齐形态。
 */
class Base64Test {

    @Test
    fun encodesRfc4648Vectors() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        assertEquals("Zg==", Base64Codec.encode("f".toByteArray()))
        assertEquals("Zm8=", Base64Codec.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64Codec.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", Base64Codec.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE=", Base64Codec.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Codec.encode("foobar".toByteArray()))
    }

    @Test
    fun decodesRfc4648Vectors() {
        assertEquals("", String(Base64Codec.decode("")))
        assertEquals("f", String(Base64Codec.decode("Zg==")))
        assertEquals("fo", String(Base64Codec.decode("Zm8=")))
        assertEquals("foo", String(Base64Codec.decode("Zm9v")))
        assertEquals("foob", String(Base64Codec.decode("Zm9vYg==")))
        assertEquals("fooba", String(Base64Codec.decode("Zm9vYmE=")))
        assertEquals("foobar", String(Base64Codec.decode("Zm9vYmFy")))
    }

    /** 真实数据里出现的是 URL-safe 变体，且可能没有 padding —— 与旧实现的宽松度保持一致。 */
    @Test
    fun acceptsUrlSafeAlphabetAndMissingPadding() {
        assertEquals("foobar", String(Base64Codec.decode("Zm9vYmFy")))
        // 0xfb 0xff 的 base64 是 "+/8="，URL-safe 写作 "-_8="
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte())
        assertEquals("+/8=", Base64Codec.encode(bytes))
        assertArrayEquals(bytes, Base64Codec.decode("-_8="))
        assertArrayEquals(bytes, Base64Codec.decode("-_8")) // 无 padding
        assertArrayEquals(bytes, Base64Codec.decode("+/8"))
    }

    @Test
    fun roundTripsArbitraryBytes() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(0, 0),
            byteArrayOf(0, 0, 0),
            ByteArray(255) { it.toByte() },
            ByteArray(256) { (it * 7).toByte() },
        )
        samples.forEach { sample ->
            assertArrayEquals("长度 ${sample.size}", sample, Base64Codec.decode(Base64Codec.encode(sample)))
        }
    }

    /** 只有 6 位剩余（长度 % 4 == 1）不是合法 Base64，必须报错而不是返回半截数据。 */
    @Test
    fun rejectsImpossibleLength() {
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("Z") }
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("Zm9vY") }
    }

    @Test
    fun rejectsIllegalCharacters() {
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("Zm9v!") }
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("Zm9v\n") }
    }

    /** padding 只能在末尾且补齐 4 字符组：中段 padding 绝不允许静默截断数据（JDK 严格解码同样抛错）。 */
    @Test
    fun rejectsMisplacedPadding() {
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("Zg==Zm9v") }
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("Zg=") }
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("Z===") }
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("=") }
    }

    /** 签名链里真实用到的输入形态：4 字节时间戳、20 字节哈希块。 */
    @Test
    fun encodesFixedWidthBlocksUsedBySigning() {
        assertEquals("AAAAAA==", Base64Codec.encode(ByteArray(4)))
        assertEquals("/////w==", Base64Codec.encode(byteArrayOf(-1, -1, -1, -1)))
        assertEquals(28, Base64Codec.encode(ByteArray(20)).length) // 20 字节 → 28 字符含 1 个 '='
        assertArrayEquals(ByteArray(20) { it.toByte() }, Base64Codec.decode(Base64Codec.encode(ByteArray(20) { it.toByte() })))
    }
}
