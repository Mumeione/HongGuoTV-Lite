package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.PlaybackOptions
import com.hongguotv.core.model.nextAfter
import com.hongguotv.core.model.UpdateFailure
import com.hongguotv.core.model.UpdateState
import com.hongguotv.core.model.isDownloadFailure
import com.hongguotv.core.usecase.CheckFailure
import com.hongguotv.core.usecase.FavoriteCheckReport
import com.hongguotv.lite.uikit.text.TvText

/**
 * 收藏卡片的角标与状态行。
 *
 * 判据是**季号**不是集数：端口的 `NEW_SEASON` 由"集数增长"派生，而产品口径
 * 是"收藏看的是新季，不是新集"——
 * 短剧一次放全，新季是另一个 `series_id`，只有季线能回答。所以这里有两条判据：
 * 1. 季线最新季号 > 当前季号 → 「第十三季已上线」（最准，优先）；
 * 2. 否则看端口给的检查状态。
 */
internal object FavoriteLabels {

    fun hasNewerSeason(entry: FavoriteEntry): Boolean =
        entry.latestSeason > 0 && entry.latestSeason > entry.series.season

    /**
     * 收藏卡片的角标：直接说"更新到哪里"，不说"已是最新"——
     * 用户要的是"现在到第几季第几集"这个事实，"没变化"不用说（维护者口径）。
     * 句子在 [TvText.updateProgress]，这里只补"还没检查出来"的兜底。
     */
    fun updateBadge(entry: FavoriteEntry): String =
        TvText.updateProgress(entry.latestSeason, entry.latestEpisodes)
            .ifBlank { if (entry.status == FavoriteStatus.FAILED) "检查失败" else "尚未检查更新" }

    /**
     * 收藏卡片的**两行**角标：
     * 「更新到第X季」一行、「第X集」一行——单行在 13.5sp 加粗下放不下完整一句话，
     * 卡上被截成「…第2...」比换行更糟。拆不出两行的（单季、只有季号、兜底句）保持一行。
     * 信息栏里的更新段仍用单行的 [updateBadge]：那里宽度够。
     */
    fun updateBadgeLines(entry: FavoriteEntry): String =
        if (entry.latestSeason > 0 && entry.latestEpisodes > 0) {
            "更新到${TvText.season(entry.latestSeason)}\n第${entry.latestEpisodes}集"
        } else {
            updateBadge(entry)
        }

    /** 只有"有新内容"才需要用户注意（白字加粗）；普通的更新进度是说明文字。 */
    fun attention(entry: FavoriteEntry): Boolean =
        hasNewerSeason(entry) || entry.status == FavoriteStatus.NEW_SEASON

    /** 状态行：没在检查时说什么。[remaining] = 这次没轮到的过期部数（自动检查是分批的）。 */
    fun idleStatus(favoriteCount: Int, remaining: Int): String = when {
        favoriteCount == 0 -> "收藏后可检查有没有新内容"
        remaining == 0 -> "最近 12 小时检查过 · 可手动刷新"
        else -> "「检查更新」看有没有新一季或新集"
    }

    /**
     * 自动检查被全局门槛挡住时的状态行：上一轮 6 小时内刚跑过。
     *
     * 说"刚查过"而不是"没有要查的"——后者会让用户以为收藏真的都新鲜，
     * 而实际是这次根本没发请求；剩下的部数一起说出来，用户才知道还有下一轮。
     */
    fun blockedStatus(remaining: Int): String =
        if (remaining > 0) "刚查过 · 还有 $remaining 部待查" else "最近 6 小时检查过 · 可手动刷新"

    fun progressText(index: Int, total: Int, title: String): String =
        "正在检查 ${index + 1}/$total · ${title.take(14)}"

