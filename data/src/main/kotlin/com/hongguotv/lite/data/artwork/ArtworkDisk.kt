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
            // 先写临时文件再改名：中途被杀不会留下半张图（读出来解码失败也是一样的，但少一次坏读）。
            //
            // 临时名**必须带唯一后缀**：文件名只取 URL 的 SHA-1，而工作线程有两个，
            // 同一张封面在不同视口下是不同尺寸、也就成了两个任务（Key 含宽高），
            // 共用 `<name>.tmp` 会让两路字节交错写进同一个文件——写坏的那张 rename 成功之后
            // 永远解不开（has() 只看文件在不在），封面就再也不刷新了。
            val temp = File.createTempFile(file.name, TEMP_SUFFIX, dir)
            temp.writeBytes(stored)
            if (!temp.renameTo(file)) temp.delete()
            trim()
        }
    }

    /**
     * 超上限时按最后修改时间删最旧的一批（各留 10% 余量）：文件数回到
     * `limit - limit/10`，总字节回到 `maxTotalBytes - maxTotalBytes/10`。
     * 一个循环同时看两个口径——老机器上 ≤200 次文件 stat，成本可忽略。
     *
     * 临时文件**不参与配额**（它是正在写的那一张的中间态，按"最后修改时间最旧"淘汰会
     * 删掉正在写的那份），但顺手清掉放了一分钟以上的残留——进程被杀会留下它，
     * 不清就永远占着目录。
     */
    private fun trim() {
        sweepStaleTemps()
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) } ?: return
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

    /** 清掉写盘中途被杀留下的临时文件（一分钟内的可能是刚起的另一路写，别动）。 */
    private fun sweepStaleTemps() {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(TEMP_SUFFIX) && now - file.lastModified() > TEMP_STALE_MS) file.delete()
        }
    }

    internal fun fileOf(url: String): File = File(dir, nameFor(url))

    companion object {
        private const val MAX_FILES = 200

        /** 临时文件后缀：`File.createTempFile` 的产物，配额与淘汰都不看它。 */
        private const val TEMP_SUFFIX = ".tmp"

        /** 临时文件超过这个年龄就是残留（正常写一张图是毫秒级）。 */
        private const val TEMP_STALE_MS = 60_000L

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
