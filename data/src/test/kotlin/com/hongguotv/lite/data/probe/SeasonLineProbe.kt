package com.hongguotv.lite.data.probe

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.SilentDiagnostics
import com.hongguotv.core.usecase.EpisodeFlow
import com.hongguotv.core.usecase.FavoriteLookup
import com.hongguotv.core.usecase.SeasonChain
import com.hongguotv.core.usecase.SeasonProgress
import com.hongguotv.lite.data.content.ContentRepository
import com.hongguotv.lite.data.content.DeviceSession
import com.hongguotv.lite.data.http.OkHttpTransport
import com.hongguotv.lite.data.library.KeyValueStore
import com.hongguotv.lite.data.library.PrefsLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 季线真网探针（一次性诊断，默认跳过）：
 *
 * ```sh
 * $env:HGTV_PROBE = "1"
 * $env:KEYWORDS = "魏家逆子成首辅|万妖图录传|聚宝仙盆|十八岁的二次回响"
 * ./gradlew :data:testDebugUnitTest --tests '*SeasonLineProbe*' --info --console=plain
 * ```
 *
 * 目的：把「用户在 app 里能看到的搜索卡片」与两条季线放在一起看——
 * 1. 搜索锚点（SearchModel 口径：第 1 页、基名相同、优先带季号）拉出来的线；
 * 2. 详情锚点（SeasonPage / 播放页口径：详情带 season_index + related_album_id）拉出来的线；
 * 3. 同名基座但不在季线里的同 IP 卡片（正传、3D版…）；
 * 4. 收藏归组（PrefsLibrary + FavoriteLookup 实跑）、跨季续播（SeasonChain/EpisodeFlow）。
 *
 * 限流：搜索端点自带 400ms 节流；其余请求每次间隔 [REQUEST_GAP_MS]，
 * 关键词之间再隔 [KEYWORD_GAP_MS]。一次跑只注册一台匿名设备。
 */
class SeasonLineProbe {

    @Test
    fun probe() {
        assumeTrue("设置 HGTV_PROBE=1 才跑真网探针", System.getenv("HGTV_PROBE") == "1")
        runBlocking {
            val diagnostics = SilentDiagnostics
            val transport = OkHttpTransport(diagnostics = diagnostics)
            // 与生产同一条路：匿名注册一次，之后所有内容端点都用这一对身份。
            val devices = DeviceSession(MemoryStore(), transport, diagnostics)
            devices.ensure()
            val content = ContentRepository(http = transport, devices = devices, diagnostics = diagnostics)

            val keywords = System.getenv("KEYWORDS")?.split('|')?.filter { it.isNotBlank() }
                ?: DEFAULT_KEYWORDS
            keywords.forEachIndexed { index, keyword ->
                if (index > 0) delay(KEYWORD_GAP_MS)
                probeKeyword(content, keyword)
            }
        }
    }

