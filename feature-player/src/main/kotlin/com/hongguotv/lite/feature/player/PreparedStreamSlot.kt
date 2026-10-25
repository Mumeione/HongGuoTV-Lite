package com.hongguotv.lite.feature.player

import com.hongguotv.core.port.MediaStream

/**
 * 下一集的预备流槽位：同一时刻只留一条，**谁最后拿到它谁负责关闭**。
 *
 * 流是稀缺资源（占一条连接与一份文件头缓存），所以三条路径都要关：
 * 换目标时 [clear]、被播放器领走时 [take]、超时未用时 [isFresh] 判死后在下一次操作里清掉。
 * 播放器只认识 [MediaStream]，不知道它是不是预取来的。
 */
internal class PreparedStreamSlot(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private var key: String? = null
    private var stream: MediaStream? = null
    private var readyAt = 0L

    /** 这个目标是否有一条还没过期的预备流。 */
    fun isFresh(key: String): Boolean =
        stream != null && this.key == key && clock() - readyAt in 0..ttlMs

    /** 收下一条新流。旧流（无论目标是否相同）先关掉——同一时刻只留一条。 */
    fun offer(key: String, stream: MediaStream) {
        clear()
        this.key = key
        this.stream = stream
        readyAt = clock()
    }

    /**
     * 领走预备流。目标对得上且没过期才给；否则返回 null（顺手把过期的那条关掉）。
     * 调用方拿到之后，流的归属转交给播放器。
     */
    fun take(key: String): MediaStream? {
        if (!isFresh(key)) {
            clear()
            return null
        }
        val taken = stream
        stream = null
        this.key = null
        readyAt = 0L
        return taken
    }

    fun clear() {
        stream?.close()
        stream = null
        key = null
        readyAt = 0L
    }

    private companion object {
        /** 播放地址带时效；预取离使用通常只有十几秒，两分钟是兜底上限（旧实现同值）。 */
        const val DEFAULT_TTL_MS = 120_000L
    }
}
