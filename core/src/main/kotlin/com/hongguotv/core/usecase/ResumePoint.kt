package com.hongguotv.core.usecase

import com.hongguotv.core.model.Episode
import com.hongguotv.core.model.Progress

/** 续播落点：从第几集、第几秒起播，以及"这一集算不算已经看完"。 */
data class ResumeTarget(
    val index: Int,
    val positionMs: Long,
    /** true = 记录的那一集已经看完，落点已经挪到下一集。 */
    val finished: Boolean,
)

/** 归一化后的断点：只回答"从第几秒开始播"和"算不算看完"。 */
data class Checkpoint(val positionMs: Long, val completed: Boolean)

/**
 * 断点续播的判定（行为基准：旧仓库 `ResumePlayback`）。
 *
 * 两条口径容易写错，写在类型里而不是注释里：
 * - **有记录就必须显示「继续播放」**：位置归一化只影响"从第几秒开始播"，不代表"没有记录"。
 * - **距片尾 3 秒算看完**：一集停在片尾 1 秒再点进去，用户要的是下一集，不是重看最后两秒。
 */
object ResumePoint {

    /** 距片尾多少毫秒内算看完。 */
    const val NEAR_END_MS = 3_000L

    /** 小于这个位置视为没开始（"续播 2 秒"没有意义）。 */
    const val START_FLOOR_MS = 5_000L

    fun checkpoint(positionMs: Long, durationMs: Long, completed: Boolean): Checkpoint {
        val clean = positionMs.coerceAtLeast(0)
        if (completed || (durationMs > 0 && clean >= durationMs - NEAR_END_MS)) return Checkpoint(clean, true)
        return Checkpoint(if (clean < START_FLOOR_MS) 0L else clean, false)
    }

    /**
     * 落点。分集清单为空、或记录里的 vid 已经不在清单里（上游换了集）时回落到第一集第 0 秒——
     * 不猜"最接近的那一集"，猜错比从头开始更让用户困惑。
     */
    fun of(episodes: List<Episode>, progress: Progress?): ResumeTarget {
        if (episodes.isEmpty() || progress == null) return ResumeTarget(0, 0L, false)
        val index = episodes.indexOfFirst { it.vid == progress.episodeVid }
        if (index < 0) return ResumeTarget(0, 0L, false)
        val checkpoint = checkpoint(progress.positionMs, progress.durationMs, progress.completed)
        return if (checkpoint.completed) {
            ResumeTarget((index + 1).coerceAtMost(episodes.lastIndex), 0L, true)
        } else {
            ResumeTarget(index, checkpoint.positionMs, false)
        }
    }
}
