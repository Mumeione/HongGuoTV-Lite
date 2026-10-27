package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.PlaybackOptions
import com.hongguotv.core.model.nextAfter
import com.hongguotv.core.model.UpdateFailure
import com.hongguotv.core.model.UpdateState
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

    /** 状态行：没在检查时说什么。 */
    fun idleStatus(favoriteCount: Int, dueCount: Int): String = when {
        favoriteCount == 0 -> "收藏后可检查有没有新内容"
        dueCount == 0 -> "最近一小时检查过 · 可手动刷新"
        else -> "「检查更新」看有没有新一季或新集"
    }

    fun progressText(index: Int, total: Int, title: String): String =
        "正在检查 ${index + 1}/$total · ${title.take(14)}"

    fun summary(report: FavoriteCheckReport): String = when {
        // 全军覆没时先说"失败"，别报成"中断"——用户要知道是网络断了
        report.checked == 0 && report.failed > 0 -> "检查失败 · 稍后联网再试"
        // 连续失败中止：别让用户以为"检查完成"
        report.stoppedEarly -> "检查中断 · 已检查 ${report.checked} 部 · 稍后联网再试"
        report.newSeasons > 0 && report.newEpisodes > 0 ->
            "检查完成 · ${report.newSeasons} 部有新一季 · ${report.newEpisodes} 部有新集"
        report.newSeasons > 0 -> "检查完成 · ${report.newSeasons} 部有新一季"
        // 单季短剧的日常更新：只加集、没有新季，也要如实说出来
        report.newEpisodes > 0 -> "检查完成 · ${report.newEpisodes} 部有新集"
        report.failed > 0 -> "检查完成 · ${report.checked} 部已是最新 · ${report.failed} 部失败"
        report.checked == 1 -> "检查完成 · 已是最新"
        else -> "检查完成 · ${report.checked} 部都已是最新"
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
        is UpdateState.Available -> "新版本 ${state.manifest.versionName} · 按确认更新"
        is UpdateState.Downloading -> if (state.percent < 0) "下载中…" else "下载中 ${state.percent}%"
        is UpdateState.ReadyToInstall -> "按确认安装 ${state.versionName}"
        is UpdateState.Failed -> when (state.failure) {
            UpdateFailure.NONE -> "尚无可用版本"
            UpdateFailure.NETWORK -> "检查失败，请稍后再试"
            UpdateFailure.DOWNLOAD -> "下载失败，请重试"
            UpdateFailure.INCOMPLETE -> "下载不完整，请重试"
        }
    }
}
