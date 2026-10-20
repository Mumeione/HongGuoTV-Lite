package com.hongguotv.core.usecase

/**
 * 单集的自动重试预算：最多 3 次，退避 1s → 2.5s → 5s。
 *
 * 两条口径（行为基准：旧仓库 `RecoveryBudget`）：
 * - 预算**按集**计，换一集就重新开始；
 * - `reset()` 只由"用户手动重试"触发——否则"点一下重试"会让自动重试也跟着复活，
 *   变成用户眼里的"永远在重试"。
 *
 * 不自己计时：排期是调用方的事（页面用 Handler，单测用假调度器）。
 */
class RecoveryBudget {

    var attempts: Int = 0
        private set

    /** 下一次重试该等多久（毫秒）；预算用完返回 null。 */
    val nextDelayMs: Long? get() = DELAY_MS.getOrNull(attempts)

    /** 消耗一次预算并返回本次的退避时长；预算用完返回 null。 */
    fun consume(): Long? {
        val delay = nextDelayMs ?: return null
        attempts++
        return delay
    }

    fun reset() {
        attempts = 0
    }

    companion object {
        const val ATTEMPT_LIMIT = 3
        val DELAY_MS = listOf(1_000L, 2_500L, 5_000L)
    }
}
