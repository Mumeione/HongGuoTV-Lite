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
     * 剧集详情页「检查更新」的三种结局。
     *
     * 三态收成一个类型（而不是"两个参数 + 一个失败常量"）：页面只负责把季线换算成其中一个，
     * 用户看到的那句话全部由 [checkStatus] 派生，测试也只用钉这一处。
     */
    sealed interface CheckFinding {
        /** 季线拿到了，没有更新的季：这一季就是线上最新的。 */
        data class Latest(val episodes: Int) : CheckFinding

        /** 季线拿到了，且有更新的季已上线。 */
        data class NewSeason(val season: Int) : CheckFinding

        /** 季线没拿到（风控 / 网络 / 结构变化）：都收成"稍后重试"，重试入口就是那颗键本身。 */
        data object Failed : CheckFinding
    }

    /**
     * 剧集详情页「检查更新」的结果行。
     *
     * 只回答两个问题：有没有更新的季、这一季共多少集。当前季号未知（0）时页面不会构造
     * [CheckFinding.NewSeason]——拿 0 去比会把任何一条季线都报成"新季"。
     */
    fun checkStatus(finding: CheckFinding): String = when (finding) {
        is CheckFinding.Latest -> "已是最新 · 共 ${finding.episodes} 集"
        is CheckFinding.NewSeason -> "${TvText.season(finding.season)}已上线"
        CheckFinding.Failed -> "检查失败 · 稍后重试"
    }

    /**
     * 选季页「检查更新」的结果行。
     *
     * 多季剧关心的是"有没有新一季"，「已是最新」因此报季数而不是集数——
     * 这一页上没有"当前季多少集"的上下文，写「共 104 集」用户不知道是哪一季的。
     */
    fun seasonCheckStatus(finding: CheckFinding, seasonCount: Int): String = when (finding) {
        is CheckFinding.Latest -> "已是最新 · 共 $seasonCount 季"
        is CheckFinding.NewSeason -> checkStatus(finding)
        CheckFinding.Failed -> checkStatus(finding)
    }

    /**
     * 季线 → 三态（[checkStatus] 的输入）。
     *
     * 两条判据：
     * - 未上线季不算"已上线"：季线里给它留着占位，但用户点不进去；
     * - **当前季号未知（0）时不做季号比较**——拿 0 去比会把任何一条季线都报成"新季"。
     */
    fun checkFinding(line: SeasonLine, series: Series, episodes: Int): CheckFinding {
        val latest = line.seasons.filter { it.released }.maxOfOrNull { it.season } ?: 0
        return if (series.season > 0 && latest > series.season) {
            CheckFinding.NewSeason(latest)
        } else {
            CheckFinding.Latest(episodes)
        }
    }

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
     *
     * 「季数」一行只写**已收藏整个系列**（2026-10-24 验收）：这一行要回答的是"收藏了什么"，
     * 季数在片名下的副行已经说过（「共 37 季 · 36 季已上线」），再写一遍是重复。
     */
    fun seasonSpecs(update: String, tags: String, record: String): List<SpecRow> = listOf(
        SpecRow("更新", update),
        SpecRow("季数", COLLECT_WHOLE_SERIES),
        SpecRow("分类", tags),
        SpecRow("记录", record, strong = true),
    ).filter { it.value.isNotBlank() }

    /** 收藏页规格表的「季数」值：收藏的是整个系列，进度按季各存各的。 */
    const val COLLECT_WHOLE_SERIES: String = "已收藏整个系列"

    /**
     * 剧集详情的「更新」值：这一季现在到第几集。空剧集返回空串（整行去掉）。
     *
     * 放在这里而不是调用点拼：同一句话在详情页、收藏页、信息栏各出现一次，
     * 空格与"第 N 集 / 第 N 集"的写法必须只有一份。
     */
    fun updateLabel(episodeCount: Int): String =
        if (episodeCount <= 0) "" else "更新到 第 $episodeCount 集"

    /** 剧集详情的「记录」值：**有记录就必须说出来**——这是用户回到这一页的理由。 */
    fun episodeRecord(progress: Progress?): String =
        progress?.let { TvText.progress(it.episodeIndex, it.positionMs) }.orEmpty()

    /** 系列页的「记录」值：多季剧必须带季名，否则"第 20 集"分不清是哪一季的。 */
    fun seasonRecord(progress: Progress?, line: SeasonLine?, fallbackSeason: Int): String {
        if (progress == null) return ""
        val season = SeasonProgress.seasonOf(progress, line, fallbackSeason)
        return listOf(TvText.season(season), TvText.progress(progress.episodeIndex, progress.positionMs))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /**
     * 主按钮的两行：动作 + 集数。
     *
     * 「继续播放 · 第 100 集」在 172dp 的胶囊里会折成「…第 100 / 集」——断在量词上。
     * 文案仍由 [playLabel] / `SeasonTarget.label` 统一派生（"按钮说什么"只有一份事实），
     * 这里只负责把它拆成"动作"与"第二行小字"两截。
     */
    fun playLabelLines(label: String): Pair<String, String> {
        val parts = label.split(" · ", limit = 2)
        return parts[0] to parts.getOrElse(1) { "" }
    }

    /** 主按钮文案。看 [ResumeTarget] 而不是原始记录：落点挪到下一集时文案也要跟着挪。 */
    fun playLabel(progress: Progress?, target: ResumeTarget): String = when {
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
    fun seasonState(season: Series, progress: Progress?): String = when {
        // 季卡第一行已经是季号（「第十季」），第二行只补状态（2026-10-24 验收：别重复）
        !season.released -> "未上线"
        progress != null && progress.completed -> "已看完"
        progress != null -> "上次第 ${progress.episodeIndex + 1} 集"
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

    /**
     * 系列页右栏说明：解释"换一季不会冲掉别的季的记录"（旧行为，用户常问）。
     * 一行显示（2026-10-24 口径）：说明句不是正文，拆两行会把信息块顶出封面底。
     */
    const val SEASON_FOOTER: String = "各季进度分别保存，继续播放会回到最近看的那一季"
}
