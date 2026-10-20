package com.hongguotv.core.usecase

import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series

/**
 * 一集播完之后该做什么。播放器据此决定"自动连播 / 跨季 / 停住"，
 * **与详情页那句「本季看完后会接着播《X》」同源**（都走 [SeasonChain]），
 * 否则会出现"页面说会接着播、播完却停下"的自相矛盾。
 */
sealed interface EndAction {
    /** 同一季的下一集。 */
    data class Play(val index: Int) : EndAction

    /** 跨季：下一季是另一部作品（另一个 `series_id`），交给 `Navigator` 换路由。 */
    data class NextSeason(val series: Series) : EndAction

    /** 停在"本集已结束"。没有下一集、没有下一季，或用户关了自动连播。 */
    data object Stop : EndAction
}

/**
 * 片尾判定。输入只谈事实（共几集、现在第几集、连播开关、季线），不读任何全局状态，
 * 所以"最后一个季的最后一集"这类边界能直接断言。
 *
 * 未上线季由 [SeasonChain] 排除，这里不重复判断——一条规则只有一处实现。
 */
object EpisodeFlow {

    fun after(
        index: Int,
        total: Int,
        autoNext: Boolean,
        line: SeasonLine?,
        currentSeriesId: String,
    ): EndAction {
        if (!autoNext) return EndAction.Stop
        val safeTotal = total.coerceAtLeast(0)
        if (index < 0 || index >= safeTotal) return EndAction.Stop
        if (index + 1 < safeTotal) return EndAction.Play(index + 1)
        val next = line?.let { SeasonChain.next(it, currentSeriesId) }
        return if (next == null) EndAction.Stop else EndAction.NextSeason(next)
    }
}
