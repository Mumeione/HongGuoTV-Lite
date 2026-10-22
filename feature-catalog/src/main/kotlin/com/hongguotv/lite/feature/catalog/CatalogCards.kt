package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.model.Series
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.ui.CardItem

/**
 * 领域对象 → 卡片内容。三个页面共用一套角标规则，避免"同一部剧在搜索页和历史页角标不一致"。
 *
 * 卡片只收字符串（`CardItem` 的约定）：季、季键、系列这些词汇留在 feature 与 core。
 *
 * 一条 2026-10-21 的维护者口径：**卡片下方不再显示片名**——片名由聚焦时上方的大字预览区说，
 * 卡上只留一行"这个页面负责的状态"，每页完成好自己的功能：
 * 搜索 = 收藏/更新状态；收藏 = 更新进度；历史/继续看 = 观看位置。
 */
internal object CatalogCards {

    /**
     * 搜索结果卡：封面 + 一行集数（B 方案，mockup 画面2）——
     * 未上线写「未上线」，其余写「全 N 集」；收藏状态在底部信息栏说，不占卡片。
     */
    fun search(series: Series): CardItem = CardItem(
        id = series.id,
        title = "",
        coverUrl = series.cover,
        badge = when {
            !series.released -> "未上线"
            series.episodeCount > 0 -> "全 ${series.episodeCount} 集"
            else -> ""
        },
        // 未上线季：暗一档。是否拦点击由页面决定（旧版是点进去再提示，那是产品文案）
        dim = !series.released,
        artworkTitle = series.title,
    )

    /** 收藏卡：封面 + 更新进度（`更新到第九季第108集`）。 */
    fun favorite(entry: FavoriteEntry): CardItem = CardItem(
        id = entry.series.id,
        title = "",
        coverUrl = entry.series.cover,
        badge = FavoriteLabels.updateBadge(entry),
        badgeAttention = FavoriteLabels.attention(entry),
        artworkTitle = entry.series.title,
    )

    /**
     * 历史卡片：封面 + 观看位置（`第九季 第20集`）。角标先说"看完"，再说"看到哪"——
     * 一条 `completed = true` 或手动标记过已看的记录，停在"第 30 集"既没用也不准确。
     *
     * B 方案：**不带时间**（mockup 画面4）——时间在详情页/信息栏里有需要时再看；
     * 季与集之间一个空格，季号**总是**带上：多季剧的历史记录散在各季里，
     * 不写季名就分不清看的是哪一季。
     */
    fun history(entry: HistoryEntry, watched: Boolean): CardItem {
        val finished = watched || entry.progress.completed
        return CardItem(
            id = entry.series.id,
            title = "",
            coverUrl = entry.series.cover,
            badge = if (finished) "整剧已看完"
            else TvText.cardProgress(entry.series.season, entry.progress.episodeIndex),
            artworkTitle = entry.series.title,
        )
    }
}
