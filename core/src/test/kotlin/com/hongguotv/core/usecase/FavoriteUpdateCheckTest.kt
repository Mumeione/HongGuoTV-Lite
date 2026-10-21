package com.hongguotv.core.usecase

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.Episode
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.StreamInfo
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.UpstreamError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 收藏更新检查的三条规则（1 小时、连续失败 3 次、新季看季号）都在这里钉死。
 * 用假端口测：断言的是"发起了哪些调用、按什么顺序、报告了几个数"，不是内部实现。
 */
class FavoriteUpdateCheckTest {

    private val now = 10_000_000L

    private fun entry(
        id: String,
        title: String = "测试书第${id}季",
        season: Int = 0,
        checkedAt: Long = 0L,
        key: String = "测试书",
    ) = FavoriteEntry(
        series = Series(id = id, title = title, season = season),
        addedAt = 0L,
        status = FavoriteStatus.UP_TO_DATE,
        seriesKey = key,
        checkedAt = checkedAt,
    )

    private fun detail(id: String, episodes: Int, season: Int = 0): Detail =
        Detail(Series(id = id, title = "测试书第${id}季", season = season), List(episodes) { Episode("vid-$id-$it", it) })

    private class FakeContent : ContentPort {
        val calls = mutableListOf<String>()
        val details = LinkedHashMap<String, Detail>()
        val lines = LinkedHashMap<String, SeasonLine>()
        val failingDetails = mutableSetOf<String>()
        val failingLines = mutableSetOf<String>()

        override suspend fun search(keyword: String, filter: ContentFilter): SearchResult =
            SearchResult(emptyList(), emptyList())

        override suspend fun suggest(keyword: String): List<Suggestion> = emptyList()

        override suspend fun detail(seriesId: String): Detail {
            calls += "detail:$seriesId"
            if (seriesId in failingDetails) throw UpstreamError.Network(IOException("offline"))
            return details.getValue(seriesId)
        }

        override suspend fun seasons(seriesId: String, anchor: Series?): SeasonLine {
            calls += "seasons:$seriesId"
            if (seriesId in failingLines) throw UpstreamError.EmptyBody()
            // 没配置季线 = 服务端这次没给出可用的季线
            return lines[seriesId] ?: throw UpstreamError.EmptyBody()
        }

        override suspend fun stream(vid: String, maxHeight: Int): StreamInfo = error("检查更新不取流")
    }

    private class FakeFavorites : FavoritesPort {
        val remembered = mutableListOf<Pair<String, SeasonLine>>()
        val acknowledged = mutableListOf<Triple<String, Int, Long>>()
        /** 检查后重新读到的收藏行；null = 收藏已被删除。 */
        var currentEntry: FavoriteEntry? = null

        override fun all(): List<FavoriteEntry> = emptyList()
        override fun isFavorite(seriesKey: String): Boolean = false
        override fun entry(seriesKey: String): FavoriteEntry? = currentEntry
        override suspend fun toggle(series: Series): Boolean = false
        override suspend fun setCurrentSeason(seriesKey: String, series: Series) = Unit
        override fun seasonLine(seriesKey: String): SeasonLine? = null
        override fun rememberSeasonLine(seriesKey: String, line: SeasonLine) {
            remembered += seriesKey to line
        }

        override fun acknowledge(seriesId: String, totalEpisodes: Int, at: Long) {
            acknowledged += Triple(seriesId, totalEpisodes, at)
        }
    }

    private fun check(
        entries: List<FavoriteEntry>,
        content: FakeContent,
        favorites: FakeFavorites,
        force: Boolean = false,
        at: Long = now,
    ): FavoriteCheckReport = runBlocking {
        FavoriteUpdateCheck.run(
            entries = entries,
            content = content,
            favorites = favorites,
            clock = { at },
            force = force,
        )
    }

    // ---------- 1 小时有效期 ----------

