package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.usecase.ResumeTarget
import com.hongguotv.core.usecase.SeasonProgress
import com.hongguotv.lite.uikit.text.TvText

/**
 * 两页的文案派生。全部是纯函数（不 import `android.*`），时间/季号/失败文案这类跨 feature 的口径
 * 走 `uikit` 的 [TvText]，这里只写这两个页面独有的句子。
 *
 * 一条硬规矩：**按钮文案必须和落点一致**。按钮写着"继续播放 · 第 9 集"、点进去却是第 1 集，
 * 是最容易让用户失去信任的那种不一致，所以落点由 `ResumePoint` 算、文案由落点派生。
 */
internal object DetailText {

    /** 详情页第二行：集数与分类。上游的 `" · "` 分隔换成 `" / "`，与行内的分隔符区分开。 */
    fun heroMeta(series: Series, episodeCount: Int): String {
        val parts = mutableListOf<String>()
        if (episodeCount > 0) parts += "全 $episodeCount 集" else if (series.badge.isNotBlank()) parts += series.badge
        val tags = series.tags.replace(" · ", " / ")
        if (tags.isNotBlank()) parts += tags
        return parts.joinToString("  ·  ")
    }

    /** 详情页状态行。**有记录就必须说出来**——这是用户回到这一页的理由。 */
    fun heroStatus(progress: Progress?, watched: Boolean): String = TvText.watchStatus(progress, watched)

    /**
     * 系列页状态行：多季剧必须带季名，否则"上次看到第 9 集"分不清是哪一季。
     * [fallbackSeason] 用于记录所属的季不在季线里时（季线缓存可能是旧的）。
     */
    fun seriesStatus(progress: Progress?, line: SeasonLine?, fallbackSeason: Int = 0): String {
        if (progress == null) return ""
        val season = SeasonProgress.seasonOf(progress, line, fallbackSeason)
        return TvText.watchStatus(progress, watched = false, season = season)
    }

    /** 主按钮文案。看 [ResumeTarget] 而不是原始记录：落点挪到下一集时文案也要跟着挪。 */
    fun playLabel(progress: Progress?, target: ResumeTarget, watched: Boolean): String = when {
        watched -> "重新观看"
        progress == null -> "开始观看"
        !target.finished -> "继续播放 · 第 ${target.index + 1} 集"
        // 看完最后一集时落点没有前移：这时是重看本集，不是"下一集"
        target.index > progress.episodeIndex -> "开始下一集"
        else -> "重新播放"
    }

    /** 分页范围：「第 1—100 集」。空区间返回空串（页面据此整行隐藏）。 */
    fun rangeLabel(range: IntRange): String =
        if (range.isEmpty()) "" else "第 ${range.first + 1}—${range.last + 1} 集"

    /**
     * 季线分页范围：按**卡片上的季号**写（「第 33—40 季」），不按下标写。
     * 倒序展示时下标 1—8 对应的是第 40—33 季，按下标写会与卡片对不上。
     * 季号缺失（0）时返回空串，宁可不显示也不写一个错的季号。
     */
    fun seasonRangeLabel(firstSeason: Int, lastSeason: Int): String {
        if (firstSeason <= 0 || lastSeason <= 0) return ""
        return "第 ${minOf(firstSeason, lastSeason)}—${maxOf(firstSeason, lastSeason)} 季"
    }

    /**
     * 下一季说明。没有下一季就返回空串——页面据此整行隐藏，不留一行空文字占位。
     * 真正的连播在 S6 的播放器里，用的是同一个 `SeasonChain`。
     */
    fun nextSeasonHint(next: Series?): String = next?.let { "本季看完后会接着播《${it.title}》" } ?: ""

    /** 季卡状态。看完的季不能停在"上次第 N 集"，那既不准确也没用。 */
    fun seasonState(season: Series, progress: Progress?, watched: Boolean): String = when {
        !season.released -> TvText.season(season.season).let { if (it.isBlank()) "未上线" else "$it · 未上线" }
        progress != null && progress.completed -> "已看完"
        progress != null -> "上次第 ${progress.episodeIndex + 1} 集"
        watched -> "已看完"
        else -> "未观看"
    }

    /** 标题里「第N季 / 第N部」的片段：季卡标题要把季号提出来，篇名留在后面。 */
    private val SEASON_TAG = Regex("第\\s*[0-9０-９一二两三四五六七八九十]{1,3}\\s*[季部]")

    private val TRIM = charArrayOf(' ', '：', ':', '·', '-', '—', '，', ',')

    /**
     * 季卡标题：只写季号（`第九季`），没有季号才退回篇名。
     *
     * 8 列时每张卡只有九十来 dp：写全名会被截成"魏家逆子成首辅第十…"；把季号与篇名
     * 拆成两行又会断在词中间。剧名与篇名在主视觉和详情页都写全了，卡片上只需要
     * 回答"这是第几季"——这一行必须一眼读完。
     */
    fun seasonCardTitle(season: Series, base: String): String {
        var name = season.title
        if (base.isNotBlank() && name.startsWith(base)) name = name.removePrefix(base)
        name = name.trim(*TRIM)
        val tag = SEASON_TAG.find(name)
        val label = TvText.season(season.season).ifEmpty { tag?.value.orEmpty() }
        return label.ifBlank { name.ifBlank { season.title } }
    }

    /** 系列页第二行：收藏身份 + 季数 + 集数 + 分类。 */
    fun seriesMeta(series: Series, seasonCount: Int, favorite: Boolean): String {
        val parts = mutableListOf<String>()
        if (favorite) parts += "收藏的剧"
        if (seasonCount >= 2) parts += "共 $seasonCount 季"
        if (series.badge.isNotBlank()) parts += series.badge
        val tags = series.tags.replace(" · ", "/")
        if (tags.isNotBlank()) parts += tags
        return parts.joinToString("  ·  ")
    }

    /** 季线只在已收藏时才有意义：未收藏的剧不必为空标题写占位。 */
    fun seasonLineTitle(seasonCount: Int): String = if (seasonCount >= 2) "所有季 · 共 $seasonCount 季" else "所有季"

    /** 系列页页脚：解释"换一季不会冲掉别的季的记录"（旧行为，用户常问）。 */
    const val SEASON_FOOTER: String = "各季进度分别保存，继续播放会回到最近看的那一季"
}
