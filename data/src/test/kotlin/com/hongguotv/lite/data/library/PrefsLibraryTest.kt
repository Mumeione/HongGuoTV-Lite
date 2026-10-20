package com.hongguotv.lite.data.library

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.Settings
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地库：旧版 prefs 的可读性（迁移）、三个端口的行为、写路径。 */
class PrefsLibraryTest {

    private class InMemoryStore(initial: Map<String, Any?> = emptyMap()) : KeyValueStore {
        val data = LinkedHashMap<String, Any?>(initial)
        var commits = 0
            private set
        override fun all(): Map<String, Any?> = data.toMap()
        override fun commit(values: Map<String, Any?>) {
            values.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
            commits++
        }
    }

    private fun store(vararg pairs: Pair<String, Any?>) = InMemoryStore(pairs.toMap())

    // ---------- 旧版 prefs 迁移 ----------

    private fun oldSeriesJson(id: String, title: String) = JSONObject()
        .put("id", id).put("title", title).put("cover", "https://img.example/c.jpg")
        .put("description", "旧简介").put("badge", "全 12 集").put("tags", "古装")
        .put("createTime", 0L).put("isComic", false).put("season", 2)

    private fun oldPrefs(): Array<Pair<String, Any?>> = arrayOf(
        "favorites" to JSONArray().put(oldSeriesJson("1", "凡人测试书第二季")).put(oldSeriesJson("2", "完全无关的剧")).toString(),
        "progress" to JSONArray().put(
            JSONObject()
                .put("series", oldSeriesJson("1", "凡人测试书第二季"))
                .put("episodeId", "900").put("episodeIndex", 2)
                .put("position", 65_000L).put("duration", 120_000L)
                .put("completed", false).put("updatedAt", 1_000L)
        ).toString(),
        "watched" to setOf("1"),
        "quality" to 720,
        "playbackSpeed" to 1.5f,
        "autoNext" to false,
        "contentType" to "comic",
        "frameMode" to "ZOOM",
        "startupTab" to 2,
        "seasonSortAsc" to true,
    )

    @Test
    fun readsLegacyPrefsAndMigratesFavoritesToSeries() = runTest {
        // 固定时钟：迁移 addedAt 全等，排序稳定性才可断言
        val lib = PrefsLibrary(store(*oldPrefs()), clock = { 1_000L })
        // 旧版两条收藏：同 base 的"第二季"保留（它就是最新一条），无关剧另成一条
        val favorites = lib.all()
        assertEquals(listOf("凡人测试书", "完全无关的剧"), favorites.map { it.series.title })
        val entry = lib.entry("凡人测试书")
        assertNotNull(entry)
        // description → intro 的映射；released 由 badge 派生
        assertEquals("旧简介", entry!!.series.intro)
        assertTrue(entry.series.released)
        assertEquals(2, entry.series.season)
        assertFalse(lib.isFavorite("凡人测试书第二季"))
    }

    @Test
    fun readsLegacyProgressWatchedAndSettings() = runTest {
        val lib = PrefsLibrary(store(*oldPrefs()))
        val progress = lib.progressOf("1")
        assertNotNull(progress)
        assertEquals("900", progress!!.episodeVid)
        assertEquals(2, progress.episodeIndex)
        assertEquals(65_000L, progress.positionMs)
        assertEquals(120_000L, progress.durationMs)
        assertFalse(progress.completed)
        assertTrue(lib.isWatched("1"))
        assertFalse(lib.isWatched("2"))
        val settings = lib.current()
        assertEquals(720, settings.quality)
        assertEquals(1.5f, settings.speed)
        assertEquals(false, settings.autoNext)
        assertEquals(ContentFilter.COMIC, settings.filter)
        assertEquals(FrameMode.ZOOM, settings.frame)
        assertEquals(2, settings.startupTab)
        assertTrue(settings.seasonSortAsc)
    }

    @Test
    fun migrationRunsOnceAndMarksTheMarkerKey() = runTest {
        val s = store(*oldPrefs())
        PrefsLibrary(s).flush()
        // favoriteSeries 写过即视为已迁移；旧 favorites 分区原样保留（不删数据）
        assertTrue(s.data.containsKey("favoriteSeries"))
        assertTrue(s.data.containsKey("favorites"))
    }

    @Test
    fun corruptPartitionsAreDroppedNotFatal() = runTest {
        val lib = PrefsLibrary(store(
            "progress" to "{not json",
            "seasonGroups" to "[also bad",
            "favoriteSeries" to "{}",
        ))
        assertTrue(lib.history().isEmpty())
        assertNull(lib.seasonLine("任何键"))
        // 坏分区不炸启动，但必须留痕（顺序 = init 的解码顺序）
        assertEquals(listOf("seasonGroups", "progress"), lib.corruptPartitions)
    }

