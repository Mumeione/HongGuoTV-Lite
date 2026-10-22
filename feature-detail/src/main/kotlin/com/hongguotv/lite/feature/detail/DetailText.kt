package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.usecase.ResumeTarget
import com.hongguotv.core.usecase.SeasonProgress
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.ui.SpecRow

/**
 * 两页的文案派生。全部是纯函数（不 import `android.*`），时间/季号/失败文案这类跨 feature 的口径
 * 走 `uikit` 的 [TvText]，这里只写这两个页面独有的句子。
 *
 * 一条硬规矩：**按钮文案必须和落点一致**。按钮写着"继续播放 · 第 9 集"、点进去却是第 1 集，
 * 是最容易让用户失去信任的那种不一致，所以落点由 `ResumePoint` 算、文案由落点派生。
 *
 * B 方案：头部信息从"两行拼接"改成**四行规格表**（更新 / 集数·季数 / 分类 / 记录），
 * 「记录」一行是 strong——它是用户回到这一页的理由。哪一行存在、值是什么，全部在这里派生，
 * 页面只负责把 `List<SpecRow>` 交给 `SpecTable`。
 */
internal object DetailText {

    /**
     * 片名副行（「共 10 季」/「共 10 季 · 9 季已上线」）。
     * [withReleased] 给收藏详情页用（它关心有几季还没上线）；剧集详情页只写季数。
     * 单季剧返回空串——季号已经在标题里，再写"共 1 季"是废话。
     */
    fun seasonNote(seasonCount: Int, releasedCount: Int, withReleased: Boolean): String {
        if (seasonCount < 2) return ""
        val unreleased = seasonCount - releasedCount
        val base = "共 $seasonCount 季"
        return if (withReleased && unreleased > 0) "$base · $releasedCount 季已上线" else base
    }

    /**
     * 剧集详情的规格表：更新 / 集数 / 分类 / 记录。
     *
     * [update] 是"这一季现在到第几集"（调用方用集数总数拼「更新到 第 N 集」）；
     * 空值整行去掉，不画"记录：（空）"这种行。
     */
    fun episodeSpecs(update: String, total: Int, watchedCount: Int, tags: String, record: String): List<SpecRow> {
        val episodeValue = buildString {
            if (total > 0) append("全 $total 集")
            if (watchedCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("已看 $watchedCount 集")
            }
        }
        return listOf(
            SpecRow("更新", update),
            SpecRow("集数", episodeValue),
            SpecRow("分类", tags),
            SpecRow("记录", record, strong = true),
        ).filter { it.value.isNotBlank() }
    }

    /**
     * 收藏详情的规格表：更新 / 季数 / 分类 / 记录。
     * 季数行的后半句是产品口径：收藏的是**整个系列**，进度按季各存各的。
     */
    fun seasonSpecs(update: String, seasonCount: Int, tags: String, record: String): List<SpecRow> {
        val seasonValue = if (seasonCount > 0) "$seasonCount 季 · 收藏整个系列" else ""
        return listOf(
            SpecRow("更新", update),
            SpecRow("季数", seasonValue),
            SpecRow("分类", tags),
            SpecRow("记录", record, strong = true),
        ).filter { it.value.isNotBlank() }
    }

    /** 剧集详情的「记录」值：**有记录就必须说出来**——这是用户回到这一页的理由。手动标"已看"优先。 */
    fun episodeRecord(progress: Progress?, watched: Boolean): String = when {
        watched -> "已看完"
        progress == null -> ""
        else -> TvText.progress(progress.episodeIndex, progress.positionMs)
    }

    /** 系列页的「记录」值：多季剧必须带季名，否则"第 20 集"分不清是哪一季的。 */
    fun seasonRecord(progress: Progress?, line: SeasonLine?, fallbackSeason: Int): String {
        if (progress == null) return ""
        val season = SeasonProgress.seasonOf(progress, line, fallbackSeason)
        return listOf(TvText.season(season), TvText.progress(progress.episodeIndex, progress.positionMs))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

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

    /** 季线只在已收藏时才有意义：未收藏的剧不必为空标题写占位。 */
    fun seasonLineTitle(seasonCount: Int): String = if (seasonCount >= 2) "所有季 · 共 $seasonCount 季" else "所有季"

    /** 系列页右栏说明：解释"换一季不会冲掉别的季的记录"（旧行为，用户常问）。逗号后分行——一句两个主语挤一行读起来费劲。 */
    const val SEASON_FOOTER: String = "各季进度分别保存，\n继续播放会回到最近看的那一季"
}
