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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 收藏更新检查的规则（12 小时有效期、全局 6 小时门槛、每轮 40 部、连续失败 3 次、
 * "每部只问一次"的探针选择）。用假端口测：断言的是"发起了哪些请求、按什么顺序、
 * 报告了几个数"，不是内部实现。
 */
class FavoriteUpdateCheckTest {

    private val now = 10_000_000L

    private fun entry(
        id: String,
        title: String = "测试书第${id}季",
        season: Int = 0,
        checkedAt: Long = 0L,
        key: String = "测试书",
        currentEpisodes: Int = 0,
        albumId: String = "",
    ) = FavoriteEntry(
        series = Series(id = id, title = title, season = season, albumId = albumId),
        addedAt = 0L,
        status = FavoriteStatus.UP_TO_DATE,
        seriesKey = key,
        checkedAt = checkedAt,
        currentEpisodes = currentEpisodes,
    )

    private fun detail(id: String, episodes: Int, season: Int = 0): Detail =
        Detail(Series(id = id, title = "测试书第${id}季", season = season), List(episodes) { Episode("vid-$id-$it", it) })

    private class FakeContent : ContentPort {
        val calls = mutableListOf<String>()
        val details = LinkedHashMap<String, Detail>()
        val lines = LinkedHashMap<String, SeasonLine>()
        val failingDetails = mutableSetOf<String>()

        /** 详情接口按风控处理（200 空体）的 id。 */
        val riskDetails = mutableSetOf<String>()
        val failingLines = mutableSetOf<String>()

        override suspend fun search(keyword: String, filter: ContentFilter, page: Int): SearchResult =
            SearchResult(emptyList())

        override suspend fun suggest(keyword: String): List<Suggestion> = emptyList()

