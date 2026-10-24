package com.hongguotv.lite.data.content

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.Episode
import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.SeriesIds
import com.hongguotv.core.model.StreamInfo
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.SilentDiagnostics
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.Fields
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.MediaCrypto
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.core.protocol.SignerRegistry
import com.hongguotv.core.protocol.urlEncode
import com.hongguotv.lite.data.diagnostics.stageTimed
import com.hongguotv.lite.data.diagnostics.stageTimedSuspend
import com.hongguotv.lite.data.http.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * [ContentPort] 的上游实现。端点、签名、字段名全部经 core 的表与常量，
 * 这里只谈业务：搜索、联想、详情、季线、取流。
 *
 * 行为基准是旧仓库 ContentRepository（只读参照），差异只有两处且都是分类升级：
 * 运行期失败从 IOException 换成 [UpstreamError] 家族；取流结果把"高度"与"是否降级"
 * 拆成两个独立事实，不再拼中文展示串。
 */
class ContentRepository(
    private val http: HttpTransport,
    private val signers: SignerRegistry = SignerRegistry(),
    private val diagnostics: DiagnosticsPort = SilentDiagnostics,
) : ContentPort {

    /**
     * 签名统一入口。端点 id 是日志里唯一允许出现的"请求目标"——
     * query 与 body 含设备号/时间戳，不能进日志。
     */
    private fun sign(endpoint: String, values: Map<String, String>): SignedRequest =
        diagnostics.stageTimed(DiagnosticsPort.Stage.SIGN, endpoint) {
            signers.sign(endpoint, values)
        }

    // 取流设备号：进程级复用的随机十九位（[1e18, 9e18)），与内容端点的 71332 设备常量是两套身份。
    // 混用（例如图省事拿 VendorConstants 的固定值）会被 preload 风控判成另一个客户端，返回 200 空体。
    private val streamDeviceId = (1_000_000_000_000_000_000L + Random.nextLong(8_000_000_000_000_000_000L)).toString()
    private val streamInstallId = (1_000_000_000_000_000_000L + Random.nextLong(8_000_000_000_000_000_000L)).toString()

    // 官网请求统一节流（最少间隔 400ms），避免补搜多季时触发限流。Mutex 保证并发安全（旧实现靠 synchronized + sleep）。
    private val siteGate = Mutex()
    private var lastSiteAt = 0L

    // ---------- 搜索 ----------

    override suspend fun search(keyword: String, filter: ContentFilter): SearchResult {
        require(keyword.isNotBlank() && keyword.length <= 80) { "搜索词无效" }
        // 条目来自官网搜索（每词固定前 10 条，无法翻页）；季数排序由服务端下发（plan 接口「相关作品」cell）。
        // 仅当搜索词恰为某系列 base（输入了完整剧名）时才走季线；否则官方原序展示。
        val rows = Parsers.valid(siteCards(keyword))
        val anchor = rows.firstOrNull { SeasonKeys.seasonKey(it.series.title) == SeasonKeys.normalizeTitle(keyword) }
            ?: return emitSearchResult(displayOf(rows, filter), emptyList(), keyword)
        val keywordKey = SeasonKeys.normalizeTitle(keyword)

        // plan 失败（网络/风控/结构）不是搜索失败：降级为官网原序，与旧行为一致。
        // 但"第一条锚点没有相关作品"很常见（正传常常没进专辑），这时换下一条同系列候选再试，
        // 否则搜完整剧名只会拿到官网那 10 条乱序结果（维护者实测"魏家逆子成首辅"）。
        val found = seasonLineFor(anchor, rows, keywordKey)
            ?: return emitSearchResult(displayOf(rows, filter), emptyList(), keyword)
        val (line, lineAnchor) = found

        val lineIds = line.seasons.map { it.id }.toSet()
        val rest = rows.filter { it.series.id !in lineIds && SeasonKeys.normalizeTitle(it.series.title).contains(keywordKey) }
        val displayRest = Parsers.filterType(rest, filter).map { it.series }
        // 季线卡片（plan）不一定带分类：同一系列的各季共用分类，
        // 从可用的锚点传播过去，否则只有官网搜索那一条卡片有分类（维护者实测"时有时无"）。
        val anchorTags = lineAnchor.series.tags
        val displayLine = filterLine(line, filter).asReversed().map { season ->
            if (season.tags.isBlank() && anchorTags.isNotBlank()) season.copy(tags = anchorTags) else season
        }
        // 展示序：季线新 → 旧置顶，其余条目保持官方原序
        return emitSearchResult(displayLine + displayRest, line.seasons, keyword)
    }

    /**
     * 找到能给出季线（≥2 季）的锚点：先试首选，再试同系列的前几条候选。
     *
     * 上限 [MAX_SEASON_ANCHORS] 是为了让"搜错词"这类情况不至于把 10 条候选都打一遍——
     * 每条 plan 约 1 秒，宁可退回官网原序，也不能让搜索转半分钟。
     */
    private suspend fun seasonLineFor(anchor: Card, rows: List<Card>, keywordKey: String): Pair<PlanLine, Card>? {
        val candidates = buildList {
            add(anchor)
            rows.asSequence()
                .filter { it.series.id != anchor.series.id }
                .filter { SeasonKeys.normalizeTitle(it.series.title).contains(keywordKey) }
                .take(MAX_SEASON_ANCHORS - 1)
                .forEach { add(it) }
        }
        for (candidate in candidates) {
            val line = runCatchingIo { planCells(candidate.series.id, candidate.isComic, candidate.series) }.getOrNull()
            if (line != null && line.seasons.size >= 2) return line to candidate
        }
        return null
    }

    /** 搜索结果的统一出口：顺手把"实际展示了几条、季线几季"记进诊断，不记搜索词原文。 */
    private fun emitSearchResult(items: List<Series>, seasons: List<Series>, keyword: String): SearchResult {
        diagnostics.event(
            "search.results",
            mapOf("chars" to keyword.length, "items" to items.size, "seasons" to seasons.size),
        )
        return SearchResult(items, seasons)
    }

    private suspend fun siteCards(keyword: String): List<Card> {
        // keyword 必须经 urlEncode 再进 path 占位符——编码口径只有 core 里一个
        val signed = sign("siteSearch", mapOf("keyword" to urlEncode(keyword)))
        val html = fetchSite(signed)
        if (Parsers.clientRendered(html)) {
            // 官网 2026-10-23 起把搜索页换成纯前端渲染：200 但不带任何数据。
            // 解析层已经把这种页面当"没有结果"，这里补一条诊断——线上再坏时能一眼看出
            // 是"上游不下发数据"而不是"解析器又跟不上结构"
            diagnostics.event("search.csr", mapOf("chars" to html.length))
            return emptyList()
        }
        return diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "siteSearch") {
            Parsers.searchList(html).objects().map(Parsers::card)
        }
    }

    private suspend fun fetchSite(request: SignedRequest): String = siteGate.withLock {
        val wait = lastSiteAt + SITE_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastSiteAt = System.currentTimeMillis()
        http.text(request)
    }

    private fun displayOf(rows: List<Card>, filter: ContentFilter): List<Series> =
        Parsers.filterType(rows, filter).map { it.series }

    /** 季线整段共享锚点的介质：plan 卡片无 celebrities，介质只能从锚点传播。 */
    private fun filterLine(line: PlanLine, filter: ContentFilter): List<Series> = when (filter) {
        ContentFilter.ALL -> line.seasons
        ContentFilter.COMIC -> if (line.isComic) line.seasons else emptyList()
        ContentFilter.SHORT -> if (!line.isComic) line.seasons else emptyList()
    }

    private data class PlanLine(val seasons: List<Series>, val isComic: Boolean)

    // ---------- 联想 ----------

    override suspend fun suggest(keyword: String): List<Suggestion> {
        require(keyword.isNotBlank() && keyword.length <= 80) { "联想词无效" }
        // 联想是输入辅助，网络与解析失败静默降级为空列表，不打断输入（旧行为）。
        // 它允许不完整（实测漏个别季），不作为季数上线的事实来源——那是 plan 接口的职责。
        // 注意：sign 不在降级范围内——端点表坏了是装配错误，启动时就要炸（端口契约）。
        val signed = sign("suggest", mapOf("keyword" to keyword))
        val raw = runCatchingIo { http.text(signed) }.getOrElse { return emptyList() }
        val data = runCatching { JSONObject(raw).optJSONObject(Fields.Envelope.DATA) }.getOrElse {
            // JSON 本身坏了是解析失败，不是"没有联想词"；仍然降级为空，但诊断要留下失败
            diagnostics.stage(DiagnosticsPort.Stage.PARSE, false, "suggest ${it.javaClass.simpleName}")
            return emptyList()
        }
        val suggestions = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "suggest") {
            val rows = data?.optJSONArray(Fields.Suggest.QUERY_RESULT)
            if (rows == null) {
                emptyList<Suggestion>()
            } else {
                rows.objects().mapNotNull { o ->
                    val video = o.optJSONObject(Fields.Suggest.VIDEO_DATA) ?: return@mapNotNull null
                    val id = video.str(Fields.Suggest.SERIES_ID)
                    if (!SeriesIds.ID.matches(id)) return@mapNotNull null
                    val series = Series(
                        id = id,
                        title = o.str(Fields.Suggest.NAME),
                        cover = Parsers.cleanUrl(video.str(Fields.Suggest.COVER)),
                        badge = o.str(Fields.Suggest.SUG_ABSTRACT),
                    )
                    Suggestion(series.title, series)
                }.distinctBy { it.target?.id }
            }
        }
        diagnostics.event("suggest.results", mapOf("chars" to keyword.length, "count" to suggestions.size))
        return suggestions
    }

    // ---------- 详情 ----------

    override suspend fun detail(seriesId: String): Detail {
        require(SeriesIds.ID.matches(seriesId)) { "剧集 id 无效：$seriesId" }
        // 全集顺序以服务端下发为准（本地不重排）；官网详情页 2026-10 起全网 404，不再回退。
        val signed = sign("detail", mapOf("seriesId" to seriesId))
        val raw = http.text(signed)
        val detail = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "detail") {
            val envelope = runCatching { JSONObject(raw) }.getOrElse { throw UpstreamError.Shape("body") }
            val code = envelope.optInt(Fields.Envelope.CODE, envelope.optInt(Fields.Envelope.CODE_CAP, 0))
            if (code != 0) throw UpstreamError.RiskControl(code)
            val vd = envelope.optJSONObject(Fields.Envelope.DATA)?.optJSONObject(Fields.Detail.VIDEO_DATA)
                ?: throw UpstreamError.Shape("data.video_data")
            val vids = (vd.optJSONArray(Fields.Detail.VIDEO_LIST)?.objects() ?: emptyList())
                .map { it.optString(Fields.Detail.VID) }
                .filter { SeriesIds.ID.matches(it) }
                .distinct()
            if (vids.isEmpty()) throw UpstreamError.Shape("data.video_data.video_list")
            val title = vd.str(Fields.Detail.SERIES_TITLE).ifEmpty { vd.str(Fields.Detail.SERIES_NAME) }
            val series = Series(
                id = vd.str(Fields.Detail.SERIES_ID).ifEmpty { seriesId },
                title = title,
                cover = Parsers.cleanUrl(vd.str(Fields.Detail.SERIES_COVER)),
                intro = vd.str(Fields.Detail.SERIES_INTRO),
                badge = "全 ${vd.optInt(Fields.Detail.EPISODE_CNT, vids.size)} 集",
                tags = Parsers.detailTags(vd),
                season = SeasonKeys.seasonNumber(title),
            )
            Detail(series, vids.mapIndexed { index, vid -> Episode(vid, index) })
        }
        diagnostics.event("detail.loaded", mapOf("episodes" to detail.episodes.size, "season" to detail.series.season))
        return detail
    }

    // ---------- 季线 ----------

    override suspend fun seasons(seriesId: String, anchor: Series?): SeasonLine {
        // 端口只传 Series（无介质信息）：季线的介质不被任何消费方使用，传播链到 port 为止。
        return SeasonLine(planCells(seriesId, isComic = false, anchor = anchor).seasons)
    }

    /**
     * plan 接口返回「相关作品」cell（show_type=547）：本系列全部季（第 2 季起）自带季标签与集数；
     * 锚点本尊不在卡片里（如分宗篇=第 1 季），按其标题季号并入，无季号的锚点视为系列起点。
     * 返回季按季号升序（旧 → 新）。
     */
    private suspend fun planCells(seriesId: String, isComic: Boolean, anchor: Series?): PlanLine {
        require(SeriesIds.ID.matches(seriesId)) { "剧集 id 无效：$seriesId" }
        val signed = sign("seasonLine", mapOf("seriesId" to seriesId))
        val raw = http.text(signed)
        val line = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "seasonLine") {
            val data = runCatching { JSONObject(raw).optJSONArray(Fields.Envelope.DATA) ?: JSONArray() }
                .getOrElse { throw UpstreamError.Shape("body") }
            val entries = Parsers.planCell(data).toMutableList()
            if (anchor != null && entries.none { it.second.id == anchor.id }) {
                val n = SeasonKeys.seasonNumber(anchor.title)
                entries += n to (if (n > 0) anchor.copy(season = n) else anchor)
            }
            PlanLine(entries.sortedBy { it.first }.map { it.second }, isComic)
        }
        diagnostics.event("seasons.loaded", mapOf("seasons" to line.seasons.size))
        return line
    }

    // ---------- 取流 ----------

    override suspend fun stream(vid: String, maxHeight: Int): StreamInfo {
        require(SeriesIds.ID.matches(vid)) { "剧集 id 无效：$vid" }
        // 旧入口 video_model / multi_video_model 2026-10 起返回 200 空体，preload 是唯一还吐数据的入口；
        // 其风控只放行 onedrama 6905554 同款简化请求（PreloadSigner 负责），设备号是本类自己的随机身份。
        val signed = sign(
            "preload",
            mapOf("vid" to vid, "deviceId" to streamDeviceId, "installId" to streamInstallId),
        )
        val raw = http.text(signed)
        val entry = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "stream.preload") {
            val envelope = runCatching { JSONObject(raw) }.getOrElse { throw UpstreamError.Shape("body") }
            val code = envelope.optInt(Fields.Envelope.CODE_CAP, envelope.optInt(Fields.Envelope.CODE, 0))
            if (code != 0) throw UpstreamError.RiskControl(code)
            val data = envelope.optJSONObject(Fields.Envelope.DATA) ?: throw UpstreamError.Shape("data")
            // preload 是批量接口：data 以 vid 为键，只认请求的那一集，不拿其他条目顶上。
            data.optJSONObject(vid) ?: throw UpstreamError.Shape("data.$vid")
        }
        // 第二跳（fallback_api）是取流的解析段：慢了、换域名了、结构变了都在这里露出来。
        // `fallbackUrl` 的结构错也算 RESOLVE：它回答的是"地址从哪来"。
        val info = diagnostics.stageTimedSuspend(DiagnosticsPort.Stage.RESOLVE, "stream.fallback") {
            val fallback = Parsers.fallbackUrl(entry)
            runCatchingIo {
                JSONObject(http.text(SignedRequest(HttpMethod.GET, fallback, emptyMap(), null)))
                    .optJSONObject(Fields.Stream.VIDEO_INFO)?.optJSONObject(Fields.Envelope.DATA)
            }.getOrNull() ?: throw UpstreamError.Shape("video_info.data")
        }
        val rows = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "stream.videoList") {
            val videoList = info.optJSONObject(Fields.Stream.VIDEO_LIST) ?: throw UpstreamError.Shape("video_list")
            Parsers.qualityRows(videoList).also { if (it.isEmpty()) throw UpstreamError.Shape("video_list") }
        }
        // 首选不超过上限的最高清晰度；没有就退到最低档（"兼容资源"，fellBack 单独成事实）
        val selected = rows.firstOrNull { it.first <= maxHeight } ?: rows.last()
        val item = selected.third
        var url = Parsers.cleanUrl(item.str(Fields.Stream.MAIN_URL))
        val seed = info.str(Fields.Stream.KEY_SEED)
        if (seed.isNotEmpty()) {
            url = diagnostics.stageTimed(DiagnosticsPort.Stage.DECRYPT, "stream.url") {
                runCatching { MediaCrypto.decryptUrl(url, seed) }.getOrElse { throw UpstreamError.Decrypt("url") }
            }
        }
        diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "stream.mainUrl") {
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw UpstreamError.Shape("main_url")
        }
        val key = item.str(Fields.Stream.SPADE_A).takeIf { it.isNotEmpty() }?.let { seedValue ->
            diagnostics.stageTimed(DiagnosticsPort.Stage.DECRYPT, "stream.key") {
                runCatching { MediaCrypto.deriveKey(seedValue) }.getOrElse { throw UpstreamError.Decrypt("key") }
            }
        }
        diagnostics.event(
            "stream.selected",
            mapOf(
                "height" to selected.first,
                "fellBack" to (selected.first > maxHeight),
                "encrypted" to (key != null),
                "qualities" to rows.size,
            ),
        )
        return StreamInfo(
            url = url,
            key = key,
            height = selected.first.takeIf { it > 0 },
            fellBack = selected.first > maxHeight,
        )
    }

    private companion object {
        /** 官网请求最少间隔：补搜多季时限流的实测安全值（旧行为）。 */
        const val SITE_INTERVAL_MS = 400L

        /** 季线最多试几个锚点：首选 + 2 条同系列候选。每条 plan 约 1 秒。 */
        const val MAX_SEASON_ANCHORS = 3
    }
}

/**
 * 标准库 runCatching 会把 CancellationException 一并捕获——取消不是失败，
 * 吞掉它会破坏结构化并发（调用方以为请求还在跑）。包 suspend 调用一律走这里。
 */
private inline fun <T> runCatchingIo(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
