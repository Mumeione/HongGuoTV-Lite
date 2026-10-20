package com.hongguotv.lite.feature.catalog

import com.hongguotv.lite.uikit.ui.Icons

/**
 * 目录类的四个页面。顺序即顶栏顺序，也是 `Navigator.toCatalog(tab)` 的口径。
 *
 * 图标跟着 uikit 走：页面只说"用哪个"，不碰 `R`。
 */
enum class CatalogTab(val label: String, val iconRes: Int) {
    SEARCH("搜索", Icons.SEARCH),
    FAVORITES("收藏", Icons.FAVORITE),
    HISTORY("历史", Icons.HISTORY),
    SETTINGS("设置", Icons.SETTINGS),
    ;

    companion object {
        /** 越界一律回到搜索页：启动页偏好被改坏时不该崩在启动路径上。 */
        fun of(index: Int): CatalogTab = entries.getOrElse(index) { SEARCH }
    }
}