        override suspend fun detail(seriesId: String): Detail {
            calls += "detail:$seriesId"
            if (seriesId in riskDetails) throw UpstreamError.EmptyBody()
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
        var line: SeasonLine? = null
        var currentSeason: Pair<String, Series>? = null

        override fun all(): List<FavoriteEntry> = emptyList()
        override fun isFavorite(seriesKey: String): Boolean = false
        override fun entry(seriesKey: String): FavoriteEntry? = null
        override suspend fun toggle(series: Series): Boolean = false
        override suspend fun setCurrentSeason(seriesKey: String, series: Series) {
            currentSeason = seriesKey to series
        }

        override fun seasonLine(seriesKey: String): SeasonLine? = line
        override fun rememberSeasonLine(seriesKey: String, line: SeasonLine) {
            remembered += seriesKey to line
        }

        override fun acknowledge(seriesId: String, totalEpisodes: Int, at: Long) {
            acknowledged += Triple(seriesId, totalEpisodes, at)
        }

        override fun lastCheckRoundAt(): Long = 0L
        override fun markCheckRound(at: Long) = Unit
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

    // ---------- 有效期 ----------

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

    // ---------- 每轮查哪些（全局门槛 + 轮转） ----------

    @Test
    fun autoCheckIsBlockedWithinSixHoursOfTheLastRun() {
        // 上一轮两小时前刚查过一批：其余过期项也先压住，别把 100 部分成几秒内连打两轮
        val entries = listOf(
            entry("a", checkedAt = now - 2 * 3_600_000L),
            entry("b", checkedAt = now - 20 * 3_600_000L),
            entry("c", checkedAt = now - 20 * 3_600_000L),
        )
        val plan = FavoriteUpdateCheck.plan(entries, now, manual = false)
        assertTrue(plan.blocked)
        assertTrue(plan.targets.isEmpty())
        // 过期的两部如实报出来：页面说"刚查过 · 还有 2 部待查"
        assertEquals(2, plan.remaining)
    }

    @Test
    fun autoCheckPicksTheOldestFortyAndReportsTheRest() {
        // 100 部都没查过：只查最旧的 40 部，剩下的 60 部如实报出来（下一轮再来）
        val entries = (1..100).map { entry("$it", checkedAt = now - 13 * 3_600_000L - it) }
        val plan = FavoriteUpdateCheck.plan(entries, now, manual = false)
        assertFalse(plan.blocked)
        assertEquals(FavoriteUpdateCheck.AUTO_LIMIT, plan.targets.size)
        assertEquals(60, plan.remaining)
        // 最旧优先：checkedAt 最小的是 id=100
        assertEquals("100", plan.targets.first().series.id)
    }

    @Test
    fun manualCheckTakesEverythingWithoutTouchingTheRotation() {
        val entries = (1..100).map { entry("$it", checkedAt = now - 60_000L) }
        val plan = FavoriteUpdateCheck.plan(entries, now, manual = true)
        assertFalse(plan.blocked)
        assertEquals(100, plan.targets.size)
        assertEquals(0, plan.remaining)
    }

    /**
     * 在某一部的详情页手动查过它（`acknowledge` 把 checkedAt 写成现在）：
     * 12 小时内它不会再进自动轮转——**不占别人的名额**，也不需要额外的"已手动检查"标记。
     */
    @Test
    fun aManuallyCheckedFavoriteDropsOutOfTheNextRotation() {
        val content = FakeContent().apply { details["1"] = detail("1", episodes = 3) }
        val favorites = FakeFavorites()
        runBlocking {
            FavoriteUpdateCheck.run(listOf(entry("1")), content, favorites, clock = { now }, force = true)
        }
        val written = favorites.acknowledged.single().third
        assertEquals("落库的就是这一轮的时钟", now, written)

        val after = entry("1", checkedAt = written)
        assertFalse(FavoriteUpdateCheck.isDue(after, now, force = false))
        assertTrue(FavoriteUpdateCheck.plan(listOf(after), now, manual = false).targets.isEmpty())
    }

    /**
     * 整轮门槛只看 [FavoriteUpdateCheck.plan] 的 `lastRoundAt`，不看各部的 `checkedAt`。
     *
     * 反例（旧实现）：在详情页查了一部 → 那一部 checkedAt = now → 门槛读 max(checkedAt)
     * → 整轮被挡住 6 小时，其余收藏永远轮不到。这条测试就是那件事的照妖镜。
     */
    @Test
    fun aSingleCheckDoesNotPushTheWholeRoundBack() {
        // 用一个"足够晚"的时钟：要给 13 小时前的 checkedAt 留出正数空间
        val t = 10L * 24 * 3_600_000L
        // 一部刚在详情页查过（5 分钟前），其余 9 部 13 小时没查，上一轮整轮是 7 小时前
        val entries = listOf(entry("1", checkedAt = t - 5 * 60_000L)) +
            (2..10).map { entry("$it", checkedAt = t - 13 * 3_600_000L - it) }
        val plan = FavoriteUpdateCheck.plan(entries, t, manual = false, lastRoundAt = t - 7 * 3_600_000L)

        assertFalse("单部检查不该把整轮挡住", plan.blocked)
        assertEquals("刚查过的那一部不入列", 9, plan.targets.size)
        assertTrue("1 号不在本轮", plan.targets.none { it.series.id == "1" })
    }

    /** 整轮时间在 6 小时内：整轮跳过，与各部 checkedAt 无关。 */
    @Test
    fun theRoundGateReadsTheRoundTimestamp() {
        val entries = (1..10).map { entry("$it", checkedAt = now - 13 * 3_600_000L) }
        val blocked = FavoriteUpdateCheck.plan(entries, now, manual = false, lastRoundAt = now - 3_600_000L)
        assertTrue("上一轮 1 小时前跑过：自动检查整轮跳过", blocked.blocked)
        assertEquals(10, blocked.remaining)
    }

    /** 旧盘没有整轮时间（0）：退回 max(checkedAt)，升级后的第一轮与旧版行为一致。 */
    @Test
    fun anOldStoreFallsBackToTheNewestRow() {
        val entries = (1..5).map { entry("$it", checkedAt = now - 13 * 3_600_000L) } +
            entry("99", checkedAt = now - 3_600_000L)
        val plan = FavoriteUpdateCheck.plan(entries, now, manual = false)
        assertTrue("没有整轮时间时按最新的一部判门槛", plan.blocked)
    }

    @Test
    fun aRunReportsHowManyAreLeft() {
        val content = FakeContent()
        (1..45).forEach { content.details["$it"] = detail("$it", episodes = 3) }
        val entries = (1..45).map { entry("$it", checkedAt = now - 13 * 3_600_000L - it) }
        val report = runBlocking {
            FavoriteUpdateCheck.run(entries, content, FakeFavorites(), clock = { now }, force = false)
        }
        assertEquals(40, report.checked)
        assertEquals(5, report.remaining)
        assertEquals(40, content.calls.size)
    }

    // ---------- 每部只问一次（探针） ----------

    @Test
    fun singleSeasonFavoriteOnlyAsksForTheDetail() {
        val content = FakeContent().apply { details["1"] = detail("1", episodes = 12, season = 1) }
        val report = check(listOf(entry("1")), content, FakeFavorites())

        assertEquals(listOf("detail:1"), content.calls)
        assertEquals(1, report.checked)
    }

    @Test
    fun multiSeasonFavoriteOnlyAsksForTheSeasonLine() {
        val content = FakeContent().apply {
            lines["1"] = SeasonLine(
                listOf(
                    Series("1", "测试书第一季", season = 1, episodeCount = 12),
                    Series("2", "测试书第二季", season = 2, episodeCount = 5),
                ),
            )
        }
        val favorites = FakeFavorites().apply { line = content.lines["1"] }
        val report = check(listOf(entry("1", season = 1, title = "测试书第一季", currentEpisodes = 12)), content, favorites)

        assertEquals(listOf("seasons:1"), content.calls)
        assertEquals(1, report.checked)
        // 当前季在线里且集数已知：按 12 落库（与详情一致），不是拿别的季顶
        assertEquals(Triple("1", 12, now), favorites.acknowledged.single())
    }

    @Test
    fun aLineWithOneSeasonStillGoesThroughTheDetail() {
        // 季线只有一季（单季剧）：问详情，详情给的是准确集数。
        // 带专辑号也不改判据——单季剧也可能有 album_id，按它判会把它永远锁在季线路径上。
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 4, season = 1)
            lines["1"] = SeasonLine(listOf(Series("1", "测试书第一季", season = 1, episodeCount = 4)))
        }
        val favorites = FakeFavorites().apply { line = content.lines["1"] }
        check(listOf(entry("1", albumId = "album-1")), content, favorites)

