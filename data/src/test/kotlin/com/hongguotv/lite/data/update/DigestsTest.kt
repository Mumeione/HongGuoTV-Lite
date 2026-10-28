package com.hongguotv.lite.data.update

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 哈希是更新包四道校验里唯一"不依赖系统解析"的那道：清单写错一个字符、或流式读漏一个字节，
 * 都会表现为"包明明下了却装不上"。所以既要钉住已知向量，也要钉住**流式读与整块读一致**。
 */
class DigestsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun hashesMatchTheKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun theHexFormIsLowercaseAndAlwaysSixtyFourCharacters() {
        val hex = sha256Hex(byteArrayOf(0, 1, 2, 3))

        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
    }

    @Test
    fun anEmptyFileHashesLikeAnEmptyArray() {
        val file = tmp.newFile("empty.bin")

        assertEquals(sha256Hex(ByteArray(0)), sha256Hex(file))
    }

    @Test
    fun theFileFormMatchesTheWholeArrayFormAcrossTheReadBuffer() {
        // 超过 64KB 读缓冲，且不是它的整数倍：最后一块是短的
        val bytes = ByteArray(200_003) { (it % 251).toByte() }
        val file: File = tmp.newFile("update.apk").apply { writeBytes(bytes) }

        assertEquals(sha256Hex(bytes), sha256Hex(file))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).toHex(), sha256Hex(file))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