    @Test
    fun freshEntriesAreSkipped() {
        val content = FakeContent()
        val favorites = FakeFavorites()
        val report = check(listOf(entry("1", checkedAt = now - 30 * 60_000L)), content, favorites)
        assertEquals(0, report.checked + report.failed)
        assertTrue(content.calls.isEmpty())
        assertTrue(favorites.acknowledged.isEmpty())
    }

    @Test
    fun staleNeverCheckedAndFutureTimestampsAreDue() {
        assertTrue(FavoriteUpdateCheck.isDue(entry("1", checkedAt = now - FavoriteUpdateCheck.FRESH_MS), now, force = false))
        assertTrue(FavoriteUpdateCheck.isDue(entry("1", checkedAt = 0L), now, force = false))
        // 设备时钟回拨：一次坏时间不能把检查冻住一整天
        assertTrue(FavoriteUpdateCheck.isDue(entry("1", checkedAt = now + 60_000L), now, force = false))
        assertFalse(FavoriteUpdateCheck.isDue(entry("1", checkedAt = now - 1L), now, force = false))
    }

    @Test
    fun forceChecksEvenFreshEntries() {
        val content = FakeContent().apply { details["1"] = detail("1", episodes = 3) }
        val report = check(listOf(entry("1", checkedAt = now - 1L)), content, FakeFavorites(), force = true)
        assertEquals(1, report.checked)
    }

    // ---------- 检查内容 ----------

