package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.usecase.ResumePoint
import com.hongguotv.core.usecase.SeasonChain
import com.hongguotv.core.usecase.SeasonProgress

/**
 * 系列页「继续播放」的落点与文案。
 *
 * 口径（沿用旧版，且与详情页**逐条一致**）：**有记录就跳回记录的那一季那一集**，哪怕秒数是 0
 * ——"第 1 集 + 00:00"也是一条记录，说成"开始播放"再回落第一季，会和同一行的
 * "上次看到第七季第 1 集"自相矛盾。
 *
 * 「这一集算不算看完」必须用 [ResumePoint.checkpoint] 判，不能只看 `Progress.completed`：
 * 停在片尾 1 秒时 `completed` 仍是 false，详情页会给出"开始下一集"，系列页若说"继续播放第 9 集"
 * 就是两个页面各说一套。
 */
internal data class SeasonTarget(
    /** 要进的那一季；为 null 表示连锚点都没有（页面会显示空态）。 */
    val series: Series?,
    val index: Int,
    val positionMs: Long,
    val label: String,
    /** true = 直接起播；false = 进详情页重新挑集（整季看完、且没有下一季）。 */
    val direct: Boolean,
)

internal object SeasonTargets {

    fun of(line: SeasonLine?, progress: Progress?, fallback: Series?): SeasonTarget {
        val season = SeasonChain.latest(line ?: SeasonLine(emptyList())) ?: fallback
        if (progress == null) return SeasonTarget(season, 0, 0L, "开始播放", direct = true)

        val checkpoint = ResumePoint.checkpoint(progress.positionMs, progress.durationMs, progress.completed)
        val watchedSeason = SeasonProgress.seriesOf(progress, line) ?: fallback

        if (!progress.completed) {
            if (!checkpoint.completed) {
                return SeasonTarget(
                    series = watchedSeason,
                    index = progress.episodeIndex,
                    positionMs = checkpoint.positionMs,
                    label = "继续播放 · 第 ${progress.episodeIndex + 1} 集",
                    direct = true,
                )
            }
            // 这一集看完了，但整季未必：进下一集；越界（本来就是最后一集）由播放器夹住
            return SeasonTarget(
                series = watchedSeason,
                index = progress.episodeIndex + 1,
                positionMs = 0L,
                label = "继续播放 · 第 ${progress.episodeIndex + 2} 集",
                direct = true,
            )
        }

        // 整季看完：有下一季就顺势续上，否则退回详情页重挑
        val next = line?.let { SeasonChain.next(it, progress.seriesId) }
        if (next != null) return SeasonTarget(next, 0, 0L, "继续播放 · 下一季", direct = true)
        return SeasonTarget(watchedSeason, 0, 0L, "重新播放", direct = false)
    }
}