    /**
     * 状态行：把**本次**的发现与失败并列说出来。
     *
     * 两条 2026-10-28 的口径：
     * 1. **有发现也要说失败**——以前"有新一季"的分支排在"有失败"前面，
     *    2 部有新季 + 5 部失败的检查只报前半句，用户以为全查成功了；
     * 2. **「有新集」是本次增量**（`newEpisodes` 由集数比较得出），不再读历史未读标记，
     *    所以同一句话不会每小时复读一次。
     *
     * [FavoriteCheckReport.remaining] 非 0 时补一句"还有 N 部待查"——自动检查是分批的，
     * 不说清楚会让用户以为只查了这些。
     */
    fun summary(report: FavoriteCheckReport): String = when {
        // 风控：重试一千次也还是被拦，唯一有效的动作是换一台设备——文案直接说那件事
        report.failure == CheckFailure.RISK_CONTROL -> "设备被拒绝 · 按「重新注册」"
        // 断网：用户能做的只有等，别把网络问题说成"检查失败"
        report.failure == CheckFailure.NETWORK && report.checked == 0 -> "网络异常 · 请稍后重试"
        report.failure == CheckFailure.NETWORK -> "网络异常 · 已检查 ${report.checked} 部 · 稍后重试"
        // 全军覆没时先说"失败"，别报成"中断"——用户要知道是网络断了
        report.checked == 0 && report.failed > 0 -> "检查失败 · 稍后联网再试"
        // 连续失败中止：别让用户以为"检查完成"
        report.stoppedEarly -> "检查中断 · 已检查 ${report.checked} 部 · 稍后联网再试"
        else -> "检查完成 · " + summaryParts(report).joinToString(" · ")
    }

    /** 一次成功检查要说的几段：发现 → 失败 → 还没轮到的。 */
    private fun summaryParts(report: FavoriteCheckReport): List<String> = buildList {
        // 两个计数独立累计：一部剧可以同时上新季又给当前季加集，两边都要说出来
        if (report.newSeasons > 0) add("${report.newSeasons} 部有新一季")
        if (report.newEpisodes > 0) add("${report.newEpisodes} 部有新集")
        if (report.newSeasons == 0 && report.newEpisodes == 0 && report.checked > 0) {
            // 措辞跟着句子后面还接不接坏消息走："6 部已是最新 · 1 部失败"读起来是一句话，
            // "6 部都已是最新 · 1 部失败"却是两个结论拼接（"都已"把失败排除在外了）
            add(
                when {
                    report.checked == 1 -> "已是最新"
                    report.failed > 0 -> "${report.checked} 部已是最新"
                    else -> "${report.checked} 部都已是最新"
                },
            )
        }
        if (report.failed > 0) add("${report.failed} 部失败")
        if (report.remaining > 0) add("还有 ${report.remaining} 部待查")
        if (isEmpty()) add("没有要查的")
    }

    /**
     * 「检查更新」那个按钮该写什么。
     *
     * 同一个按钮承担三种状态，不给遥控器多加一个焦点项：
     * - 风控 → 「重新注册」（换设备是唯一能推进的动作，重试一千次还是被拦）；
     * - 断网 → 「重试」（用户能做的只有再试一次）；
     * - 其余 → 「检查更新」。
     * 三种文案都不超过 5 字，定宽胶囊不会因此重排。
     */
    fun checkAction(failure: CheckFailure?): String = when (failure) {
        CheckFailure.RISK_CONTROL -> "重新注册"
        CheckFailure.NETWORK -> "重试"
        else -> "检查更新"
    }
}

/**
 * 设置项的档位与文案。循环切换：档位最多 6 个，比弹选择框少一次按键。
 *
 * 倍速 / 清晰度 / 画面这三条的**档位**来自 `core.model.PlaybackOptions`、
 * **文案**来自 `uikit.TvText`（S6 的播放侧栏是第二个消费者）——这里只留"循环切换"这层页面语义。
 */
internal object SettingRows {

    val qualities = PlaybackOptions.qualities
    val speeds = PlaybackOptions.speeds
    val frames = FrameMode.entries
    val filters = ContentFilter.entries

    /** 下一档；当前值不在档位里（盘上被改坏）时回落到第一档。实现只有一份，见 `core.model.nextAfter`。 */
    fun <T> next(current: T, options: List<T>): T = options.nextAfter(current)

    fun qualityLabel(value: Int): String = TvText.quality(value)

    fun speedLabel(value: Float): String = TvText.speed(value)

    fun frameLabel(mode: FrameMode): String = TvText.frame(mode)