    @Test
    fun detailComesBeforeSeasonLineAndEpisodeCountIsAcknowledged() {
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 12, season = 1)
            lines["1"] = SeasonLine(listOf(Series("1", "测试书第一季", season = 1), Series("2", "测试书第二季", season = 2)))
            details["2"] = detail("2", episodes = 5)
        }
        val favorites = FakeFavorites()
        val report = check(listOf(entry("1"), entry("2")), content, favorites)

        assertEquals(listOf("detail:1", "seasons:1", "detail:2", "seasons:2"), content.calls)
        assertEquals(2, report.checked)
        // 集数以详情为准（与详情页一致），季线只负责"有没有更新的季"
        assertEquals(listOf(Triple("1", 12, now), Triple("2", 5, now)), favorites.acknowledged)
    }

    @Test
    fun seasonLineIsRememberedOnlyWhenItHasAtLeastTwoSeasons() {
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 4)
            lines["1"] = SeasonLine(listOf(Series("1", "测试书第一季", season = 1)))
            details["2"] = detail("2", episodes = 4)
            lines["2"] = SeasonLine(listOf(Series("2", "测试书第一季", season = 1), Series("3", "测试书第二季", season = 2)))
        }
        val favorites = FakeFavorites()
        check(listOf(entry("1", key = "书一"), entry("2", key = "书二")), content, favorites)

        assertEquals(listOf("书二"), favorites.remembered.map { it.first })
    }

    @Test
    fun seasonLineFailureDoesNotFailTheItem() {
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 4)
            failingLines += "1"
        }
        val favorites = FakeFavorites()
        val report = check(listOf(entry("1")), content, favorites)

        // 集数已经拿到了，"这次不知道有没有新季"不是整部失败（旧行为）
        assertEquals(1, report.checked)
        assertEquals(0, report.failed)
        assertEquals(1, favorites.acknowledged.size)
        assertTrue(favorites.remembered.isEmpty())
    }

    @Test
    fun newerSeasonIsJudgedBySeasonNumberNotEpisodeGrowth() {
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 12, season = 5)
            lines["1"] = SeasonLine(listOf(Series("1", "测试书第五季", season = 5), Series("2", "测试书第七季", season = 7)))
            details["2"] = detail("2", episodes = 12, season = 8)
            lines["2"] = SeasonLine(listOf(Series("2", "测试书第八季", season = 8), Series("3", "测试书第七季", season = 7)))
        }
        val report = check(listOf(entry("1", season = 5), entry("2", season = 8)), content, FakeFavorites())

        assertEquals(1, report.newSeasons)
        assertEquals(0, report.newEpisodes)
        assertEquals(2, report.checked)
    }

    @Test
    fun episodeGrowthInTheSameSeasonCountsAsNewEpisodes() {
        // 单季短剧每日加集：没有新季，但详情集数涨了。旧实现只看季号，状态行会误报"已是最新"。
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 524, season = 0)
            lines["1"] = SeasonLine(listOf(Series("1", "十八岁的二次回响", season = 0)))
        }
        val favorites = FakeFavorites().apply {
            currentEntry = entry("1", title = "十八岁的二次回响", season = 0)
                .copy(status = FavoriteStatus.NEW_SEASON)
        }
        val report = check(listOf(entry("1", title = "十八岁的二次回响", season = 0)), content, favorites)

        assertEquals(0, report.newSeasons)
        assertEquals(1, report.newEpisodes)
        assertEquals(1, report.checked)
    }

    @Test
    fun unreleasedSeasonDoesNotCountAsNewSeason() {
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 56, season = 9)
            lines["1"] = SeasonLine(
                listOf(
                    Series("1", "测试书第九季", season = 9),
                    Series("2", "测试书第十季", season = 10, released = false),
                ),
            )
        }
        val report = check(listOf(entry("1", season = 9)), content, FakeFavorites())

        assertEquals(0, report.newSeasons)
    }

    @Test
    fun aNewSeasonAndEpisodeGrowthCanHappenTogether() {
        // 既有新季、当前季又加了集：两个计数都要+1，不能让"新一季"把"新集"吞掉。
        // acknowledge 是假端口里没实现的落库，所以直接把检查后状态摆成 NEW_SEASON（与真库一致）。
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 60, season = 9)
            lines["1"] = SeasonLine(
                listOf(
                    Series("1", "测试书第九季", season = 9),
                    Series("2", "测试书第十季", season = 10),
                ),
            )
        }
        val favorites = FakeFavorites().apply {
            currentEntry = entry("1", season = 9).copy(status = FavoriteStatus.NEW_SEASON)
        }
        val report = check(listOf(entry("1", season = 9)), content, favorites)

        assertEquals(1, report.newSeasons)
        assertEquals(1, report.newEpisodes)
    }

    // ---------- 连续失败 ----------

    @Test
    fun threeConsecutiveFailuresStopTheRun() {
        val content = FakeContent().apply {
            listOf("1", "2", "3", "4").forEach { id ->
                details[id] = detail(id, episodes = 3)
                failingDetails += id
            }
        }
        val report = check(listOf(entry("1"), entry("2"), entry("3"), entry("4")), content, FakeFavorites())

        assertEquals(3, report.failed)
        assertEquals(0, report.checked)
        assertTrue(report.stoppedEarly)
        // 第四部不再发起请求：断网时别把整个收藏列表空转一遍
        assertEquals(listOf("detail:1", "detail:2", "detail:3"), content.calls)
    }

    @Test
    fun aSuccessResetsTheFailureStreak() {
        val content = FakeContent().apply {
            listOf("1", "2", "3", "4", "5").forEach { id -> details[id] = detail(id, episodes = 3) }
            failingDetails += setOf("1", "2", "4", "5")
        }
        val report = check(listOf("1", "2", "3", "4", "5").map { entry(it) }, content, FakeFavorites())

        assertEquals(1, report.checked)
        assertEquals(4, report.failed)
        assertFalse(report.stoppedEarly)
        assertEquals(5, content.calls.count { it.startsWith("detail:") })
    }

    @Test
    fun emptyDueListReportsNothing() {
        val report = check(emptyList(), FakeContent(), FakeFavorites())
        assertEquals(
            FavoriteCheckReport(checked = 0, failed = 0, newSeasons = 0, newEpisodes = 0, stoppedEarly = false),
            report,
        )
    }
}
