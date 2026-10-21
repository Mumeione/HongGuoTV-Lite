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
     * 搜索结果卡：封面 + 一行收藏状态。没收藏的剧角标留空——
     * 搜索页的职责是"找到剧"，收藏状态是它与收藏页的唯一交集。
     */
    fun search(series: Series, favoriteBadge: String = ""): CardItem = CardItem(
        id = series.id,
        title = "",
        coverUrl = series.cover,
        badge = favoriteBadge,
        // 未上线季：暗一档。是否拦点击由页面决定（旧版是点进去再提示，那是产品文案）
        dim = !series.released,
    )

    /** 收藏卡：封面 + 更新进度（`更新到第九季 · 20集`）。 */
    fun favorite(entry: FavoriteEntry): CardItem = CardItem(
        id = entry.series.id,
        title = "",
        coverUrl = entry.series.cover,
        badge = FavoriteLabels.updateBadge(entry),
        badgeAttention = FavoriteLabels.attention(entry),
    )

    /**
     * 历史卡片：封面 + 观看位置。角标先说"看完"，再说"看到哪"——
     * 一条 `completed = true` 或手动标记过已看的记录，停在"第 30 集 · 00:00"既没用也不准确。
     *
     * [withSeason] 给首页的「继续看」用：那里没有预览区兜底季名，多季剧必须自己带上
     * `第九季 · 第 20 集 · 05:00`；历史页有预览区，保持一行短句。
     */
    fun history(entry: HistoryEntry, watched: Boolean, withSeason: Boolean = false): CardItem {
        val finished = watched || entry.progress.completed
        val position = if (finished) "整剧已看完" else TvText.progress(entry.progress.episodeIndex, entry.progress.positionMs)
        val season = TvText.season(entry.series.season)
        return CardItem(
            id = entry.series.id,
            title = "",
            coverUrl = entry.series.cover,
            badge = if (withSeason && season.isNotBlank() && !finished) "$season · $position" else position,
        )
    }
}
