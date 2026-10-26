package com.hongguotv.lite.data.artwork

import java.io.File
import java.security.MessageDigest

/**
 * 封面落盘（只有收藏 / 历史的封面才用，见 `ArtworkSpec.persist`）。
 *
 * 两个粗上限（V1.0.0 容量治理）：文件数 [limit]（200）与总字节 [maxTotalBytes]（30MB），
 * 超出时按最后修改时间删最旧（各留 10% 余量，别每写一张就删一次）。
 * 没有数据库、没有索引——封面丢了只是重下一次。
 *
 * [reencode] 在写盘前把原图字节重编码成小图（ArtworkLoader 给真实实现：512px/JPEG）；
 * 返回 null 表示"编不动/编完不划算"，原样存原图字节。失败重试都不做——封面丢了重下。
 *
 * 文件名用 URL 的 SHA-1：URL 带 `?`、`/`、中文，直接当文件名要么非法要么撞名。
 */
class ArtworkDisk(
    private val dir: File,
    private val limit: Int = MAX_FILES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val reencode: (ByteArray) -> ByteArray? = { null },
) {

    /** 命中判断只看文件在不在——封面解码失败也无所谓，下次重新下。 */
    fun has(url: String): Boolean = fileOf(url).isFile

    fun get(url: String): ByteArray? {
        val file = fileOf(url)
        return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
    }

    fun put(url: String, bytes: ByteArray) {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val stored = runCatching { reencode(bytes) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size < bytes.size }
                ?: bytes
            val file = fileOf(url)
            // 先写临时文件再改名：中途被杀不会留下半张图（读出来解码失败也是一样的，但少一次坏读）
            val temp = File(dir, file.name + ".tmp")
            temp.writeBytes(stored)
            if (!temp.renameTo(file)) temp.delete()
            trim()
        }
    }

    /**
     * 超上限时按最后修改时间删最旧的一批（各留 10% 余量）：文件数回到
     * `limit - limit/10`，总字节回到 `maxTotalBytes - maxTotalBytes/10`。
     * 一个循环同时看两个口径——老机器上 ≤200 次文件 stat，成本可忽略。
     */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { runCatching { it.length() }.getOrDefault(0L) }
        if (files.size <= limit && total <= maxTotalBytes) return
        val keepFiles = limit - limit / 10
        val keepBytes = maxTotalBytes - maxTotalBytes / 10
        var count = files.size
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (count <= keepFiles && total <= keepBytes) return
            val size = runCatching { file.length() }.getOrDefault(0L)
            if (file.delete()) {
                count--
                total -= size
            }
        }
    }

    internal fun fileOf(url: String): File = File(dir, nameFor(url))

    companion object {
        private const val MAX_FILES = 200

        /** 磁盘总量上限：200 张压缩后（~30-80KB/张）正常到不了，这是防上游下发大图的兜底。 */
        const val MAX_TOTAL_BYTES = 30L * 1024 * 1024

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
