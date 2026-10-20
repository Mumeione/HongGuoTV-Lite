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
 */
internal object CatalogCards {

    fun search(series: Series): CardItem = CardItem(
        id = series.id,
        title = series.title,
        coverUrl = series.cover,
        badge = series.badge,
        // 未上线季：暗一档。是否拦点击由页面决定（旧版是点进去再提示，那是产品文案）
        dim = !series.released,
    )

    fun favorite(entry: FavoriteEntry): CardItem = CardItem(
        id = entry.series.id,
        title = entry.series.title,
        coverUrl = entry.series.cover,
        badge = FavoriteLabels.badge(entry),
        badgeAttention = FavoriteLabels.attention(entry),
    )

    /**
     * 历史卡片。角标先说"看完"，再说"看到哪"——
     * 一条 `completed = true` 或手动标记过已看的记录，停在"第 30 集 · 00:00"既没用也不准确。
     */
    fun history(entry: HistoryEntry, watched: Boolean): CardItem {
        val finished = watched || entry.progress.completed
        return CardItem(
            id = entry.series.id,
            title = entry.series.title,
            coverUrl = entry.series.cover,
            badge = if (finished) "整剧已看完" else TvText.progress(entry.progress.episodeIndex, entry.progress.positionMs),
        )
    }
}
