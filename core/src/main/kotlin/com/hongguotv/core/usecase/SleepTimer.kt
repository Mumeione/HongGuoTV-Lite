package com.hongguotv.core.usecase

/**
 * 定时停止。**会话级状态，不进 `Settings`**：它是"这一次观看"的意图，
 * 关掉应用就该失效（旧行为）；写进设置会让用户第二天打开时莫名其妙被停。
 *
 * 时间来源由调用方注入（`System.currentTimeMillis` 或测试里的假钟），
 * 所以"15 分钟后"能被直接断言，不用等 15 分钟。
 *
 * 两种模式互斥：设了分钟数就清掉集数，反之亦然。
 */
class SleepTimer(private val clock: () -> Long) {

    private var deadlineMs: Long? = null

    /** 还剩几集后停止；0 = 没有按集数定时。 */
    var episodesLeft: Int = 0
        private set

    fun cancel() {
        deadlineMs = null
        episodesLeft = 0
    }

    fun afterMinutes(minutes: Int) {
        require(minutes in 1..180) { "定时分钟数应在 1—180 之间，实际 $minutes" }
        cancel()
        deadlineMs = clock() + minutes * 60_000L
    }

    fun afterEpisodes(count: Int) {
        require(count in 1..10) { "定集数应在 1—10 之间，实际 $count" }
        cancel()
        episodesLeft = count
    }

    /** 剩余毫秒；没有按分钟定时时为 null（按集数定时要用 [episodesLeft]）。 */
    val remainingMs: Long?
        get() = deadlineMs?.let { (it - clock()).coerceAtLeast(0) }

    /**
     * 时间到点了吗。**到点即自动取消**：否则每秒一次的轮询会反复触发"定时停止"，
     * 用户按「继续播放」之后又被停一次。
     */
    fun poll(): Boolean {
        val deadline = deadlineMs ?: return false
        if (clock() < deadline) return false
        cancel()
        return true
    }

    /**
     * 一集播完时调用：先看时间是否到点（时间优先），再按集数递减。
     * @return true = 该停止播放了
     */
    fun episodeEnded(): Boolean {
        if (poll()) return true
        if (episodesLeft <= 0) return false
        episodesLeft--
        return episodesLeft == 0
    }
}
