package com.hongguotv.core.usecase

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "这一季属于哪个收藏系列"的唯一口径。
 *
 * 光比标题算出的系列键是不够的：分宗篇这类标题不带季号，键是它自己，
 * 与《魏家逆子成首辅》的收藏行永远对不上——维护者实测"已收藏却不显示已收藏"。
 * 季线里的 id 归属是第二个证据。
 */
class FavoriteLookupTest {

    private fun entry(key: String, seasonId: String, title: String = key) = FavoriteEntry(
        series = Series(id = seasonId, title = title),
        addedAt = 0L,
        status = FavoriteStatus.UNCHECKED,
        seriesKey = key,
    )

    private fun lineOf(vararg ids: String) = SeasonLine(ids.mapIndexed { index, id ->
        Series(id = id, title = "魏家测试书第${index + 1}季", season = index + 1)
    })

    @Test
    fun `matches by series key first`() {
        val favorites = listOf(entry("魏家测试书", "2", "魏家测试书"))
        val key = FavoriteLookup.favoriteKey(
            series = Series("3", "魏家测试书第三季", season = 3),
            entries = favorites,
            lineOf = { null },
        )
        assertEquals("魏家测试书", key)
    }

    @Test
    fun `falls back to the season line when the title carries no season tag`() {
        val favorites = listOf(entry("魏家测试书分宗篇", "1", "魏家测试书分宗篇"))
        val lines = mapOf("魏家测试书分宗篇" to lineOf("1", "2", "3"))
        val season = Series("2", "魏家测试书第二季", season = 2)

        val key = FavoriteLookup.favoriteKey(season, favorites) { lines[it] }
        assertEquals("标题算出的键对不上，但这一季在收藏的季线里：认它", "魏家测试书分宗篇", key)
        assertEquals(
            "同一个 id 直接命中收藏行",
            "魏家测试书分宗篇",
            FavoriteLookup.favoriteKey(Series("1", "分宗篇"), favorites) { lines[it] },
        )
    }

    @Test
    fun `returns the title key when nothing is favorited`() {
        val season = Series("2", "魏家测试书第二季", season = 2)
        assertEquals("魏家测试书", FavoriteLookup.favoriteKey(season, emptyList()) { null })
        assertNull(FavoriteLookup.entry(season, emptyList()) { null })
    }

    @Test
    fun `entry returns the favorite row that owns the season`() {
        val favorites = listOf(entry("魏家测试书分宗篇", "1", "魏家测试书分宗篇"))
        val lines = mapOf("魏家测试书分宗篇" to lineOf("1", "2"))
        val found = FavoriteLookup.entry(Series("2", "魏家测试书第二季", season = 2), favorites) { lines[it] }
        assertEquals("魏家测试书分宗篇", found?.seriesKey)
    }
}