    fun filterLabel(filter: ContentFilter): String = when (filter) {
        ContentFilter.ALL -> "全部"
        ContentFilter.SHORT -> "短剧"
        ContentFilter.COMIC -> "漫剧"
    }

    fun tabLabel(index: Int): String = CatalogTab.of(index).label

    fun sortLabel(ascending: Boolean): String = if (ascending) "正序" else "倒序"

    /**
     * 「检查更新」的说明文字（页头按钮旁，收藏页同款）：状态 → 一句话。
     * 失败**按类型映射**（ARCHITECTURE：运行期失败不匹配中文字符串）；
     * 点击行为按同一状态分派，见 SettingsPage。
     */
    fun updateLabel(state: UpdateState): String = when (state) {
        is UpdateState.Idle -> "自动检查新版本，也可手动检查"
        is UpdateState.Checking -> "正在检查…"
        is UpdateState.UpToDate -> "已是最新"
        is UpdateState.Available -> "新版本 ${state.manifest.versionName} · 按确认下载"
        is UpdateState.Downloading -> if (state.percent < 0) "下载中…" else "下载中 ${state.percent}%"
        is UpdateState.ReadyToInstall -> "按确认安装 ${state.versionName}"
        is UpdateState.Failed -> when (state.failure) {
            UpdateFailure.NONE -> "尚无可用版本"
            UpdateFailure.NETWORK -> "检查失败，请稍后再试"
            UpdateFailure.DOWNLOAD -> "下载失败，请重试"
            UpdateFailure.INCOMPLETE -> "下载不完整，请重试"
            // 问题在包不在网，与"下载失败"分开说；发布方修正清单或附件后重试才能过
            UpdateFailure.VERIFICATION -> "安装包校验未通过，请稍后再试"
        }
    }

    /**
     * 「检查更新」那颗键该写什么。**按钮必须说"按下去会发生什么"**：
     * 有新版时按下去是下载、下载完是安装、下载中是空操作——以前四种行为共用一个
     * 「检查更新」，用户连按三次只看得到右边小字在变。
     *
     * 每个文案都不超过 5 个字：胶囊定宽 [com.hongguotv.lite.uikit.token.Metrics.leadActionWidth]
     * （100dp、13.5sp），换文案时右侧说明不会被推着走。
     */
    fun updateAction(state: UpdateState): String = when (state) {
        is UpdateState.Idle, is UpdateState.UpToDate -> "检查更新"
        is UpdateState.Checking -> "正在检查…"
        is UpdateState.Available -> "下载新版"
        // 百分比只留在说明行：100dp 装不下「下载中 87%」
        is UpdateState.Downloading -> "下载中"
        is UpdateState.ReadyToInstall -> "去安装"
        is UpdateState.Failed -> if (state.failure.isDownloadFailure) "重试下载" else "检查更新"
    }

    /**
     * 手动检查的终止态要弹的那句话；`null` = 还在进行中/空闲，不弹。
     *
     * 为什么按钮变了还要弹一句：检查通常几百毫秒就回来，「正在检查…」一闪而过；
     * 「已是最新」再按一次时按钮从「检查更新」变回「检查更新」，净变化为零——
     * 只有一句 toast 能确定地告诉用户"按下去了、得到了答案"。
     *
     * 文案按**类型**映射（不匹配中文字符串），整句 ≤11 字，Android 12+ 的两行限制装得下。
     */
    fun updateToast(state: UpdateState): String? = when (state) {
        is UpdateState.UpToDate -> "已是最新"
        is UpdateState.Available -> "发现新版本 ${state.manifest.versionName}"
        is UpdateState.ReadyToInstall -> "下载完成，按确认安装"
        is UpdateState.Failed -> when (state.failure) {
            UpdateFailure.NONE -> "尚无可用版本"
            UpdateFailure.NETWORK -> "检查失败，请稍后再试"
            UpdateFailure.DOWNLOAD, UpdateFailure.INCOMPLETE -> "下载失败，请稍后再试"
            UpdateFailure.VERIFICATION -> "安装包校验未通过"
        }
        is UpdateState.Idle, is UpdateState.Checking, is UpdateState.Downloading -> null
    }
}