    // ---------- 进度 ----------

    private fun progress(index: Int, updatedAt: Long, completed: Boolean = false, seriesId: String = "1") = Progress(
        seriesId = seriesId, episodeVid = "${index}00", episodeIndex = index,
        positionMs = 10_000L, durationMs = 100_000L, completed = completed, updatedAt = updatedAt,
    )

    @Test
    fun saveUpsertsHistoryWithMetadataPreserved() = runTest {
        val s = store(*oldPrefs())
        val lib = PrefsLibrary(s)
        lib.save(progress(index = 3, updatedAt = 2_000L))
        lib.flush()
        // 新记录只有 id：盘上旧行的标题不能因为续看一次而消失
        val rows = JSONArray(s.data["progress"] as String)
        assertEquals(1, rows.length())
        assertEquals("凡人测试书第二季", rows.getJSONObject(0).getJSONObject("series").getString("title"))
        assertEquals(3, lib.history()[0].progress.episodeIndex)
    }

    @Test
    fun historyCarriesSeriesMetadataForTheHistoryPage() = runTest {
        // 历史页要画标题与封面：只有 Progress 画不出卡片，端口必须把作品一起交出来
        val lib = PrefsLibrary(store(*oldPrefs()))
        val row = lib.history().single()
        assertEquals("凡人测试书第二季", row.series.title)
        assertEquals("1", row.series.id)
        assertEquals("https://img.example/c.jpg", row.series.cover)
    }

    @Test
    fun identicalPausedCheckpointDoesNotWrite() = runTest {
        val s = store("favoriteSeries" to "{}")
        val lib = PrefsLibrary(s)
        lib.save(progress(index = 1, updatedAt = 1_000L))
        lib.flush()
        val commits = s.commits
        lib.save(progress(index = 1, updatedAt = 1_000L))
        lib.flush()
        assertEquals(commits, s.commits)
    }

    @Test
    fun historyIsCappedAtThirty() = runTest {
        val lib = PrefsLibrary(store("favoriteSeries" to "{}"))
        (0 until 35).forEach { i -> lib.save(progress(index = 0, updatedAt = i.toLong(), seriesId = "$i")) }
        lib.flush()
        assertEquals(30, lib.history().size)
    }

    @Test
    fun watchedUpToDerivesFromTheLatestRow() = runTest {
        val lib = PrefsLibrary(store("favoriteSeries" to "{}"))
        assertEquals(0, lib.watchedUpTo("1"))
        lib.save(progress(index = 5, updatedAt = 1_000L, completed = false))
        assertEquals(5, lib.watchedUpTo("1"))
        lib.save(progress(index = 5, updatedAt = 2_000L, completed = true))
        assertEquals(6, lib.watchedUpTo("1"))
    }

    @Test
    fun removeAndClearHistoryAlsoDropTheWatchedMark() = runTest {
        val lib = PrefsLibrary(store(*oldPrefs()))
        lib.clearHistory()
        assertTrue(lib.history().isEmpty())
        assertFalse(lib.isWatched("1"))
    }

    // ---------- 收藏 ----------

    @Test
    fun toggleUsesTheSeasonBaseKey() = runTest {
        val lib = PrefsLibrary(store())
        assertTrue(lib.toggle(Series("1", "凡人测试书第二季")))
        assertTrue(lib.isFavorite("凡人测试书"))
        assertFalse(lib.isFavorite("凡人测试书第二季"))
        assertFalse(lib.toggle(Series("1", "凡人测试书第三季")))
        assertFalse(lib.isFavorite("凡人测试书"))
    }

    @Test
    fun rememberSeasonLineIsLruAndStripped() = runTest {
        val s = store("favoriteSeries" to "{}")
        val lib = PrefsLibrary(s)
        lib.rememberSeasonLine("剧A", SeasonLine(listOf(Series("1", "剧A第一季", intro = "长简介", tags = "标签"), Series("2", "剧A第二季"))))
        lib.rememberSeasonLine("剧B", SeasonLine(listOf(Series("3", "剧B第一季"), Series("4", "剧B第二季"))))
        val line = lib.seasonLine("剧A")
        assertNotNull(line)
        assertEquals("", line!!.seasons.first().intro)
        assertEquals(2, line.seasons.size)
        // 单季不落库
        lib.rememberSeasonLine("剧C", SeasonLine(listOf(Series("5", "剧C第一季"))))
        assertNull(lib.seasonLine("剧C"))
    }

    @Test
    fun rememberSeasonLineEvictsTheOldestBeyondTwenty() = runTest {
        val lib = PrefsLibrary(store("favoriteSeries" to "{}"))
        (0 until 21).forEach { i ->
            lib.rememberSeasonLine("剧$i", SeasonLine(listOf(Series("$i", "剧${i}一"), Series("x$i", "剧${i}二"))))
        }
        assertNull(lib.seasonLine("剧0"))
        assertNotNull(lib.seasonLine("剧20"))
    }

