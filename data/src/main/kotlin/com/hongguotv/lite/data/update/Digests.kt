package com.hongguotv.lite.data.update

import java.io.File
import java.security.MessageDigest

private fun toHex(digest: ByteArray): String =
    digest.joinToString("") { "%02x".format(it) }

/** 字节的 SHA-256 → 小写十六进制。 */
internal fun sha256Hex(bytes: ByteArray): String =
    toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** 流式版本：安装包按块读，不整文件进内存。 */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return toHex(digest.digest())
}