        assertEquals(listOf("detail:1"), content.calls)
        assertTrue(favorites.remembered.isEmpty())
    }

    @Test
    fun theSeasonLineIsRememberedWhenItHasAtLeastTwoSeasons() {
        val content = FakeContent().apply {
            lines["1"] = SeasonLine(
                listOf(Series("1", "测试书第一季", season = 1, episodeCount = 4), Series("2", "测试书第二季", season = 2)),
            )
        }
        val favorites = FakeFavorites().apply { line = content.lines["1"] }
        check(listOf(entry("1", title = "测试书第一季", key = "书一")), content, favorites)

        assertEquals(listOf("书一"), favorites.remembered.map { it.first })
    }

    // ---------- 判定 ----------

    @Test
    fun episodeGrowthIsMeasuredAgainstTheKnownCount() {
        // 单季短剧每日加集：已知 520，详情 524 → 本次有新集
        val content = FakeContent().apply { details["1"] = detail("1", episodes = 524) }
        val favorites = FakeFavorites()
        val report = check(listOf(entry("1", title = "十八岁的二次回响", currentEpisodes = 520)), content, favorites)

        assertEquals(0, report.newSeasons)
        assertEquals(1, report.newEpisodes)
        assertEquals(Triple("1", 524, now), favorites.acknowledged.single())
    }

    @Test
    fun anUnknownCountNeverCountsAsGrowth() {
        // 首次检查（已知 0）：落库但不报"有新集"
        val content = FakeContent().apply { details["1"] = detail("1", episodes = 524) }
        val report = check(listOf(entry("1", currentEpisodes = 0)), content, FakeFavorites())

        assertEquals(0, report.newEpisodes)
    }

    @Test
    fun aNewSeasonIsJudgedBySeasonNumberNotEpisodeGrowth() {
        val content = FakeContent().apply {
            lines["1"] = SeasonLine(
                listOf(
                    Series("1", "测试书第五季", season = 5, episodeCount = 12),
                    Series("2", "测试书第七季", season = 7, episodeCount = 3),
                ),
            )
        }
        val favorites = FakeFavorites().apply { line = content.lines["1"] }
        val report = check(listOf(entry("1", season = 5, title = "测试书第五季", currentEpisodes = 12)), content, favorites)

        assertEquals(1, report.newSeasons)
        assertEquals(0, report.newEpisodes)
    }

    @Test
    fun unreleasedSeasonDoesNotCountAsNewSeason() {
        val content = FakeContent().apply {
            lines["1"] = SeasonLine(
                listOf(
                    Series("1", "测试书第九季", season = 9, episodeCount = 56),
                    Series("2", "测试书第十季", season = 10, released = false),
                ),
            )
        }
        val favorites = FakeFavorites().apply { line = content.lines["1"] }
        val report = check(listOf(entry("1", season = 9, title = "测试书第九季", currentEpisodes = 56)), content, favorites)

        assertEquals(0, report.newSeasons)
    }

