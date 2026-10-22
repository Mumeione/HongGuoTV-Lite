package com.hongguotv.lite.feature.catalog

import com.hongguotv.lite.uikit.ui.RailSlot

/**
 * 目录类的四个页面。顺序与 [RailSlot] 一致（侧栏的视觉顺序），也是 `Navigator.toCatalog(tab)` 的口径。
 *
 * 标签与图标只在 uikit 的 [RailSlot] 里写一份：目录页与详情页都从那里取，
 * 两个 feature 各写一份「收藏」的漂移不会再发生。
 */
enum class CatalogTab {
    SEARCH,
    FAVORITES,
    HISTORY,
    SETTINGS,
    ;

    /** 这一页在侧栏里的位置。 */
    val slot: RailSlot get() = RailSlot.entries[ordinal]

    val label: String get() = slot.label

    companion object {
        /** 越界一律回到搜索页：启动页偏好被改坏时不该崩在启动路径上。 */
        fun of(index: Int): CatalogTab = entries.getOrElse(index) { SEARCH }
    }
}
