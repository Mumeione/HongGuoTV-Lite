package com.hongguotv.lite.feature.detail

import com.hongguotv.core.model.FavoriteLimits
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.lite.uikit.text.TvText

/**
 * 收藏快满时的一句提醒：**已满且这一部还没收藏**才返一句话，其余情况 null。
 *
 * 写入口（`PrefsLibrary.toggle`）超限时按 `addedAt` 淘汰最旧的一条，没有任何返回值；
 * 不在这里提醒，用户就是在不知情下丢掉一部收藏。文案与截断规则收在 [TvText]，
 * 两个详情页共用这一份判断，免得一处提醒、一处不提醒。
 */
internal fun favoriteCapacityWarning(favorites: FavoritesPort): String? {
    val all = favorites.all()
    val oldest = all.minByOrNull { it.addedAt }?.series?.title
    return capacityWarning(all.size, oldest)
}

/**
 * 「是否要提醒」的纯判断：够满、且知道要淘汰哪一部时才提醒。
 *
 * 单独成函数是为了能在 JVM 单测里钉住边界（99 部不提醒、第 100 部才提醒）；
 * 端口读取与文案截断留在 [favoriteCapacityWarning]。
 */
internal fun capacityWarning(count: Int, oldestTitle: String?): String? {
    if (count < FavoriteLimits.CAPACITY || oldestTitle == null) return null
    return TvText.favoriteCapacityNotice(oldestTitle)
}
