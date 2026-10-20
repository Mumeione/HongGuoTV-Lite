package com.hongguotv.lite.data.library

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.Settings
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.LocalError
import com.hongguotv.core.port.ProgressPort
import com.hongguotv.core.port.SettingsPort
import com.hongguotv.lite.data.content.TitleKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 本地库：[ProgressPort] / [FavoritesPort] / [SettingsPort] 的同一份事实。
 *
 * 存储形态沿用旧版（prefs 文件 native-library-v1，键名见下方各 KEY_ 常量）——
 * 升级安装后必须能读旧数据，改结构必须写迁移。内存态一次加载、写操作经单线程
 * 队列异步落盘（端口契约："写操作是提交意图"），磁盘失败不回滚内存，
 * 下一次写会整分区重写覆盖（与旧版 LatestWriteQueue 的失败口径一致）。
 *
 * 未被端口覆盖的旧版分区（later / hidden / favorites / searches）不读不写不删：
 * 删了就是丢数据，将来哪个端口需要时它们还在。
 */
class PrefsLibrary(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressPort, FavoritesPort, SettingsPort {

    // ---------- 内存态（全部由 [lock] 保护；port 方法可能从任意线程调） ----------

    private val lock = Any()

    /** 收藏系列：base（去季号归一化剧名）→ (当前季, addedAt)。当前季的 title 已替换为 base。 */
    private var favoriteRows: LinkedHashMap<String, Pair<Series, Long>>

    /** 季线缓存：base → 季列表（存储序 = 写入序）。LRU：最近写入的在最后。 */
    private var seasonLines: LinkedHashMap<String, List<Series>>

    /** 检查更新结果：季 id → 观察记录。只保留收藏系列的 id（见 [trackedIds]）。 */
    private var updateRows: LinkedHashMap<String, FavoriteUpdateRow>

    /** 观看历史：updatedAt 降序、按系列去重、截 [HISTORY_LIMIT]。series 保留盘上的元数据（标题/封面）。 */
    private var historyRows: List<HistoryRow>

    private var watchedIds: Set<String>
    private var settings: Settings

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-lite-library") }

    private data class HistoryRow(val series: Series, val progress: Progress)

    init {
        val values = store.all()
        favoriteRows = decodeFavoriteSeries(values[KEY_FAVORITE_SERIES] as? String)
        seasonLines = decodeSeasonGroups(values[KEY_SEASON_GROUPS] as? String)
        updateRows = decodeUpdates(values[KEY_FAVORITE_UPDATES] as? String)
        historyRows = decodeHistory(values[KEY_PROGRESS] as? String)
        watchedIds = (values[KEY_WATCHED] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        settings = loadSettings(values)
        if (!values.containsKey(KEY_FAVORITE_SERIES)) migrateLegacyFavorites(values)
    }

    // ---------- ProgressPort ----------

    override fun progressOf(seriesId: String): Progress? = synchronized(lock) {
        historyRows.firstOrNull { it.progress.seriesId == seriesId }?.progress
    }

    override fun history(limit: Int): List<Progress> = synchronized(lock) {
        historyRows.take(limit).map { it.progress }
    }

    override fun isWatched(seriesId: String): Boolean = synchronized(lock) { seriesId in watchedIds }

    /** 已看高水位：最新记录之前各集都算看过；当前集看完（completed）则含当前集。无记录返回 0。 */
    override fun watchedUpTo(seriesId: String): Int = synchronized(lock) {
        val row = historyRows.firstOrNull { it.progress.seriesId == seriesId }?.progress ?: return 0
        if (row.completed) row.episodeIndex + 1 else row.episodeIndex
    }

    override suspend fun save(progress: Progress) {
        val changed = synchronized(lock) {
            val previous = historyRows.firstOrNull { it.progress.seriesId == progress.seriesId }
            // 暂停中的同刻度检查点不刷新时间戳、不触发写盘（旧行为）
            if (previous != null && previous.progress.copy(updatedAt = progress.updatedAt) == progress) return
            // 新记录只有 id，没有标题：沿用盘上已有行的元数据，历史页的标题不因为续看一次而消失
            val series = previous?.series ?: Series(id = progress.seriesId, title = "")
            historyRows = (listOf(HistoryRow(series, progress)) + historyRows.filterNot { it.progress.seriesId == progress.seriesId })
                .take(HISTORY_LIMIT)
                .sortedByDescending { it.progress.updatedAt }
            true
        }
        if (changed) persist(KEY_PROGRESS)
    }

    override suspend fun setWatched(seriesId: String, watched: Boolean) {
        val changed = synchronized(lock) {
            if ((seriesId in watchedIds) == watched) return
            watchedIds = if (watched) watchedIds + seriesId else watchedIds - seriesId
            true
        }
        if (changed) persist(KEY_WATCHED)
    }

    override suspend fun removeHistory(seriesId: String) {
        synchronized(lock) {
            historyRows = historyRows.filterNot { it.progress.seriesId == seriesId }
            watchedIds = watchedIds - seriesId
        }
        persist(KEY_PROGRESS, KEY_WATCHED)
    }

    override suspend fun clearHistory() {
        synchronized(lock) {
            historyRows = emptyList()
            watchedIds = emptySet()
        }
        persist(KEY_PROGRESS, KEY_WATCHED)
    }

    // ---------- FavoritesPort ----------

    /** 收藏的全部系列（按收藏时间倒序）。 */
    override fun all(): List<FavoriteEntry> = synchronized(lock) {
        favoriteRows.entries.sortedByDescending { it.value.second }.map { (base, value) ->
            FavoriteEntry(
                series = value.first,
                addedAt = value.second,
                status = statusOf(value.first.id),
                latestSeason = latestSeasonOf(base, value.first),
            )
        }
    }

    override fun isFavorite(seriesKey: String): Boolean = synchronized(lock) { seriesKey in favoriteRows }

    override fun entry(seriesKey: String): FavoriteEntry? = synchronized(lock) {
        favoriteRows[seriesKey]?.let { (series, addedAt) ->
            FavoriteEntry(series, addedAt, statusOf(series.id), latestSeasonOf(seriesKey, series))
        }
    }

    /** 收藏/取消收藏一个系列（base = 去季号后的剧名）。返回 true 表示已收藏。 */
    override suspend fun toggle(series: Series): Boolean {
        val base = TitleKeys.seasonKey(series.title)
        if (base.isBlank()) return false
        val added = synchronized(lock) {
            if (favoriteRows.containsKey(base)) {
                favoriteRows.remove(base)
                false
            } else {
                favoriteRows[base] = series.copy(title = base) to clock()
                true
            }
        }
        persist(KEY_FAVORITE_SERIES)
        pruneUpdates()
        return added
    }

    override fun seasonLine(seriesKey: String): SeasonLine? = synchronized(lock) {
        seasonLines[seriesKey]?.takeIf { it.size >= 2 }?.let { SeasonLine(it) }
    }

    /** 季线落库（LRU 保留 [SEASON_LINE_LIMIT] 个系列）。少于两季不落：单季没有"线"可言。 */
    override fun rememberSeasonLine(seriesKey: String, line: SeasonLine) {
        if (seriesKey.isBlank() || line.seasons.size < 2) return
        // 只存 id/title/cover/badge/season（剥掉简介与分类省空间，单条 ~300B）；
        // 打开详情时用接口结果回填。
        val clean = line.seasons.map { it.copy(intro = "", tags = "") }
        synchronized(lock) {
            seasonLines.remove(seriesKey)
            seasonLines[seriesKey] = clean
            while (seasonLines.size > SEASON_LINE_LIMIT) seasonLines.remove(seasonLines.keys.first())
        }
        persist(KEY_SEASON_GROUPS)
        pruneUpdates()
    }

    /**
     * 「检查更新」回报：该季现在共 totalEpisodes 集。seen 记录"已确认到的集数"，
     * 状态由 added = total - seen 派生。端口只有这一个写入口，规则定为：
     * - 首次检查：seen = total，不算更新（首次落库不打 NEW_SEASON 标签，旧行为）；
     * - total 增长：seen 不动 → NEW_SEASON，等用户看过这一季后再调一次（total 不变）
     *   走下面的"确认"分支消掉标签；
     * - total 没涨：seen 提到 total → UP_TO_DATE。
     */
    override fun acknowledge(seriesId: String, totalEpisodes: Int, at: Long) {
        val changed = synchronized(lock) {
            // 非收藏系列的检查结果不留：没主的 update 行会在 pruneUpdates 里全量漂移
            if (seriesId !in trackedIds()) return
            val next = when (val previous = updateRows[seriesId]) {
                null -> FavoriteUpdateRow.first(totalEpisodes, at)
                else -> if (totalEpisodes > previous.total) {
                    FavoriteUpdateRow(totalEpisodes, previous.seen, at)
                } else {
                    FavoriteUpdateRow(totalEpisodes, totalEpisodes, at)
                }
            }
            if (updateRows[seriesId] == next) false else {
                updateRows[seriesId] = next
                true
            }
        }
        if (changed) persist(KEY_FAVORITE_UPDATES)
    }

    /** 收藏卡片的更新状态：新一季上线 > 已是最新 > 尚未检查。 */
    private fun statusOf(seriesId: String): FavoriteStatus = when (val row = updateRows[seriesId]) {
        null -> FavoriteStatus.UNCHECKED
        else -> if (row.added > 0) FavoriteStatus.NEW_SEASON else FavoriteStatus.UP_TO_DATE
    }

    private fun latestSeasonOf(base: String, current: Series): Int {
        val candidates = listOfNotNull(current.season.takeIf { it > 0 }) +
            seasonLines[base].orEmpty().map { it.season }.filter { it > 0 }
        return candidates.maxOrNull() ?: 0
    }

    /** 需要跟踪检查结果的季 id：收藏的当前季 + 收藏系列季线里的全部季。 */
    private fun trackedIds(): Set<String> = buildSet {
        favoriteRows.forEach { (base, value) ->
            add(value.first.id)
            seasonLines[base]?.forEach { add(it.id) }
        }
    }

    /** 清掉不再被收藏覆盖的检查结果，防止 update 行随增删收藏无限漂移。 */
    private fun pruneUpdates() {
        val changed = synchronized(lock) {
            val tracked = trackedIds()
            val next = updateRows.filterKeys { it in tracked }
            if (next.size == updateRows.size) false else {
                updateRows = LinkedHashMap(next)
                true
            }
        }
        if (changed) persist(KEY_FAVORITE_UPDATES)
    }

    // ---------- SettingsPort ----------

    override fun current(): Settings = synchronized(lock) { settings }

    override suspend fun update(transform: (Settings) -> Settings) {
        val changedKeys = synchronized(lock) {
            val next = normalizeSettings(transform(settings))
            val keys = mutableListOf<String>()
            if (next.quality != settings.quality) keys += "quality"
            if (next.speed != settings.speed) keys += "playbackSpeed"
            if (next.frame != settings.frame) keys += "frameMode"
            if (next.autoNext != settings.autoNext) keys += "autoNext"
            if (next.filter != settings.filter) keys += "contentType"
            if (next.startupTab != settings.startupTab) keys += "startupTab"
            if (next.seasonSortAsc != settings.seasonSortAsc) keys += "seasonSortAsc"
            settings = next
            keys
        }
        if (changedKeys.isNotEmpty()) persist(*changedKeys.toTypedArray())
    }

    // ---------- 迁移：旧版 prefs → 收藏系列语义 ----------

    /**
     * 旧版收藏的是"具体某季"；现语义收藏系列（去季号 base），同名 base 保留最新一条。
     * 迁移标记就是 favoriteSeries 键本身存在与否——它最少也写成 "{}"，
     * 与旧版 migrateLegacyFavorites 的口径一致：不同步就会把检查结果当成无主数据丢掉。
     */
    private fun migrateLegacyFavorites(values: Map<String, Any?>) {
        val legacy = SeriesCodec.decodeList(values["favorites"] as? String)
        val all = LinkedHashMap<String, Pair<Series, Long>>()
        legacy.forEach { series ->
            val base = TitleKeys.seasonKey(series.title)
            if (base.isNotBlank()) all[base] = series.copy(title = base) to clock()
        }
        synchronized(lock) { favoriteRows = all }
        persist(KEY_FAVORITE_SERIES)
    }

    // ---------- 磁盘编解码 ----------

    private fun persist(vararg keys: String) {
        val batch = buildBatch(keys.toList())
        writer.execute {
            try {
                store.commit(batch)
            } catch (cause: Throwable) {
                // 磁盘失败不回滚内存（内存是最新事实），下一次写同一分区会整段重写；留痕供诊断
                lastWriteFailure = cause
            }
        }
    }

    /** 最近一次落盘失败（null = 没失败过）。诊断端口接入前先留个观察口。 */
    @Volatile
    var lastWriteFailure: Throwable? = null
        private set

    private fun buildBatch(keys: List<String>): Map<String, Any?> = synchronized(lock) {
        val batch = LinkedHashMap<String, Any?>()
        keys.forEach { key ->
            when (key) {
                KEY_FAVORITE_SERIES -> batch[key] = encodeFavoriteSeries(favoriteRows)
                KEY_SEASON_GROUPS -> batch[key] = encodeSeasonGroups(seasonLines)
                KEY_FAVORITE_UPDATES -> batch[key] = encodeUpdates(updateRows)
                KEY_PROGRESS -> batch[key] = encodeHistory(historyRows)
                KEY_WATCHED -> batch[key] = watchedIds
                "quality" -> batch[key] = settings.quality
                "playbackSpeed" -> batch[key] = settings.speed
                "autoNext" -> batch[key] = settings.autoNext
                "contentType" -> batch[key] = settings.filter.toStored()
                "frameMode" -> batch[key] = settings.frame.name
                "startupTab" -> batch[key] = settings.startupTab
                "seasonSortAsc" -> batch[key] = settings.seasonSortAsc
                else -> throw LocalError.Storage(IllegalStateException("未知的本地库分区：$key"))
            }
        }
        batch
    }

    private fun encodeFavoriteSeries(rows: Map<String, Pair<Series, Long>>): String {
        val obj = JSONObject()
        rows.forEach { (base, value) ->
            obj.put(base, JSONObject().put("series", SeriesCodec.toJson(value.first)).put("addedAt", value.second))
        }
        return obj.toString()
    }

    private fun decodeFavoriteSeries(raw: String?): LinkedHashMap<String, Pair<Series, Long>> {
        val result = LinkedHashMap<String, Pair<Series, Long>>()
        if (raw.isNullOrEmpty()) return result
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        obj.keys().forEach { base ->
            runCatching {
                val entry = obj.getJSONObject(base)
                val series = SeriesCodec.fromJson(entry.getJSONObject("series"))
                result[base] = series.copy(title = base) to entry.optLong("addedAt")
            }
        }
        return LinkedHashMap(result.toList().take(FAVORITES_LIMIT).toMap())
    }

    private fun encodeSeasonGroups(rows: Map<String, List<Series>>): String {
        val obj = JSONObject()
        rows.forEach { (base, seasons) -> obj.put(base, JSONArray(seasons.map { SeriesCodec.toJson(it) })) }
        return obj.toString()
    }

    private fun decodeSeasonGroups(raw: String?): LinkedHashMap<String, List<Series>> {
        val result = LinkedHashMap<String, List<Series>>()
        if (raw.isNullOrEmpty()) return result
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        obj.keys().forEach { base ->
            runCatching { obj.getJSONArray(base) }.getOrNull()?.let { arr ->
                val seasons = SeriesCodec.decodeList(arr.toString())
                if (seasons.isNotEmpty()) result[base] = seasons
            }
        }
        return result
    }

    private fun encodeUpdates(rows: Map<String, FavoriteUpdateRow>): String {
        val obj = JSONObject()
        rows.forEach { (id, row) -> obj.put(id, row.toJson()) }
        return obj.toString()
    }

    private fun decodeUpdates(raw: String?): LinkedHashMap<String, FavoriteUpdateRow> {
        val result = LinkedHashMap<String, FavoriteUpdateRow>()
        if (raw.isNullOrEmpty()) return result
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        obj.keys().forEach { id ->
            FavoriteUpdateRow.fromJson(obj.optJSONObject(id))?.let { result[id] = it }
        }
        return result
    }

    private fun encodeHistory(rows: List<HistoryRow>): String = JSONArray(rows.map { row ->
        JSONObject()
            .put("series", SeriesCodec.toJson(row.series))
            .put("episodeId", row.progress.episodeVid)
            .put("episodeIndex", row.progress.episodeIndex)
            .put("position", row.progress.positionMs)
            .put("duration", row.progress.durationMs)
            .put("completed", row.progress.completed)
            .put("updatedAt", row.progress.updatedAt)
    }).toString()

    private fun decodeHistory(raw: String?): List<HistoryRow> {
        if (raw.isNullOrEmpty()) return emptyList()
        val rows = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val o = rows.getJSONObject(i)
                val series = runCatching { SeriesCodec.fromJson(o.getJSONObject("series")) }
                    .getOrElse { Series(id = o.optString("episodeId"), title = "") }
                HistoryRow(
                    series,
                    Progress(
                        seriesId = series.id,
                        episodeVid = o.getString("episodeId"),
                        episodeIndex = o.getInt("episodeIndex"),
                        positionMs = o.getLong("position"),
                        durationMs = o.getLong("duration"),
                        completed = o.optBoolean("completed"),
                        updatedAt = o.getLong("updatedAt"),
                    ),
                )
            }.getOrNull()
        }
            .sortedByDescending { it.progress.updatedAt }
            .distinctBy { it.progress.seriesId }
            .take(HISTORY_LIMIT)
    }

    private fun loadSettings(values: Map<String, Any?>): Settings = normalizeSettings(
        Settings(
            quality = (values["quality"] as? Number)?.toInt() ?: 1080,
            speed = (values["playbackSpeed"] as? Number)?.toFloat() ?: 1f,
            frame = FrameMode.entries.firstOrNull { it.name == values["frameMode"] } ?: FrameMode.FIT,
            autoNext = values["autoNext"] as? Boolean ?: true,
            filter = ContentFilter.entries.firstOrNull { it.name.equals(values["contentType"] as? String, ignoreCase = true) }
                ?: ContentFilter.ALL,
            startupTab = (values["startupTab"] as? Number)?.toInt() ?: 0,
            seasonSortAsc = values["seasonSortAsc"] as? Boolean ?: false,
        ),
    )

    /** 落盘值可能被外部改坏：读回时一律过一遍合法值域（旧行为）。 */
    private fun normalizeSettings(value: Settings): Settings = value.copy(
        quality = value.quality.takeIf { it in QUALITY_OPTIONS } ?: 1080,
        speed = value.speed.takeIf { it in SPEED_OPTIONS } ?: 1f,
        startupTab = value.startupTab.coerceIn(0, 3),
    )

    private fun ContentFilter.toStored(): String = name.lowercase()

    /** 等待排队中的写盘完成（仅退出与测试用；端口契约是"调用方不关心写盘时机"）。 */
    fun flush() {
        writer.submit {}.get()
    }

    private companion object {
        const val KEY_FAVORITE_SERIES = "favoriteSeries"
        const val KEY_SEASON_GROUPS = "seasonGroups"
        const val KEY_FAVORITE_UPDATES = "favoriteUpdates"
        const val KEY_PROGRESS = "progress"
        const val KEY_WATCHED = "watched"

        const val HISTORY_LIMIT = 30
        const val FAVORITES_LIMIT = 500
        const val SEASON_LINE_LIMIT = 20

        val QUALITY_OPTIONS = listOf(480, 720, 1080)
        val SPEED_OPTIONS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}

/**
 * 检查更新的观察记录（旧版 FavoriteUpdate 的存储形态，键名不变）。
 * added 是高水位口径：total 只增不减，临时下架不算"新一季"。
 */
internal data class FavoriteUpdateRow(val total: Int, val seen: Int, val checkedAt: Long) {
    val added: Int get() = (total - seen).coerceAtLeast(0)

    fun observe(count: Int, now: Long, acknowledge: Boolean): FavoriteUpdateRow {
        require(count > 0)
        if (now < checkedAt) return this
        return FavoriteUpdateRow(count, if (acknowledge) maxOf(seen, count) else seen, now)
    }

    fun toJson(): JSONObject = JSONObject().put("total", total).put("seen", seen).put("checkedAt", checkedAt)

    companion object {
        fun first(count: Int, now: Long): FavoriteUpdateRow {
            require(count > 0)
            return FavoriteUpdateRow(count, count, now)
        }

        fun fromJson(value: JSONObject?): FavoriteUpdateRow? = runCatching {
            require(value != null)
            val total = value.getInt("total")
            val seen = value.getInt("seen")
            val time = value.getLong("checkedAt")
            require(total > 0 && seen > 0 && time >= 0)
            FavoriteUpdateRow(total, seen, time)
        }.getOrNull()
    }
}