    private suspend fun probeKeyword(content: ContentRepository, keyword: String) {
        println()
        println("==================== [$keyword] ====================")
        val result = searchWithRetry(content, keyword) ?: return
        println(
            "[SEARCH] page=${result.page}/${result.totalPages} total=${result.totalCount} " +
                "items=${result.items.size}",
        )
        result.items.forEach { card ->
            println(
                "  card ${card.id} | title='${card.title}' | season=${card.season} | " +
                    "eps=${card.episodeCount} | released=${card.released} | badge='${card.badge}' | " +
                    "key='${SeasonKeys.seasonKey(card.title)}'",
            )
        }

        // SearchModel.anchor 的逐字口径：基名与关键词归一化相同，优先带季号的那张。
        // ANCHOR_ID 可以把锚点钉死在某张卡上（验证"从正传/同 IP 作品进系列页"这类特定路径）。
        val keywordKey = SeasonKeys.normalizeTitle(keyword)
        val matches = result.items.filter { SeasonKeys.seasonKey(it.title) == keywordKey }
        val forced = System.getenv("ANCHOR_ID")?.let { id -> result.items.firstOrNull { it.id == id } }
        val anchor = forced ?: (matches.firstOrNull { it.season > 0 } ?: matches.firstOrNull())
        println(
            "[ANCHOR] matches=${matches.map { "${it.id}/${it.title}" }} " +
                "forced=${forced?.id} " +
                "-> anchor=${anchor?.let { "${it.id}/${it.title}/S${it.season}" }}",
        )
        if (anchor == null) return

        // 1) 搜索卡片当锚点：app 搜索聚合走的就是这条路（卡片没有 albumId、季号只从标题来）。
        val fromSearchAnchor = request("SEASONS(search-anchor ${anchor.id})") {
            content.seasons(anchor.id, anchor)
        }
        fromSearchAnchor?.let { printLine("LINE(search-anchor)", it) }

        // 2) 详情当锚点：系列页 / 播放页 / 详情页走的是这条路（season_index + related_album_id）。
        val detail = request("DETAIL(${anchor.id})") { content.detail(anchor.id) }
        detail?.let {
            println(
                "[DETAIL] id=${it.series.id} title='${it.series.title}' season=${it.series.season} " +
                    "album='${it.series.albumId}' episodes=${it.episodes.size} " +
                    "base='${SeasonKeys.seasonBase(it.series.title)}' key='${SeasonKeys.seasonKey(it.series.title)}'",
            )
        }
        val fromDetail = if (detail != null) {
            request("SEASONS(detail-anchor ${detail.series.id})") {
                content.seasons(detail.series.id, detail.series)
            }
        } else {
            null
        }
        fromDetail?.let { printLine("LINE(detail-anchor)", it) }

        // 两条线差在哪：搜索聚合从"第 1 季篇名卡"起头时最容易丢第 1 季。
        if (fromSearchAnchor != null && fromDetail != null) {
            val a = fromSearchAnchor.seasons.map { it.id }
            val b = fromDetail.seasons.map { it.id }
            if (a == b) {
                println("[COMPARE] search-anchor == detail-anchor")
            } else {
                println("[COMPARE] DIFF search-anchor=$a detail-anchor=$b")
                println("  missingFromSearchAnchor=${b.filter { it !in a.toSet() }} extra=${a.filter { it !in b.toSet() }}")
            }
        }

        val line = fromDetail ?: fromSearchAnchor ?: return

        // 3) 同基名但不在季线里的卡片：正传 / 3D版 / 同 IP 番外。它们不该进季线。
        val lineIds = line.seasons.map { it.id }.toSet()
        println("[SAME-BASE] 基名相同但不在季线里的搜索卡片：")
        val sameBase = result.items.filter {
            it.id !in lineIds && SeasonKeys.normalizeTitle(it.title).startsWith(keywordKey)
        }
        if (sameBase.isEmpty()) println("  （无）")
        sameBase.forEach {
            println("  ${it.id} | '${it.title}' | season=${it.season} | eps=${it.episodeCount} | key='${SeasonKeys.seasonKey(it.title)}'")
        }
        println("[SAME-BASE] 季线成员：${line.seasons.map { "${it.id}/S${it.season}" }}")

        // 4) 季线自身：季号是否连续、是否有序、标题季号与字段季号是否打架。
        printLineChecks(line)
        println("[EPS-COUNT] 季线 episodeCount 与搜索卡不一致的季：")
        val inconsistent = line.seasons.mapNotNull { season ->
            val card = result.items.firstOrNull { it.id == season.id }
            if (card != null && card.episodeCount != season.episodeCount) {
                "S${season.season}/${season.id} line=${season.episodeCount} card=${card.episodeCount}"
            } else {
                null
            }
        }
        println("  ${if (inconsistent.isEmpty()) "（无）" else inconsistent}")
        if (detail != null) {
            val anchorInLine = line.seasons.firstOrNull { it.id == detail.series.id }
            println(
                "[EPS-COUNT] 详情 episodes=${detail.episodes.size} detail.episodeCount=${detail.series.episodeCount} " +
                    "anchorInLine.episodeCount=${anchorInLine?.episodeCount}",
            )
        }
        println(
            "[RELEASED] 搜索卡 released=false 的条目：${
                result.items.filter { !it.released }.map { "${it.id}/'${it.title}'" }
            }；episodeCount<=0 但 released=true 的条目：${
                result.items.filter { it.episodeCount <= 0 && it.released }.map { "${it.id}/'${it.title}'" }
            }",
        )

