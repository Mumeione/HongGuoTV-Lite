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

    private fun entry(
        key: String,
        seasonId: String,
        title: String = key,
        albumId: String = "",
        season: Int = 0,
    ) = FavoriteEntry(
        series = Series(id = seasonId, title = title, albumId = albumId, season = season),
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

    /**
     * 同 IP 正传（`albumId` 空、季号 0）的收藏行不能靠标题键认领**属于某张专辑**的季。
     *
     * 实测：收藏正传后，S3–S10 的标题键都等于基名，旧逻辑会把它们全判成「已收藏」；
     * 取消收藏还会删掉正传那一条。专辑 id 是这里唯一能证伪的证据。
     */
    @Test
    fun `a same ip favorite without an album does not claim an album season`() {
        val favorites = listOf(entry("魏家测试书", "9", "魏家测试书", albumId = "", season = 0))
        val season = Series("3", "魏家测试书第三季", season = 3, albumId = "album-1")
        assertNull(FavoriteLookup.entry(season, favorites) { null })
    }

    @Test
    fun `a favorite with the same album still matches by key`() {
        val favorites = listOf(entry("魏家测试书", "9", "魏家测试书", albumId = "album-1", season = 9))
        val season = Series("3", "魏家测试书第三季", season = 3, albumId = "album-1")
        assertEquals("魏家测试书", FavoriteLookup.entry(season, favorites) { null }?.seriesKey)
    }

    @Test
    fun `a favorite with a different album does not claim the season`() {
        val favorites = listOf(entry("魏家测试书", "9", "魏家测试书", albumId = "album-2", season = 9))
        val season = Series("3", "魏家测试书第三季", season = 3, albumId = "album-1")
        assertNull(FavoriteLookup.entry(season, favorites) { null })
    }

    /** 搜索卡没有 albumId：不能因为「缺证据」把已收藏的剧显示成未收藏。 */
    @Test
    fun `a search card without album evidence still matches the title key`() {
        val favorites = listOf(entry("魏家测试书", "9", "魏家测试书", albumId = "album-1", season = 9))
        val searchCard = Series("3", "魏家测试书第三季", season = 3, albumId = "")
        assertEquals("魏家测试书", FavoriteLookup.entry(searchCard, favorites) { null }?.seriesKey)
    }

    /** 同 IP 正传（album 空、季号 0）也不能认领带季号的搜索卡。 */
    @Test
    fun `a same ip favorite without an album does not claim a numbered search card`() {
        val favorites = listOf(entry("魏家测试书", "9", "魏家测试书", albumId = "", season = 0))
        val searchCard = Series("3", "魏家测试书第三季", season = 3, albumId = "")
        assertNull(FavoriteLookup.entry(searchCard, favorites) { null })
    }

    /** 旧收藏行没有 albumId，但季号 > 0：它确实是某一季，按标题键认领。 */
    @Test
    fun `a legacy season favorite without an album still matches a numbered search card`() {
        val favorites = listOf(entry("魏家测试书", "6", "魏家测试书", albumId = "", season = 6))
        val searchCard = Series("3", "魏家测试书第三季", season = 3, albumId = "")
        assertEquals("魏家测试书", FavoriteLookup.entry(searchCard, favorites) { null }?.seriesKey)
    }

    /** 候选项有专辑证据时，收藏行没有专辑证据就只能靠季线 id 归属。 */
    @Test
    fun `a same ip favorite still claims an album season that is in its line`() {
        val favorites = listOf(entry("魏家测试书", "9", "魏家测试书", albumId = "", season = 0))
        val lines = mapOf("魏家测试书" to lineOf("9", "3"))
        val season = Series("3", "魏家测试书第三季", season = 3, albumId = "album-1")
        assertEquals("魏家测试书", FavoriteLookup.entry(season, favorites) { lines[it] }?.seriesKey)
    }
}
