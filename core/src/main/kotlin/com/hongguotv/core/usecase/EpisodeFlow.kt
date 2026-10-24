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

    /**
     * 停住。
     *
     * [seriesEnded] 是"这一集就是整个系列最后一个已上线集"——它与"本季结束"是两件事：
     * 前者要说"已全部播完、没有更新的了"，后者只是过渡到下一季或等更新。
     * 详情页与播放页的文案都按这个事实分叉（`本季已结束` vs `全剧已播完`）。
     */
    data class Stop(val seriesEnded: Boolean = false) : EndAction
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
        currentSeason: Int = 0,
    ): EndAction {
        val safeTotal = total.coerceAtLeast(0)
        if (index < 0 || index >= safeTotal) return EndAction.Stop()
        if (index + 1 < safeTotal) {
            return if (autoNext) EndAction.Play(index + 1) else EndAction.Stop()
        }
        // 已在最后一集：有没有下一季与"连播开关"无关——它决定的是文案，不是去向。
        // 没有季线时只有"单季（季号 0）"才能下"全剧播完"的结论：一个带季号的剧
        // 拿不到季线，只能说明不知道后面还有没有，不能冒充"没有更新的了"。
        if (line == null) return EndAction.Stop(seriesEnded = currentSeason <= 0)
        val next = SeasonChain.next(line, currentSeriesId)
        if (next != null) {
            return if (autoNext) EndAction.NextSeason(next) else EndAction.Stop()
        }
        // 季线里没有本季：说明不了"后面还有没有"，不冒充"全剧播完"
        val known = line.seasons.any { it.id == currentSeriesId }
        return EndAction.Stop(seriesEnded = known)
    }
}