    @Test
    fun aNewSeasonAndEpisodeGrowthCanHappenTogether() {
        val content = FakeContent().apply {
            lines["1"] = SeasonLine(
                listOf(
                    Series("1", "测试书第九季", season = 9, episodeCount = 60),
                    Series("2", "测试书第十季", season = 10, episodeCount = 2),
                ),
            )
        }
        val favorites = FakeFavorites().apply { line = content.lines["1"] }
        val report = check(listOf(entry("1", season = 9, title = "测试书第九季", currentEpisodes = 56)), content, favorites)

        assertEquals(1, report.newSeasons)
        assertEquals(1, report.newEpisodes)
    }

    @Test
    fun anUnknownCurrentSeasonCountOnlyRefreshesTheCheckTime() {
        // 季线里没有当前季：不能拿别的季的数字去覆盖，只记"查过"
        val content = FakeContent().apply {
            lines["1"] = SeasonLine(
                listOf(Series("9", "测试书第九季", season = 9, episodeCount = 60), Series("8", "测试书第八季", season = 8)),
            )
        }
        val favorites = FakeFavorites().apply { line = content.lines["1"] }
        check(listOf(entry("1", season = 9, title = "测试书第九季", currentEpisodes = 60)), content, favorites)

        assertEquals(Triple("1", 0, now), favorites.acknowledged.single())
    }

    @Test
    fun aDetailRunLearnsThatTheSeasonBelongsToAnAlbum() {
        // 单季剧第一次走详情：详情说它属于某张专辑 → 写回收藏行，下次改走季线
        val content = FakeContent().apply {
            details["1"] = Detail(Series("1", "测试书第一季", season = 1, albumId = "album-1"), List(3) { Episode("v$it", it) })
        }
        val favorites = FakeFavorites()
        check(listOf(entry("1")), content, favorites)

        assertEquals("album-1", favorites.currentSeason?.second?.albumId)
    }

    // ---------- 失败 ----------

    @Test
    fun aSeasonLineFailureCountsAsAFailedItem() {
        // 这一部只问了一次季线，问不到就是这一部失败（不再有"详情拿到了所以不算失败"的分支）
        val content = FakeContent().apply {
            lines["1"] = SeasonLine(listOf(Series("1", "测试书第一季", season = 1), Series("2", "测试书第二季", season = 2)))
            failingLines += "1"
        }
        val favorites = FakeFavorites().apply { line = content.lines["1"] }
        val report = check(listOf(entry("1")), content, favorites)

        assertEquals(1, report.failed)
        assertEquals(0, report.checked)
        assertTrue(favorites.acknowledged.isEmpty())
    }

    @Test
    fun threeConsecutiveFailuresStopTheRun() {
        val content = FakeContent().apply {
            listOf("1", "2", "3", "4").forEach { id ->
                details[id] = detail(id, episodes = 3)
                failingDetails += id
            }
        }
        val report = check(listOf("1", "2", "3", "4").map { entry(it) }, content, FakeFavorites())

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

    // ---------- 失败分档 ----------

    @Test
    fun riskControlIsItsOwnFailureKind() {
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 3)
            riskDetails += setOf("1", "2")
        }
        val report = check(listOf(entry("1"), entry("2")), content, FakeFavorites())

        assertEquals(2, report.failed)
        assertEquals(CheckFailure.RISK_CONTROL, report.failure)
    }

    @Test
    fun networkFailureIsNotMistakenForRiskControl() {
        // 断网时给"重新注册"是错的：用户换设备也解决不了没网
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 3)
            failingDetails += "1"
        }
        assertEquals(CheckFailure.NETWORK, check(listOf(entry("1")), content, FakeFavorites()).failure)
    }

    @Test
    fun theMostActionableFailureWins() {
        // 一列里混着断网与风控：用户只需要知道"要换设备"这件最该做的事
        val content = FakeContent().apply {
            details["1"] = detail("1", episodes = 3)
            details["2"] = detail("2", episodes = 3)
            failingDetails += "1"
            riskDetails += "2"
        }
        val report = check(listOf(entry("1"), entry("2")), content, FakeFavorites())

        assertEquals(2, report.failed)
        assertEquals(CheckFailure.RISK_CONTROL, report.failure)
    }

    @Test
    fun aCleanCheckReportsNoFailureKind() {
        val content = FakeContent().apply { details["1"] = detail("1", episodes = 3) }
        assertNull(check(listOf(entry("1")), content, FakeFavorites()).failure)
    }
}
