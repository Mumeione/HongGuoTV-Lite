package com.hongguotv.lite.data.library

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.model.PlaybackOptions
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.SeriesIds
import com.hongguotv.core.model.Settings
import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.ProgressPort
import com.hongguotv.core.port.SettingsPort
import com.hongguotv.core.port.SilentDiagnostics
import com.hongguotv.lite.data.internal.evictOldestUntil
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
 * 未被端口覆盖的旧版分区（later / hidden / favorites / searches / watched）不读不写不删：
 * 删了就是丢数据，将来哪个端口需要时它们还在。
 *
 * `watched` 属于这一类：它存的是"手动标记已看"，而手动标记的入口已经不存在——读它只会让
 * 界面显示一批永远不会变的旧值。"看过"的唯一事实是 [watchedUpTo]（从 [historyRows] 派生）。
 */
class PrefsLibrary(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 诊断出口：坏分区与写盘失败各报一条 event。
     * 这两件事只落在 [corruptPartitions] / [lastWriteFailure] 两个字段里的话，生产侧没人读——
     * 磁盘满时用户静默丢收藏与进度，logcat 一行都没有。
     */
    private val diagnostics: DiagnosticsPort = SilentDiagnostics,
) : ProgressPort, FavoritesPort, SettingsPort {

    // ---------- 内存态（全部由 [lock] 保护；port 方法可能从任意线程调） ----------

    private val lock = Any()

    /** 收藏系列：base（去季号归一化剧名）→ (当前季, addedAt)。当前季的 title 已替换为 base。 */
    private var favoriteRows: LinkedHashMap<String, Pair<Series, Long>>

    /** 季线缓存：base → 季列表（存储序 = 写入序）。LRU：最近写入的在最后。 */
    private var seasonLines: LinkedHashMap<String, List<Series>>

    /** 一条已缓存的季线：它挂在哪个键下、季列表是什么。 */
    private data class CachedLine(val key: String, val seasons: List<Series>)

    /** 检查更新结果：季 id → 观察记录。只保留收藏系列的 id（见 [trackedIds]）。 */
    private var updateRows: LinkedHashMap<String, FavoriteUpdateRow>

    /** 观看历史：updatedAt 降序、按系列去重、截 [HISTORY_LIMIT]。series 保留盘上的元数据（标题/封面）。 */
    private var historyRows: List<HistoryRow>

    private var settings: Settings

    /**
     * 启动加载时无法解析、被整段丢弃的分区（对应 LocalError.Corrupt 的分类位置）。
     * 加载失败不炸启动（旧行为：坏分区按空处理），但必须留痕——默默丢数据是迁移大忌。
     */
    var corruptPartitions: List<String> = emptyList()
        private set

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-lite-library") }

    private data class HistoryRow(val series: Series, val progress: Progress)

    init {
        val values = store.all()
        val corrupt = mutableListOf<String>()
        favoriteRows = decodeFavoriteSeries(values[KEY_FAVORITE_SERIES] as? String) { corrupt += KEY_FAVORITE_SERIES }
        seasonLines = decodeSeasonGroups(values[KEY_SEASON_GROUPS] as? String) { corrupt += KEY_SEASON_GROUPS }
        updateRows = decodeUpdates(values[KEY_FAVORITE_UPDATES] as? String) { corrupt += KEY_FAVORITE_UPDATES }
        historyRows = decodeHistory(values[KEY_PROGRESS] as? String) { corrupt += KEY_PROGRESS }
        settings = loadSettings(values)
        if (!values.containsKey(KEY_FAVORITE_SERIES)) migrateLegacyFavorites(values)
        // 旧收藏行按季线里的 id 证据换到系列基名（篇名季的标题算不出基名）
        if (migrateCanonicalFavoriteKeys()) persist(KEY_FAVORITE_SERIES, KEY_SEASON_GROUPS)
        corruptPartitions = corrupt.toList()
        if (corrupt.isNotEmpty()) {
            diagnostics.event("prefs.corrupt", mapOf("partitions" to corrupt.joinToString(",")))
        }
    }

    // ---------- ProgressPort ----------

    override fun progressOf(seriesId: String): Progress? = synchronized(lock) {
        historyRows.firstOrNull { it.progress.seriesId == seriesId }?.progress
    }

    override fun history(limit: Int): List<HistoryEntry> = synchronized(lock) {
        val known = knownSeriesById()
        historyRows.take(limit).map { HistoryEntry(enrich(it.series, known[it.progress.seriesId]), it.progress) }
    }

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
            // 新记录只有 id，没有标题：沿用盘上已有行的元数据，历史页的标题不因为续看一次而消失。
            // 盘上也没有（第一次看这一季）时，从收藏/季线里按 id 取一份——否则历史页会是一条无名黑块，
            // 信息栏也查不到收藏状态与分类（2026-10-24 验收："有的啥也没有"）。
            val base = previous?.series ?: Series(id = progress.seriesId, title = "")
            val series = enrich(base, knownSeriesById()[progress.seriesId])
            historyRows = (listOf(HistoryRow(series, progress)) + historyRows.filterNot { it.progress.seriesId == progress.seriesId })
                .take(HISTORY_LIMIT)
                .sortedByDescending { it.progress.updatedAt }
            true
        }
        if (changed) persist(KEY_PROGRESS)
    }

    /**
     * 按 id 索引"已知的作品元数据"：季线优先，收藏行兜底（收藏行的标题已被换成系列基名，
     * 只适合补空字段，不适合覆盖季名）。
     *
     * 只读内存里的缓存，不发请求。季线最多 20 条、每条最多几十季，取一份索引是微秒级。
     */
    private fun knownSeriesById(): Map<String, Series> {
        val index = HashMap<String, Series>()
        seasonLines.values.forEach { seasons -> seasons.forEach { index[it.id] = it } }
        // 收藏行只补季线里没有的 id（putIfAbsent 是 API 24，minSdk 23 上不能用）
        favoriteRows.values.forEach { (series, _) ->
            if (!index.containsKey(series.id)) index[series.id] = series
        }
        return index
    }

    /**
     * 用已知元数据补一条历史行的空字段。**只补空**，不覆盖：历史行可能带着当次详情拿到的
     * 完整季名，而收藏行的标题是系列基名，覆盖会把"第二季"抹掉。
     */
    private fun enrich(base: Series, known: Series?): Series {
        if (known == null) return base
        return base.copy(
            title = base.title.ifBlank { known.title },
            cover = base.cover.ifBlank { known.cover },
            intro = base.intro.ifBlank { known.intro },
            badge = base.badge.ifBlank { known.badge },
            tags = base.tags.ifBlank { known.tags },
            season = base.season.takeIf { it > 0 } ?: known.season,
            episodeCount = base.episodeCount.takeIf { it > 0 } ?: known.episodeCount,
        )
    }

    override suspend fun rememberSeries(series: Series) {
        val changed = synchronized(lock) {
            val index = historyRows.indexOfFirst { it.progress.seriesId == series.id }
            // 没有这一行的历史就不写：浏览详情不等于看过
            if (index < 0) return
            val row = historyRows[index]
            // 详情接口没有季号/角标（只有 plan 与官网搜索有）：空值不能把盘上已有的覆盖掉，
            // 否则卡片角标会从「第一季 第4集」退成「第4集」
            val merged = series.copy(
                cover = series.cover.ifBlank { row.series.cover },
                intro = series.intro.ifBlank { row.series.intro },
                badge = series.badge.ifBlank { row.series.badge },
                tags = series.tags.ifBlank { row.series.tags },
                season = series.season.takeIf { it > 0 } ?: row.series.season,
            )
            if (row.series == merged) return
            historyRows = historyRows.toMutableList().also { it[index] = row.copy(series = merged) }
            true
        }
        if (changed) persist(KEY_PROGRESS)
    }

    override suspend fun removeHistory(seriesId: String) {
        synchronized(lock) {
            historyRows = historyRows.filterNot { it.progress.seriesId == seriesId }
        }
        persist(KEY_PROGRESS)
    }

    override suspend fun clearHistory() {
        synchronized(lock) {
            historyRows = emptyList()
        }
        persist(KEY_PROGRESS)
    }

    // ---------- FavoritesPort ----------

    /** 收藏的全部系列（按收藏时间倒序）。 */
    override fun all(): List<FavoriteEntry> = synchronized(lock) {
        favoriteRows.entries.sortedByDescending { it.value.second }.map { (base, value) ->
            FavoriteEntry(
                series = value.first,
                addedAt = value.second,
                status = statusOf(value.first.id),
                seriesKey = base,
                latestSeason = latestSeasonOf(base, value.first),
                latestEpisodes = latestEpisodesOf(base, value.first),
                checkedAt = updateRows[value.first.id]?.checkedAt ?: 0L,
            )
        }
    }

    override fun isFavorite(seriesKey: String): Boolean = synchronized(lock) { seriesKey in favoriteRows }

    override fun entry(seriesKey: String): FavoriteEntry? = synchronized(lock) {
        favoriteRows[seriesKey]?.let { (series, addedAt) ->
            FavoriteEntry(
                series = series,
                addedAt = addedAt,
                status = statusOf(series.id),
                seriesKey = seriesKey,
                latestSeason = latestSeasonOf(seriesKey, series),
                latestEpisodes = latestEpisodesOf(seriesKey, series),
                checkedAt = updateRows[series.id]?.checkedAt ?: 0L,
            )
        }
    }

    /** 收藏/取消收藏一个系列（base = 去季号后的剧名）。返回 true 表示已收藏。 */
    override suspend fun toggle(series: Series): Boolean {
        val titleKey = SeasonKeys.seasonKey(series.title)
        if (titleKey.isBlank()) return false
        var lineMoved = false
        val added = synchronized(lock) {
            val cached = cachedLineOf(series)
            // 有季线证据就用线里的基名：篇名季的标题算不出系列基名，
            // 收藏行不能挂在篇名上（收藏页会把篇名当系列名显示）。
            val key = cached?.let { SeasonKeys.canonicalKey(it.seasons) } ?: titleKey
            // 已收藏的判定：规范键、标题键，或同一季线里的旧行都算——否则取消收藏会留下孤儿
            val existing = favoriteRows.keys.firstOrNull { stored ->
                stored == key || stored == titleKey ||
                    seasonLines[stored]?.any { it.id == series.id } == true
            }
            if (existing != null) {
                favoriteRows.remove(existing)
                false
            } else {
                favoriteRows[key] = series.copy(title = key) to clock()
                evictOldestFavoriteLocked()
                // 季线跟着换到规范键：否则系列页按主键读不到线，会重新请求一次
                if (cached != null) {
                    rekeyLine(cached, key)
                    lineMoved = cached.key != key
                }
                true
            }
        }
        persist(KEY_FAVORITE_SERIES)
        if (lineMoved) persist(KEY_SEASON_GROUPS)
        pruneUpdates()
        return added
    }

    /**
     * 包含这一季 id 的季线缓存；没有返回 null。
     *
     * 只看内存，不发请求；季线本身已按同 IP 作品过滤，所以"id 在这条线里"是可靠证据。
     */
    private fun cachedLineOf(series: Series): CachedLine? =
        seasonLines.entries.firstOrNull { (_, seasons) -> seasons.any { it.id == series.id } }
            ?.let { CachedLine(it.key, it.value) }

    /** 把季线换挂到 [key] 下；同键已有缓存时保留既有那份（内容等价）。 */
    private fun rekeyLine(line: CachedLine, key: String) {
        if (line.key == key) return
        seasonLines.remove(line.key)
        if (!seasonLines.containsKey(key)) seasonLines[key] = line.seasons
    }

    /**
     * 收藏行的容量口径：**只留 `addedAt` 最新的 [FAVORITES_LIMIT] 条**。
     *
     * 解码端、迁移端、写入端共用这一份：三处各截各的就会出现"读回来留最旧的、写进去淘汰最旧的"
     * （旧版上限 500 → 100 时按存储序 `take` 截断，被丢掉的是最新那批收藏）。
     *
     * 同一 `addedAt` 的行按**插入序后者更新**（升序稳定排序取尾 N）：时钟精度不够或
     * 测试里注入固定时钟时，刚收藏的那条才不会被当成最旧的淘汰掉。保留的部分仍按插入序排，
     * 与旧口径（`all()` 再按 `addedAt` 倒排）一致。
     */
    private fun keepNewest(
        rows: Map<String, Pair<Series, Long>>,
    ): LinkedHashMap<String, Pair<Series, Long>> = LinkedHashMap(
        rows.entries.sortedBy { it.value.second }.takeLast(FAVORITES_LIMIT).associate { it.key to it.value },
    )

    /** 会话内连续收藏超过上限时按 [keepNewest] 收口（与解码端、迁移端同一份口径）。 */
    private fun evictOldestFavoriteLocked() {
        if (favoriteRows.size > FAVORITES_LIMIT) favoriteRows = keepNewest(favoriteRows)
    }

    /**
     * 把 [ids] 对应的收藏行搬到 [canonical] 键上：行里存的 title 换成基名，
     * 同键已有收藏时保留既有那条（两者指向同一个系列）。
     *
     * @return 是否真的搬过（搬过才需要写收藏分区）
     */
    private fun moveRowsInto(canonical: String, ids: Set<String>): Boolean {
        val moving = favoriteRows.filter { (stored, value) -> stored != canonical && value.first.id in ids }
        if (moving.isEmpty()) return false
        moving.forEach { (stored, value) ->
            favoriteRows.remove(stored)
            if (!favoriteRows.containsKey(canonical)) {
                favoriteRows[canonical] = value.first.copy(title = canonical) to value.second
            }
        }
        return true
    }

    override fun seasonLine(seriesKey: String): SeasonLine? = synchronized(lock) {
        seasonLines[seriesKey]?.takeIf { it.size >= 2 }?.let { SeasonLine(it) }
    }

    /**
     * 换"当前季"。存进去的 title 仍然换成系列基名——收藏行的 title 是 base，这条不变量不能破
     * （季卡上的季名来自季线，不来自这一行）。
     */
    override suspend fun setCurrentSeason(seriesKey: String, series: Series) {
        synchronized(lock) {
            val existing = favoriteRows[seriesKey] ?: return
            val next = series.copy(title = seriesKey)
            if (existing.first == next) return
            favoriteRows[seriesKey] = next to existing.second
        }
        persist(KEY_FAVORITE_SERIES)
    }

    /** 季线落库（LRU 保留 [SEASON_LINE_LIMIT] 个系列）。少于两季不落：单季没有"线"可言。 */
    override fun rememberSeasonLine(seriesKey: String, line: SeasonLine) {
        if (seriesKey.isBlank() || line.seasons.size < 2) return
        // 只存 id/title/cover/badge/season/分类（简介最占空间，剥掉；分类是收藏详情页
        // 「分类」行的唯一来源，保留——短字符串，五个季也才几百字节）。
        val clean = line.seasons.map { it.copy(intro = "") }
        var favoritesChanged = false
        synchronized(lock) {
            val ids = line.seasons.mapTo(HashSet(line.seasons.size)) { it.id }
            val canonical = SeasonKeys.canonicalKey(line.seasons)
            // 只有规范键确实被收藏行使用时才换键：收藏行挂在篇名键上（收藏早于季线到手）
            // 时就地把行与线一起归到基名；没人收藏的篇名季线仍按调用方给的键缓存，
            // 详情页下次按标题键能直接命中，不会为同一条线重复请求。
            val inUse = canonical != null &&
                (favoriteRows.containsKey(canonical) ||
                    favoriteRows.any { (stored, value) -> stored != canonical && value.first.id in ids })
            val key = if (inUse) canonical!! else seriesKey
            if (inUse) favoritesChanged = moveRowsInto(key, ids)
            seasonLines.remove(seriesKey)
            if (key != seriesKey) seasonLines.remove(key)
            seasonLines[key] = clean
            seasonLines.evictOldestUntil(SEASON_LINE_LIMIT)
        }
        if (favoritesChanged) persist(KEY_SEASON_GROUPS, KEY_FAVORITE_SERIES) else persist(KEY_SEASON_GROUPS)
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
        // 只认已上线季：季线里会保留"未上线季"占位（episode_cnt=0），
        // 把它算进最新季，收藏角标就会写成"第十季已上线"（维护者实测）。
        val candidates = listOfNotNull(current.season.takeIf { it > 0 }) +
            seasonLines[base].orEmpty().filter { it.released }.map { it.season }.filter { it > 0 }
        return candidates.maxOrNull() ?: 0
    }

    /**
     * 最新一季现在的集数——收藏卡"更新到多少集"的数据。
     * 多季剧读季线里最新已上线季的 [Series.episodeCount]；季线没有（单季剧、
     * 或季线还没取到）时退回检查更新记录的 total（检查更新问的就是详情集数）。
     */
    private fun latestEpisodesOf(base: String, current: Series): Int {
        val latest = latestSeasonOf(base, current)
        if (latest > 0) {
            seasonLines[base]?.firstOrNull { it.released && it.season == latest }?.episodeCount?.takeIf { it > 0 }
                ?.let { return it }
        }
        return updateRows[current.id]?.total ?: 0
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
            if (next.quality != settings.quality) keys += SETTING_QUALITY
            if (next.speed != settings.speed) keys += SETTING_SPEED
            if (next.frame != settings.frame) keys += SETTING_FRAME
            if (next.autoNext != settings.autoNext) keys += SETTING_AUTO_NEXT
            if (next.filter != settings.filter) keys += SETTING_CONTENT_TYPE
            if (next.startupTab != settings.startupTab) keys += SETTING_STARTUP_TAB
            if (next.seasonSortAsc != settings.seasonSortAsc) keys += SETTING_SEASON_SORT_ASC
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
            val base = SeasonKeys.seasonKey(series.title)
            if (base.isNotBlank()) all[base] = series.copy(title = base) to clock()
        }
        synchronized(lock) {
            // 截断口径与写入端一致：按 addedAt 最旧淘汰（迁移时全部同刻，退化为插入序）
            favoriteRows = keepNewest(all)
        }
        persist(KEY_FAVORITE_SERIES)
        // 旧版口径：迁移完立即同步检查结果——旧的无主 update 行不该活过这次迁移
        pruneUpdates()
    }

    /**
     * 旧收藏行迁移：从篇名季收藏的行，键与 title 都是篇名。
     *
     * 只认季线里的 id 证据（线本身已按同 IP 过滤），有线就把行与线一起换到基名键；
     * 没有线就原样留着，**不按标题前缀猜**——那会把同 IP 作品误并组。
     */
    private fun migrateCanonicalFavoriteKeys(): Boolean {
        var changed = false
        seasonLines.entries.toList().forEach { (storedKey, seasons) ->
            val canonical = SeasonKeys.canonicalKey(seasons) ?: return@forEach
            val ids = seasons.mapTo(HashSet(seasons.size)) { it.id }
            // 行不在线里、规范键也没人用：这条线只是给未收藏的详情页做缓存，不换键
            val inUse = favoriteRows.containsKey(canonical) ||
                favoriteRows.any { (key, value) -> key != canonical && value.first.id in ids }
            if (!inUse) return@forEach
            changed = moveRowsInto(canonical, ids) || changed
            if (storedKey != canonical) {
                if (!seasonLines.containsKey(canonical)) seasonLines[canonical] = seasons
                seasonLines.remove(storedKey)
                changed = true
            }
        }
        return changed
    }

    // ---------- 磁盘编解码 ----------

    private fun persist(vararg keys: String) {
        val batch = buildBatch(keys.toList())
        // 外层：close() 之后仍可能有收尾中的协程走到这里（取消是协作式的），
        // 拒绝执行按"写盘失败"留痕，不炸调用方。内层：磁盘失败不回滚内存
        // （内存是最新事实），下一次写同一分区会整段重写；同样留痕供诊断。
        runCatching {
            writer.execute {
                try {
                    store.commit(batch)
                } catch (cause: Throwable) {
                    lastWriteFailure = cause
                    diagnostics.event(
                        "prefs.write.failed",
                        mapOf("keys" to keys.joinToString(","), "error" to cause.javaClass.simpleName),
                    )
                }
            }
        }.onFailure { cause ->
            // 写线程已经收摊（close 之后的收尾协程）：同样留痕，不炸调用方
            lastWriteFailure = cause
            diagnostics.event(
                "prefs.write.rejected",
                mapOf("keys" to keys.joinToString(","), "error" to cause.javaClass.simpleName),
            )
        }
    }

    /** 最近一次落盘失败（null = 没失败过）。对外留痕走 [diagnostics]，这里供测试与调试直接读。 */
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
                SETTING_QUALITY -> batch[key] = settings.quality
                SETTING_SPEED -> batch[key] = settings.speed
                SETTING_AUTO_NEXT -> batch[key] = settings.autoNext
                SETTING_CONTENT_TYPE -> batch[key] = settings.filter.toStored()
                SETTING_FRAME -> batch[key] = settings.frame.name
                SETTING_STARTUP_TAB -> batch[key] = settings.startupTab
                SETTING_SEASON_SORT_ASC -> batch[key] = settings.seasonSortAsc
                // 调用点拼错键名是编程错误（静态错误），按端口契约直接炸，不包 LocalError
                else -> throw IllegalArgumentException("未知的本地库分区：$key")
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

    private fun decodeFavoriteSeries(raw: String?, onPartitionCorrupt: () -> Unit = {}): LinkedHashMap<String, Pair<Series, Long>> {
        val result = LinkedHashMap<String, Pair<Series, Long>>()
        if (raw.isNullOrEmpty()) return result
        val obj = parseOrNull { JSONObject(raw) } ?: run { onPartitionCorrupt(); return result }
        obj.keys().forEach { base ->
            parseOrNull {
                val entry = obj.getJSONObject(base)
                val series = SeriesCodec.fromJson(entry.getJSONObject("series"))
                // 脏数据防线：id 不合法的行整条丢弃。这种 id 进到内容仓库会在
                // require(SeriesIds.ID.matches) 上炸出去（V1.0.0 审计 P1-3）。
                if (SeriesIds.ID.matches(series.id)) {
                    result[base] = series.copy(title = base) to entry.optLong("addedAt")
                }
            }
        }
        return keepNewest(result)
    }

    private fun encodeSeasonGroups(rows: Map<String, List<Series>>): String {
        val obj = JSONObject()
        rows.forEach { (base, seasons) -> obj.put(base, JSONArray(seasons.map { SeriesCodec.toJson(it) })) }
        return obj.toString()
    }

    private fun decodeSeasonGroups(raw: String?, onPartitionCorrupt: () -> Unit = {}): LinkedHashMap<String, List<Series>> {
        val result = LinkedHashMap<String, List<Series>>()
        if (raw.isNullOrEmpty()) return result
        val obj = parseOrNull { JSONObject(raw) } ?: run { onPartitionCorrupt(); return result }
        obj.keys().forEach { base ->
            parseOrNull { obj.getJSONArray(base) }?.let { arr ->
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

    private fun decodeUpdates(raw: String?, onPartitionCorrupt: () -> Unit = {}): LinkedHashMap<String, FavoriteUpdateRow> {
        val result = LinkedHashMap<String, FavoriteUpdateRow>()
        if (raw.isNullOrEmpty()) return result
        val obj = parseOrNull { JSONObject(raw) } ?: run { onPartitionCorrupt(); return result }
        obj.keys().forEach { id ->
            FavoriteUpdateRow.fromJson(obj.optJSONObject(id))?.let { result[id] = it }
        }
        return result
    }

    private fun encodeHistory(rows: List<HistoryRow>): String = JSONArray(rows.map { row ->
        JSONObject()
            // seriesId 显式落盘：`series` 是展示元数据，解不开时也不能拿 episodeId 冒充剧集 id
            .put("seriesId", row.progress.seriesId)
            .put("series", SeriesCodec.toJson(row.series))
            .put("episodeId", row.progress.episodeVid)
            .put("episodeIndex", row.progress.episodeIndex)
            .put("position", row.progress.positionMs)
            .put("duration", row.progress.durationMs)
            .put("completed", row.progress.completed)
            .put("updatedAt", row.progress.updatedAt)
    }).toString()

    private fun decodeHistory(raw: String?, onPartitionCorrupt: () -> Unit = {}): List<HistoryRow> {
        if (raw.isNullOrEmpty()) return emptyList()
        val rows = parseOrNull { JSONArray(raw) } ?: run { onPartitionCorrupt(); return emptyList() }
        // 单行坏掉不拖垮整段历史：跳过该行并留痕，绝不用 episodeId 冒充 seriesId
        var corrupt = false
        return (0 until rows.length()).mapNotNull { i ->
            val row = parseOrNull { rows.getJSONObject(i) } ?: return@mapNotNull null
            val parsed = parseOrNull { SeriesCodec.fromJson(row.getJSONObject("series")) }
                ?: run { corrupt = true; return@mapNotNull null }
            // 新数据优先读显式 seriesId；旧数据没有这个字段时退回 series.id（迁移兼容）
            val storedId = row.optString("seriesId")
            val seriesId = storedId.ifEmpty { parsed.id }
            if (seriesId.isBlank()) {
                corrupt = true
                return@mapNotNull null
            }
            parseOrNull {
                val series = if (storedId.isNotEmpty() && parsed.id != storedId) parsed.copy(id = storedId) else parsed
                HistoryRow(
                    series,
                    Progress(
                        seriesId = seriesId,
                        episodeVid = row.getString("episodeId"),
                        episodeIndex = row.getInt("episodeIndex"),
                        positionMs = row.getLong("position"),
                        durationMs = row.getLong("duration"),
                        completed = row.optBoolean("completed"),
                        updatedAt = row.getLong("updatedAt"),
                    ),
                )
            }
        }
            .also { if (corrupt) onPartitionCorrupt() }
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

    /** 落盘值可能被外部改坏：读回时一律过一遍合法值域（旧行为）。档位表在 core，三处共用一份。 */
    private fun normalizeSettings(value: Settings): Settings = value.copy(
        quality = PlaybackOptions.normalizeQuality(value.quality),
        speed = PlaybackOptions.normalizeSpeed(value.speed),
        startupTab = value.startupTab.coerceIn(0, 3),
    )

    private fun ContentFilter.toStored(): String = name.lowercase()

    /** 等待排队中的写盘完成（仅退出与测试用；端口契约是"调用方不关心写盘时机"）。 */
    fun flush() {
        writer.submit {}.get()
    }

    /**
     * flush 后停掉写线程（进程收摊用）：线程是非守护的且闭包持有
     * 全量内存态，Activity 每重建一轮就泄漏一条，必须显式收。幂等；之后 persist
     * 的写入被拒绝并留痕（见 [persist]），端口方法不会因此抛异常。
     */
    fun close() {
        runCatching { flush() }   // 二次调用时 submit 会被拒绝：runCatching 兜住保持幂等
        runCatching { writer.shutdown() }
    }

    private companion object {
        const val KEY_FAVORITE_SERIES = "favoriteSeries"
        const val KEY_SEASON_GROUPS = "seasonGroups"
        const val KEY_FAVORITE_UPDATES = "favoriteUpdates"
        const val KEY_PROGRESS = "progress"

        // 设置项与旧版 prefs 键名逐字相同（旧数据要能读）
        const val SETTING_QUALITY = "quality"
        const val SETTING_SPEED = "playbackSpeed"
        const val SETTING_AUTO_NEXT = "autoNext"
        const val SETTING_CONTENT_TYPE = "contentType"
        const val SETTING_FRAME = "frameMode"
        const val SETTING_STARTUP_TAB = "startupTab"
        const val SETTING_SEASON_SORT_ASC = "seasonSortAsc"

        const val HISTORY_LIMIT = 30

        /** 收藏上限（2026-10-26 容量治理：500 → 100）。老电视上 JSON 解析与封面磁盘都按它封顶。 */
        const val FAVORITES_LIMIT = 100
        const val SEASON_LINE_LIMIT = 20
    }
}

/**
 * 检查更新的观察记录（旧版 FavoriteUpdate 的存储形态，键名不变）。
 * added 是高水位口径：total 只增不减，临时下架不算"新一季"。
 */
internal data class FavoriteUpdateRow(val total: Int, val seen: Int, val checkedAt: Long) {
    val added: Int get() = (total - seen).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject().put("total", total).put("seen", seen).put("checkedAt", checkedAt)

    companion object {
        fun first(count: Int, now: Long): FavoriteUpdateRow {
            require(count > 0)
            return FavoriteUpdateRow(count, count, now)
        }

        /** 坏行返回 null（丢掉这一条的"检查结果"，不牵连整个分区）。 */
        fun fromJson(value: JSONObject?): FavoriteUpdateRow? = parseOrNull {
            requireNotNull(value)
            val total = value.getInt("total")
            val seen = value.getInt("seen")
            val time = value.getLong("checkedAt")
            require(total > 0 && seen > 0 && time >= 0)
            FavoriteUpdateRow(total, seen, time)
        }
    }
}
