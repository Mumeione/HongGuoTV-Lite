package com.hongguotv.core.usecase

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.FavoritesPort

/**
 * "这一季属于哪个收藏系列"。搜索页、历史页、剧集详情页都要回答它，规则只能有一份。
 *
 * 两个证据，按可靠度：
 * 1. **标题算出的系列键**（去季号归一化）——绝大多数情况够用；
 * 2. **季线里的 id 归属**——分宗篇这类标题不带「第N季」的锚点，自己算出的键是它本身，
 *    与《魏家逆子成首辅》的收藏行永远对不上（维护者实测：已收藏却不显示「已收藏」）。
 *
 * 第二个证据要遍历收藏的季线缓存（[FavoritesPort.seasonLine]）。全是内存查表，不发请求。
 * 页面用收 [FavoritesPort] 的那个重载，不要在调用点拼 lambda——同一段拼接在五个地方出现过。
 */
object FavoriteLookup {

    /** 这一季所属的收藏行；没收藏返回 null。 */
    fun entry(series: Series, favorites: FavoritesPort): FavoriteEntry? =
        entry(series, favorites.all()) { favorites.seasonLine(it) }

    fun entry(
        series: Series,
        entries: List<FavoriteEntry>,
        lineOf: (String) -> SeasonLine?,
    ): FavoriteEntry? {
        val key = SeasonKeys.seasonKey(series.title)
        entries.firstOrNull { it.seriesKey == key }?.let { return it }
        // 键对不上：看这一季的 id 在不在某个收藏系列的季线里
        return entries.firstOrNull { entry ->
            lineOf(entry.seriesKey)?.seasons?.any { it.id == series.id } == true
        }
    }

    /**
     * 收藏/取消收藏该用哪个系列键。
     *
     * 已收藏 → 用它的键（取消的就是那一条）；没收藏 → 用标题算出来的键（新开一条）。
     * 不要把"没收藏但季线里有同名系列"也合并进来：那会把同 IP 的不同作品并成一条。
     */
    fun favoriteKey(series: Series, favorites: FavoritesPort): String =
        favoriteKey(series, favorites.all()) { favorites.seasonLine(it) }

    fun favoriteKey(
        series: Series,
        entries: List<FavoriteEntry>,
        lineOf: (String) -> SeasonLine?,
    ): String = entry(series, entries, lineOf)?.seriesKey ?: SeasonKeys.seasonKey(series.title)

    /**
     * 这一季在季线缓存里的分类（`category_schema`）。
     *
     * 历史行与搜索卡片有时只带 id 或不带分类；收藏过的系列季线里有一份完整元数据。
     * 按 id 找具体那一季，不拿别的季的分类顶替（同 IP 的分类可以不同）。
     */
    fun seasonTags(series: Series, favorites: FavoritesPort): String =
        favorites.all().asSequence()
            .mapNotNull { favorites.seasonLine(it.seriesKey) }
            .flatMap { it.seasons.asSequence() }
            .firstOrNull { it.id == series.id }?.tags.orEmpty()
}
