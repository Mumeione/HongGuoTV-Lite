package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.PlaybackOptions
import com.hongguotv.core.model.nextAfter
import com.hongguotv.core.usecase.FavoriteCheckReport
import com.hongguotv.lite.uikit.text.TvText

/**
 * 收藏卡片的角标与状态行。
 *
 * 判据是**季号**不是集数：端口的 `NEW_SEASON` 由"集数增长"派生，而产品口径
 * （`docs/CONTEXT.md`「收藏更新检查」）是"收藏看的是新季，不是新集"——
 * 短剧一次放全，新季是另一个 `series_id`，只有季线能回答。所以这里有两条判据：
 * 1. 季线最新季号 > 当前季号 → 「第十三季已上线」（最准，优先）；
 * 2. 否则看端口给的检查状态。
 */
internal object FavoriteLabels {

    fun hasNewerSeason(entry: FavoriteEntry): Boolean =
        entry.latestSeason > 0 && entry.latestSeason > entry.series.season

    fun badge(entry: FavoriteEntry): String = when {
        hasNewerSeason(entry) -> "${TvText.season(entry.latestSeason)}已上线"
        entry.status == FavoriteStatus.NEW_SEASON -> "已更新"
        entry.status == FavoriteStatus.UP_TO_DATE -> "已是最新"
        entry.status == FavoriteStatus.FAILED -> "检查失败"
        else -> "尚未检查更新"
    }

    /** 只有"有新内容"才需要用户注意（白字加粗）；"已是最新/尚未检查"是普通说明。 */
    fun attention(entry: FavoriteEntry): Boolean =
        hasNewerSeason(entry) || entry.status == FavoriteStatus.NEW_SEASON

    /** 状态行：没在检查时说什么。 */
    fun idleStatus(favoriteCount: Int, dueCount: Int): String = when {
        favoriteCount == 0 -> "收藏后可检查有没有新一季上线"
        dueCount == 0 -> "最近一小时检查过 · 可手动刷新"
        else -> "「检查更新」看有没有新的一季上线"
    }

    fun progressText(index: Int, total: Int, title: String): String =
        "正在检查 ${index + 1}/$total · ${title.take(14)}"

    fun summary(report: FavoriteCheckReport): String = when {
        // 全军覆没时先说"失败"，别报成"中断"——用户要知道是网络断了
        report.checked == 0 && report.failed > 0 -> "检查失败 · 稍后联网再试"
        // 连续失败中止：别让用户以为"检查完成"
        report.stoppedEarly -> "检查中断 · 已检查 ${report.checked} 部 · 稍后联网再试"
        report.newer > 0 -> "检查完成 · ${report.newer} 部有新一季"
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
}
