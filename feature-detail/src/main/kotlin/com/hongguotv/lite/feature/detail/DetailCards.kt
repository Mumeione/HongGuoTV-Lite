package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.Series
import com.hongguotv.lite.uikit.ui.CardItem

/**
 * 领域对象 → 卡片内容。
 *
 * 卡片只收字符串（`CardItem` 的约定）：季、季键、系列这些词汇留在 feature 与 core。
 * 季线也用目录页那张海报卡——同一个东西（一部作品）不该有两种卡片样子。
 */
internal object DetailCards {

    fun season(season: Series, base: String, current: Boolean, progress: Progress?, watched: Boolean): CardItem = CardItem(
        id = season.id,
        title = DetailText.seasonCardTitle(season, base),
        coverUrl = season.cover,
        badge = DetailText.seasonState(season, progress, watched),
        dim = !season.released,
        // "当前"写在封面上角：标题只有两行，塞进标题里会挤掉季名
        corner = if (current) "当前" else "",
    )
}
