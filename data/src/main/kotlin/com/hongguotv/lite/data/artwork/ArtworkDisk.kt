package com.hongguotv.lite.data.artwork

import java.io.File
import java.security.MessageDigest

/**
 * 封面落盘（只有收藏 / 历史的封面才用，见 `ArtworkSpec.persist`）。
 *
 * 只写不删的简单策略 + 一个粗上限：上限 200 个文件，超出时按最后修改时间删到 180
 * （留 10% 余量，别每写一张就删一次）。没有数据库、没有索引——封面丢了只是重下一次。
 *
 * 文件名用 URL 的 SHA-1：URL 带 `?`、`/`、中文，直接当文件名要么非法要么撞名。
 */
class ArtworkDisk(private val dir: File, private val limit: Int = MAX_FILES) {

    /** 命中判断只看文件在不在——封面解码失败也无所谓，下次重新下。 */
    fun has(url: String): Boolean = fileOf(url).isFile

    fun get(url: String): ByteArray? {
        val file = fileOf(url)
        return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
    }

    fun put(url: String, bytes: ByteArray) {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val file = fileOf(url)
            // 先写临时文件再改名：中途被杀不会留下半张图（读出来解码失败也是一样的，但少一次坏读）
            val temp = File(dir, file.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
            trim()
        }
    }

    /** 超过上限时按最后修改时间删掉最旧的一批（留出 10% 余量，别每写一张都删一次）。 */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= limit) return
        val drop = files.size - limit + limit / 10
        files.sortedBy { it.lastModified() }.take(drop).forEach { it.delete() }
    }

    internal fun fileOf(url: String): File = File(dir, nameFor(url))

    companion object {
        private const val MAX_FILES = 200

        /** URL → 文件名。纯函数，单测钉住"稳定 + 不含路径分隔符"。 */
        fun nameFor(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return hex.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