    @Test
    fun acknowledgeTracksTheGrowthThenClearsOnConfirmation() = runTest {
        val lib = PrefsLibrary(store("favoriteSeries" to "{}"))
        lib.toggle(Series("1", "测试书"))
        // 首次检查：落库，不打新标签
        lib.acknowledge("1", totalEpisodes = 10, at = 1_000L)
        assertEquals(FavoriteStatus.UP_TO_DATE, lib.entry("测试书")!!.status)
        // 新一季上线：total 增长 → NEW_SEASON
        lib.acknowledge("1", totalEpisodes = 14, at = 2_000L)
        assertEquals(FavoriteStatus.NEW_SEASON, lib.entry("测试书")!!.status)
        // 用户看过：total 不变的再次确认 → 标签消掉
        lib.acknowledge("1", totalEpisodes = 14, at = 3_000L)
        assertEquals(FavoriteStatus.UP_TO_DATE, lib.entry("测试书")!!.status)
    }

    @Test
    fun untrackedSeriesAreNotRecorded() = runTest {
        val lib = PrefsLibrary(store("favoriteSeries" to "{}"))
        lib.acknowledge("999", totalEpisodes = 10, at = 1_000L)
        assertNull(lib.entry("999"))
    }

    @Test
    fun latestSeasonComesFromTheRememberedLine() = runTest {
        val lib = PrefsLibrary(store("favoriteSeries" to "{}"))
        lib.toggle(Series("1", "测试书"))
        lib.rememberSeasonLine("测试书", SeasonLine(listOf(Series("1", "测试书第一季", season = 1), Series("2", "测试书第八季", season = 8))))
        assertEquals(8, lib.entry("测试书")!!.latestSeason)
    }

    @Test
    fun favoriteEntryExposesItsKeyAndLastCheckTime() = runTest {
        val lib = PrefsLibrary(store("favoriteSeries" to "{}"), clock = { 5_000L })
        lib.toggle(Series("1", "凡人测试书第二季"))
        // 从未检查：时间为 0，页面据此判定"该检查一次"
        assertEquals(0L, lib.entry("凡人测试书")!!.checkedAt)
        lib.acknowledge("1", totalEpisodes = 12, at = 9_000L)
        val entry = lib.entry("凡人测试书")!!
        // 键与检查时间都由实现给出，页面不用从 title 猜、也不用另存一份
        assertEquals("凡人测试书", entry.seriesKey)
        assertEquals(9_000L, entry.checkedAt)
        assertEquals(9_000L, lib.all().single().checkedAt)
    }

    @Test
    fun pruningDropsUpdatesOfUnfavoritedSeries() = runTest {
        val s = store("favoriteSeries" to "{}")
        val lib = PrefsLibrary(s)
        lib.toggle(Series("1", "测试书"))
        lib.acknowledge("1", 10, 1_000L)
        lib.toggle(Series("1", "测试书")) // 取消收藏
        lib.flush()
        val updates = JSONObject(s.data["favoriteUpdates"] as String)
        assertEquals(0, updates.length())
    }

    // ---------- 设置 ----------

    @Test
    fun updatePersistsOnlyChangedKeys() = runTest {
        val s = store(*oldPrefs())
        val lib = PrefsLibrary(s)
        lib.flush()
        val commits = s.commits
        lib.update { it.copy(quality = 1080) } // 只有 quality 变
        lib.flush()
        assertEquals(1080, lib.current().quality)
        assertEquals(commits + 1, s.commits)
        assertEquals(1080, s.data["quality"])
        // 未变的键保持原值原样
        assertEquals(1.5f, s.data["playbackSpeed"])
    }

    @Test
    fun settingsRoundTripNormalizesGarbage() = runTest {
        val lib = PrefsLibrary(store("quality" to 999, "playbackSpeed" to 0.1f, "frameMode" to " sideways ", "startupTab" to 99))
        val settings = lib.current()
        assertEquals(1080, settings.quality)
        assertEquals(1f, settings.speed)
        assertEquals(FrameMode.FIT, settings.frame)
        assertEquals(3, settings.startupTab) // 越界收敛到上界 3，不是 0
    }

    @Test
    fun storeFailureIsRecordedNotThrown() = runTest {
        val broken = object : KeyValueStore {
            override fun all(): Map<String, Any?> = emptyMap()
            override fun commit(values: Map<String, Any?>) = throw java.io.IOException("disk full")
        }
        val lib = PrefsLibrary(broken)
        lib.toggle(Series("1", "测试书"))
        lib.flush()
        assertNotNull(lib.lastWriteFailure)
    }
}