        // 5) 收藏归组：用真实 PrefsLibrary 跑一遍，不模拟。
        if (detail != null) probeFavorite(keyword, result.items, detail, line)
        // 6) 跨季续播：真实季线喂给生产逻辑。
        println("[CHAIN] 每一季的下一季（未上线跳过）：")
        line.seasons.forEach { season ->
            val next = SeasonChain.next(line, season.id)
            println(
                "  S${season.season} ${season.id} released=${season.released} '${season.title}' " +
                    "-> next=${next?.let { "S${it.season}/${it.title}" } ?: "null"}",
            )
        }
        println("[CHAIN] latest=${SeasonChain.latest(line)?.let { "S${it.season}/${it.title}" }}")
        val lastReleased = line.seasons.lastOrNull { it.released }
        line.seasons.filter { it.released }.forEach { season ->
            val action = EpisodeFlow.after(
                index = 9,
                total = 10,
                autoNext = true,
                line = line,
                currentSeriesId = season.id,
                currentSeason = season.season,
            )
            println("  FLOW last-ep S${season.season} -> $action")
        }
        println("[FLOW] series end at S${lastReleased?.season}: ${EpisodeFlow.after(9, 10, true, line, lastReleased?.id.orEmpty(), lastReleased?.season ?: 0)}")
    }

    /**
     * 收藏归组实跑：**分别**从「详情锚点」「季线第 1 季」「季线第 2 季」「基名卡」四条路收藏，
     * 看收藏键、盘上标题、以及季线里每一季被归到哪一条收藏。
     *
     * 这四条路正是用户实际能点的：第 1 季/第 2 季的标题通常不带「第N季」，
     * 键算出来可能不是同一个——这是"收藏的是系列"这条不变量最容易被打破的地方。
     */
    private suspend fun probeFavorite(
        keyword: String,
        cards: List<Series>,
        detail: Detail,
        line: SeasonLine,
    ) {
        val candidates = (listOf(detail.series) + line.seasons + cards.filter {
            SeasonKeys.normalizeTitle(it.title).startsWith(SeasonKeys.normalizeTitle(keyword))
        }).distinctBy { it.id }

        val targets = listOfNotNull(
            detail.series to "详情锚点",
            line.seasons.firstOrNull { it.season == 1 }?.let { it to "季线S1" },
            line.seasons.firstOrNull { it.season == 2 }?.let { it to "季线S2" },
            cards.firstOrNull { SeasonKeys.normalizeTitle(it.title) == SeasonKeys.normalizeTitle(keyword) }
                ?.let { it to "基名卡" },
        ).distinctBy { it.first.id }

        targets.forEach { (favorite, label) ->
            val lib = PrefsLibrary(MemoryStore())
            lib.toggle(favorite)
            val key = SeasonKeys.seasonKey(favorite.title)
            lib.rememberSeasonLine(key, line)
            val stored = lib.entry(key)?.series?.title
            println(
                "[FAVORITE][$label] toggle('${favorite.title}') -> key='$key' " +
                    "storedTitle='$stored'",
            )
            candidates.forEach { series ->
                val hit = FavoriteLookup.entry(series, lib)
                println(
                    "  S${series.season} ${series.id} '${series.title}' " +
                        "entryKey='${hit?.seriesKey}' favoriteKey='${FavoriteLookup.favoriteKey(series, lib)}' " +
                        "tags='${FavoriteLookup.seasonTags(series, lib)}'",
                )
            }
            val keys = candidates.map { FavoriteLookup.favoriteKey(it, lib) }.distinct()
            println("  distinctFavoriteKeys=${keys.size} $keys")
            lib.flush()
        }

        // 进度模拟：最近看的是第 2 季（折桂篇）第 3 集，看 SeasonProgress 能不能定位到那一季。
        val second = line.seasons.firstOrNull { it.season == 2 } ?: return
        val progress = Progress(
            seriesId = second.id,
            episodeVid = "probe-vid",
            episodeIndex = 2,
            positionMs = 60_000L,
            durationMs = 600_000L,
            completed = false,
            updatedAt = 1L,
        )
        val lib = PrefsLibrary(MemoryStore())
        lib.toggle(detail.series)
        val favKey = SeasonKeys.seasonKey(detail.series.title)
        lib.rememberSeasonLine(favKey, line)
        lib.save(progress)
        val entry = lib.entry(favKey) ?: return
        val latest = SeasonProgress.latest(entry, line) { lib.progressOf(it) }
        val watched = SeasonProgress.seriesOf(latest ?: progress, line)
        println(
            "[PROGRESS] latest=${latest?.seriesId}/ep${latest?.episodeIndex} " +
                "watchedSeason=${watched?.let { "S${it.season}/${it.title}" }} " +
                "seasonNo=${SeasonProgress.seasonOf(latest ?: progress, line, 0)}",
        )
        lib.flush()
    }

    private fun printLine(label: String, line: SeasonLine) {
        println("[$label] n=${line.seasons.size}")
        line.seasons.forEach { season ->
            println(
                "  ${season.id} | S${season.season} | released=${season.released} | eps=${season.episodeCount} | " +
                    "'${season.title}' | titleSeason=${SeasonKeys.seasonNumber(season.title)} | " +
                    "base='${SeasonKeys.seasonBase(season.title)}' | key='${SeasonKeys.seasonKey(season.title)}'",
            )
        }
    }

    private fun printLineChecks(line: SeasonLine) {
        val seasons = line.seasons.map { it.season }
        val sorted = seasons.sorted()
        println("[LINE] seasons=$seasons sorted=${seasons == sorted} distinct=${seasons.distinct().size}/${seasons.size}")
        val withoutSeason = line.seasons.filter { it.season <= 0 }
        println("[LINE] season<=0: ${withoutSeason.map { "${it.id}/'${it.title}'" }}")
        val mismatches = line.seasons.filter {
            val fromTitle = SeasonKeys.seasonNumber(it.title)
            fromTitle > 0 && fromTitle != it.season
        }
        println("[LINE] titleSeason!=fieldSeason: ${mismatches.map { "${it.id}/field=${it.season}/title=${SeasonKeys.seasonNumber(it.title)}" }}")
        val first = line.seasons.firstOrNull { it.season == 1 }
        println("[LINE] hasSeason1=${first?.let { "${it.id}/'${it.title}'" }}")
        val distinctKeys = line.seasons.map { SeasonKeys.seasonKey(it.title) }.distinct()
        println("[LINE] distinctSeriesKeys=${distinctKeys.size} $distinctKeys")
    }

    /** 搜索偶发 502：探针自带小步重试（每次之间留足间隔，不做注册/重试风暴）。 */
    private suspend fun searchWithRetry(content: ContentRepository, keyword: String): SearchResult? {
        repeat(SEARCH_ATTEMPTS) { attempt ->
            val result = request("SEARCH#${attempt + 1}") { content.search(keyword, ContentFilter.ALL, 1) }
            if (result != null) return result
            if (attempt < SEARCH_ATTEMPTS - 1) delay(SEARCH_RETRY_GAP_MS)
        }
        return null
    }

    /** 单次请求 + 固定间隔；失败只打印，不中断整条探针。 */
    private suspend fun <T> request(label: String, block: suspend () -> T): T? {
        delay(REQUEST_GAP_MS)
        val started = System.nanoTime()
        return try {
            val value = block()
            println("[$label] ok ${(System.nanoTime() - started) / 1_000_000}ms")
            value
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            println("[$label] FAIL ${error::class.java.simpleName}: ${error.message}")
            null
        }
    }

    /** 探针用的内存键值库：注册与本地库都不落盘。 */
    private class MemoryStore : KeyValueStore {
        private val stored = LinkedHashMap<String, Any?>()
        override fun all(): Map<String, Any?> = LinkedHashMap(stored)
        override fun commit(values: Map<String, Any?>) {
            values.forEach { (key, value) -> if (value == null) stored.remove(key) else stored[key] = value }
        }
    }

    private companion object {
        /**
         * 已知样本（未被探针证伪前先用文档里的）：
         * 魏家 = 分宗篇 S1 + 折桂篇 S2 + 正传/3D版同 IP；
         * 万妖 = 14 季（含未上线）；
         * 聚宝（长名）= 15 季（含未上线），同基座的《聚宝仙盆》是另一部同 IP 作品；
         * 十八岁 = 2 季（第二季未上线）；
         * 真敢 = 高中篇 S1 + 大学篇壹 S2（两季标题都不带季号）。
         */
        val DEFAULT_KEYWORDS = listOf(
            "魏家逆子成首辅",
            "万妖图录传",
            "聚宝仙盆之杂灵根才是真BOSS",
            "十八岁的二次回响",
            "真敢让我重生啊高中篇",
        )

        /** 内容端点（详情 / 季线）的请求间隔：不挤同一个 host。 */
        const val REQUEST_GAP_MS = 1_200L

        /** 换关键词之间的间隔。 */
        const val KEYWORD_GAP_MS = 2_000L

        /** 搜索失败（502/空体）时的重试次数与间隔。 */
        const val SEARCH_ATTEMPTS = 3
        const val SEARCH_RETRY_GAP_MS = 4_000L
    }
}
