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
import com.hongguotv.core.port.DecoderCapabilities
import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.SilentDiagnostics
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.port.VideoCodec
import com.hongguotv.core.protocol.Fields
import com.hongguotv.core.protocol.FullSuiteSigner
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.MediaCrypto
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.core.protocol.PlainSigner
import com.hongguotv.core.protocol.SignerRegistry
import com.hongguotv.lite.data.diagnostics.stageTimed
import com.hongguotv.lite.data.diagnostics.stageTimedSuspend
import com.hongguotv.lite.data.http.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

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
    /**
     * 设备身份来源。**内容端点与取流共用这一份**：内置的固定值已被平台拉黑，
     * 带它签名的取流请求一律 200 空体（看起来像"所有影片都放不了"）。
     *
     * 没有默认值：默认成内置那对等于把"所有影片都放不了"设成出厂状态。
     * 生产由 `AppGraph` 传 [DeviceSession]（注册下发的一对），测试传 [FixedDeviceIdentity]。
     */
    private val devices: DeviceIdentitySource,
    /**
     * 设备解码能力。生产由 `AppGraph` 注入 `AndroidDecoderCapabilities`；
     * 单测与真网探针传 `DecoderCapabilities { true }`（探针只验证协议，不验证本机解码）。
     */
    private val decoders: DecoderCapabilities,
    private val diagnostics: DiagnosticsPort = SilentDiagnostics,
) : ContentPort {

    /**
     * 当前身份对应的签名器。身份可能在运行期被风控换新（[DeviceIdentitySource.renew]），
     * 所以不能像以前那样在构造时就固化一份。
     *
     * 初始只有无签名通道：官网搜索不依赖设备身份，注册完成前也能用；
     * 内容端点走 [withDevice]，`apply` 之后才有 FULL 签名器——不会拿内置黑名单身份签名。
     */
    @Volatile
    private var signers: SignerRegistry = SignerRegistry(listOf(PlainSigner()))

    @Volatile
    private var applied: DeviceSnapshot? = null

    init {
        // 启动时已有注册结果就先用上：首个请求不必等一次 ensure 的挂起
        devices.snapshot()?.let(::apply)
    }

    /** 换一份身份后重建签名器。内容端点与取流读的是同一个 [signers]。 */
    private fun apply(snapshot: DeviceSnapshot) {
        if (applied == snapshot) return
        applied = snapshot
        signers = SignerRegistry(
            listOf(
                FullSuiteSigner(snapshot.identity, snapshot.profile, snapshot.cookies),
                PlainSigner(snapshot.profile),
            ),
        )
    }

    /**
     * 带设备注册的请求：先确保有身份，再执行；遇到风控特征（200 空体 / 风控码）
     * 换一台设备重试一次。
     *
     * **只重试一次**，而且换新本身有冷却（见 `DeviceSession.renew`）：风控是"这台设备被拦"，
     * 不是"这个请求参数错了"，无限重试只会把设备数刷起来。
     */
    private suspend fun <T> withDevice(action: suspend () -> T): T {
        apply(devices.ensure())
        return try {
            action()
        } catch (error: UpstreamError) {
            if (!error.isRiskControl()) throw error
            val renewed = devices.renew(error.javaClass.simpleName) ?: throw error
            diagnostics.event("device.retry", mapOf("reason" to error.javaClass.simpleName))
            apply(renewed)
            action()
        }
    }

    private fun UpstreamError.isRiskControl(): Boolean =
        this is UpstreamError.EmptyBody || this is UpstreamError.RiskControl

    /**
     * 签名统一入口。端点 id 是日志里唯一允许出现的"请求目标"——
     * query 与 body 含设备号/时间戳，不能进日志。
     */
    private fun sign(endpoint: String, values: Map<String, String>): SignedRequest =
        diagnostics.stageTimed(DiagnosticsPort.Stage.SIGN, endpoint) {
            signers.sign(endpoint, values)
        }

    // 官网搜索请求统一节流（最少间隔 400ms）。Mutex 保证并发安全（旧实现靠 synchronized + sleep）。
    private val siteGate = Mutex()
    private var lastSiteAt = 0L

    // ---------- 搜索 ----------

    /**
     * 官网**搜索**请求统一节流（最少间隔 400ms）：翻页会连着打同一个 host。
     * 联想不走这里——输入框自带 400ms 防抖，再排队只会让候选更慢。
     * Mutex 保证并发安全。
     */
    private suspend fun fetchSite(request: SignedRequest): String = siteGate.withLock {
        val wait = lastSiteAt + SITE_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastSiteAt = System.currentTimeMillis()
        http.text(request)
    }

    override suspend fun search(keyword: String, filter: ContentFilter, page: Int): SearchResult {
        require(keyword.isNotBlank() && keyword.length <= 80) { "搜索词无效" }
        require(page >= 1) { "页码从 1 起：$page" }
        // 官网服务端分页：一页固定 10 条（页面自己也是一页 10 条）。界面按两行 10 张一页翻，
        // 翻页就是换一个 page 参数——不再在客户端拼"整条季线"。
        val signed = sign("siteSearch", mapOf("keyword" to keyword, "page" to page.toString()))
        val body = fetchSite(signed)
        val loaded = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "siteSearch") {
            Parsers.siteSearchPage(body)
        }
        val cards = Parsers.valid(loaded.cards)
        val items = Parsers.filterType(cards, filter).map { it.series }
        diagnostics.event(
            "search.results",
            mapOf(
                "chars" to keyword.length,
                "page" to loaded.page,
                "items" to items.size,
                "pages" to loaded.totalPages,
            ),
        )
        return SearchResult(
            items = items,
            page = loaded.page,
            totalPages = loaded.totalPages,
            totalCount = loaded.totalCount,
            hasMore = loaded.hasMore,
        )
    }

    // ---------- 联想 ----------

    override suspend fun suggest(keyword: String): List<Suggestion> {
        require(keyword.isNotBlank() && keyword.length <= 80) { "联想词无效" }
        // 联想是输入辅助：网络与解析失败静默降级为空列表，不打断输入。
        // 端点换成官网的联想词接口（免签名、免设备注册），与搜索同一个来源。
        val signed = sign("suggest", mapOf("keyword" to keyword))
        // 联想不节流：输入框有 400ms 防抖，逐字都排队反而会拖慢候选
        val raw = runCatchingIo { http.text(signed) }.getOrElse { return emptyList() }
        val suggestions = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "suggest") {
            runCatching { Parsers.suggestList(raw) }.getOrElse {
                diagnostics.stage(DiagnosticsPort.Stage.PARSE, false, "suggest ${it.javaClass.simpleName}")
                emptyList()
            }
        }
        diagnostics.event("suggest.results", mapOf("chars" to keyword.length, "count" to suggestions.size))
        return suggestions
    }

    // ---------- 详情 ----------

    override suspend fun detail(seriesId: String): Detail = withDevice { loadDetail(seriesId) }

    private suspend fun loadDetail(seriesId: String): Detail {
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
            // 详情集数同时写进 badge 与 episodeCount：收藏详情的「更新」靠后者；
            // 只写 badge 会让并入季线的锚点季集数为 0，回退成「第九季第90集」这种错文案。
            val total = vd.optInt(Fields.Detail.EPISODE_CNT, 0).takeIf { it > 0 } ?: vids.size
            val series = Series(
                id = vd.str(Fields.Detail.SERIES_ID).ifEmpty { seriesId },
                title = title,
                cover = Parsers.cleanUrl(vd.str(Fields.Detail.SERIES_COVER)),
                intro = vd.str(Fields.Detail.SERIES_INTRO),
                badge = "全 $total 集",
                tags = Parsers.detailTags(vd),
                // 详情的 season_index 是权威季号（「分宗篇」这种标题不带季号的季也能定序）；
                // 没有时退回标题解析（老数据/单季剧）
                season = vd.optInt(Fields.Detail.SEASON_INDEX, 0).takeIf { it > 0 }
                    ?: SeasonKeys.seasonNumber(title),
                // 空 = 同 IP 作品：它不属于任何季线，季线并入时据此把它挡在外面
                albumId = vd.str(Fields.Detail.RELATED_ALBUM_ID),
                episodeCount = total,
            )
            Detail(series, vids.mapIndexed { index, vid -> Episode(vid, index) })
        }
        diagnostics.event("detail.loaded", mapOf("episodes" to detail.episodes.size, "season" to detail.series.season))
        return detail
    }

    // ---------- 季线 ----------

    override suspend fun seasons(seriesId: String, anchor: Series?): SeasonLine =
        // 端口只传 Series（无介质信息）：季线的介质不被任何消费方使用，传播链到 port 为止。
        withDevice { planCells(seriesId, anchor) }

    /**
     * plan 接口返回「相关作品」cell（show_type=547）：本系列全部季（第 2 季起）自带季标签与集数；
     * 锚点本尊不在卡片里（如分宗篇=第 1 季），按其**详情给的**季号并入。
     *
     * 并入的两个条件（缺一不可）：锚点有季号，且它属于某张专辑（`albumId` 非空）。
     * 同 IP 正传的详情没有 `related_album_id`、季号也是 0——把它并进来会被当成第 1 季，
     * 顶掉真正的分宗篇（2026-10-24 维护者点名的坑）。
     *
     * 搜索卡/旧收藏行的锚点没有 `albumId`，又不一定在相关作品里：先问一次详情补齐证据，
     * 失败静默（少一季比整页错误态好）。这是搜索聚合不缺锚点季的唯一保证。
     */
    private suspend fun planCells(seriesId: String, anchor: Series?): SeasonLine {
        require(SeriesIds.ID.matches(seriesId)) { "剧集 id 无效：$seriesId" }
        val signed = sign("seasonLine", mapOf("seriesId" to seriesId))
        val raw = http.text(signed)
        val entries = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "seasonLine") {
            val data = runCatching { JSONObject(raw).optJSONArray(Fields.Envelope.DATA) ?: JSONArray() }
                .getOrElse { throw UpstreamError.Shape("body") }
            Parsers.planCell(data).toMutableList()
        }
        // 搜索卡/旧收藏行没有 albumId：plan 的相关作品又不含锚点本尊，直接按
        // "有季号 + 专辑"并入会把锚点那一季丢掉（实测魏家缺 S9、万妖缺 S1、聚宝缺 S14）。
        val resolved = resolveAnchor(entries, anchor)
        if (resolved != null && entries.none { it.second.id == resolved.id } && resolved.isSeriesSeason) {
            entries += resolved.season to resolved
        }
        val line = SeasonLine(entries.sortedBy { it.first }.map { it.second })
        diagnostics.event("seasons.loaded", mapOf("seasons" to line.seasons.size))
        // 锚点不是本系列的一季（同 IP 正传）时，相关作品里只有第 2 季起：
        // 用一次搜索 + 一次详情把第 1 季补回来，否则这一页永远少一季（维护者实测）。
        val missingFirst = line.seasons.isNotEmpty() && line.seasons.none { it.season == 1 } &&
            (resolved == null || !resolved.isSeriesSeason)
        if (!missingFirst) return line
        val base = resolved?.let { SeasonKeys.seasonKey(it.title) }.orEmpty()
        if (base.isBlank()) return line
        val first = backfillFirstSeason(base, excludeId = seriesId) ?: return line
        return SeasonLine((line.seasons + first).sortedBy { it.season })
    }

    /**
     * 搜索卡锚点的详情证据：id → 详情补出的 `season_index` / `albumId`。
     *
     * 搜索聚合每搜一次，无 `albumId` 的锚点都要补一次详情；同一部剧反复搜/翻页时这条证据不变。
     * 缓存把"每部剧一次详情"压在会话内，避免搜索路径重复请求（B3）。
     * 不用 `LruCache`：这里只是十几条小对象，按最近使用淘汰即可。
     */
    private val anchorEvidence = LinkedHashMap<String, Series>()
    private val anchorEvidenceLock = Any()

    private fun anchorEvidenceOf(id: String): Series? = synchronized(anchorEvidenceLock) {
        anchorEvidence.remove(id)?.also { anchorEvidence[id] = it }
    }

    private fun rememberAnchorEvidence(id: String, series: Series) {
        synchronized(anchorEvidenceLock) {
            anchorEvidence.remove(id)
            anchorEvidence[id] = series
            while (anchorEvidence.size > ANCHOR_EVIDENCE_LIMIT) anchorEvidence.remove(anchorEvidence.keys.first())
        }
    }

    /**
     * 锚点没有专辑证据、又不在 plan 的相关作品里时，问一次详情把 `season_index` / `albumId` 补齐。
     *
     * 失败静默：已经拿到的相关作品仍然可用，少一季比整页变错误态好。
     * 详情返回的 id 与请求不一致时不采信——宁可少一季，也不把别的剧并进来。
     */
    private suspend fun resolveAnchor(
        entries: List<Pair<Int, Series>>,
        anchor: Series?,
    ): Series? {
        if (anchor == null || anchor.albumId.isNotBlank()) return anchor
        if (entries.any { it.second.id == anchor.id }) return anchor
        if (!SeriesIds.ID.matches(anchor.id)) return anchor
        anchorEvidenceOf(anchor.id)?.let { return it }
        val detail = try {
            loadDetail(anchor.id)
        } catch (error: UpstreamError) {
            diagnostics.event("seasons.anchor_failed", mapOf("reason" to error.javaClass.simpleName))
            return anchor
        }
        if (detail.series.id != anchor.id) {
            diagnostics.event("seasons.anchor_mismatch", mapOf("requested" to anchor.id, "got" to detail.series.id))
            return anchor
        }
        rememberAnchorEvidence(anchor.id, detail.series)
        return detail.series
    }

    /** 锚点是不是某条季线的一季：有季号，且属于某张专辑（同 IP 作品两个条件都不满足）。 */
    private val Series.isSeriesSeason: Boolean get() = season > 0 && albumId.isNotBlank()

    /**
     * 找系列的第 1 季：按基名搜一次，取"标题只比基名长几个字、自己不带季号"的同名篇
     * （分宗篇这类），再用详情确认它确实 `season_index = 1` 且属于某张专辑。
     *
     * 这条兜底只在锚点是同 IP 作品、且季线缺第 1 季时才会走；失败静默，不影响已经拿到的季线。
     * 两个候选封顶：一次进页多三次请求已经是极限，不能为了补一季把页面拖成转圈。
     */
    private suspend fun backfillFirstSeason(base: String, excludeId: String): Series? {
        val page = runCatching { search(base, ContentFilter.ALL, 1) }.getOrNull() ?: return null
        val candidates = page.items.asSequence()
            .filter { item ->
                item.id != excludeId && item.season == 0 &&
                    SeasonKeys.normalizeTitle(item.title).let { it.startsWith(base) && it.length - base.length in 1..4 }
            }
            .sortedByDescending { it.episodeCount }
            .take(MAX_FIRST_SEASON_CANDIDATES)
        for (candidate in candidates) {
            val detail = runCatching { detail(candidate.id) }.getOrNull() ?: continue
            if (detail.series.season == 1 && detail.series.albumId.isNotBlank()) return detail.series
        }
        return null
    }

    // ---------- 取流 ----------

    override suspend fun stream(vid: String, maxHeight: Int): StreamInfo = withDevice { streamOnce(vid, maxHeight) }

    private suspend fun streamOnce(vid: String, maxHeight: Int): StreamInfo {
        require(SeriesIds.ID.matches(vid)) { "剧集 id 无效：$vid" }
        // 播放地址第一候选：/novel/player/video_model/v1/，body 只认 video_id 单值。
        // 空响应体（设备被拉黑 / 网关拦下）由 HTTP 层抛 EmptyBody——绝不能当成功往下解析。
        val signed = sign("videoModel", mapOf("vid" to vid))
        val raw = http.text(signed)
        val entry = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "stream.model") {
            val envelope = runCatching { JSONObject(raw) }.getOrElse { throw UpstreamError.Shape("body") }
            val code = envelope.optInt(Fields.Envelope.CODE_CAP, envelope.optInt(Fields.Envelope.CODE, 0))
            if (code != 0) throw UpstreamError.RiskControl(code)
            envelope.optJSONObject(Fields.Envelope.DATA) ?: throw UpstreamError.Shape("data")
        }
        // 第二跳（fallback_api）是取流的解析段：慢了、换域名了、结构变了都在这里露出来。
        // `fallbackUrl` 的结构错也算 RESOLVE：它回答的是"地址从哪来"。
        val info = diagnostics.stageTimedSuspend(DiagnosticsPort.Stage.RESOLVE, "stream.fallback") {
            val fallback = Parsers.fallbackUrl(entry)
            // 第二跳是无签名 GET，但 UA 仍然用注册档案那份（不能从平板切回手机）
            val headers = applied?.profile?.let { mapOf("User-Agent" to it.userAgent) }.orEmpty()
            runCatchingIo {
                JSONObject(http.text(SignedRequest(HttpMethod.GET, fallback, headers, null)))
                    .optJSONObject(Fields.Stream.VIDEO_INFO)?.optJSONObject(Fields.Envelope.DATA)
            }.getOrNull() ?: throw UpstreamError.Shape("video_info.data")
        }
        val rows = diagnostics.stageTimed(DiagnosticsPort.Stage.PARSE, "stream.videoList") {
            val videoList = info.optJSONObject(Fields.Stream.VIDEO_LIST) ?: throw UpstreamError.Shape("video_list")
            Parsers.qualityRows(videoList).also { if (it.isEmpty()) throw UpstreamError.Shape("video_list") }
        }
        val unknownCodecs = rows.map { it.codecType }.distinct().filter { Parsers.videoCodec(it) == null }
        if (unknownCodecs.isNotEmpty()) {
            diagnostics.event(
                "stream.codec_unknown",
                mapOf("types" to unknownCodecs.joinToString(",") { it.ifBlank { "(missing)" } }),
            )
        }
        val pick = pickQuality(rows, maxHeight)
        val selected = pick.row
        val item = selected.json
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
        // 一个支持档都没有：退回整张表的最低档，并把原因留给诊断（播放器能不能解由它最终判定）
        if (pick.codecUnsupported) {
            diagnostics.event(
                "stream.codec_unsupported",
                mapOf(
                    "available" to rows.map { it.codecType }.distinct().joinToString(","),
                    "height" to selected.height,
                ),
            )
        }
        diagnostics.event(
            "stream.selected",
            mapOf(
                "height" to selected.height,
                "fellBack" to pick.overLimit,
                "encrypted" to (key != null),
                "qualities" to rows.size,
            ),
        )
        return StreamInfo(
            url = url,
            key = key,
            height = selected.height.takeIf { it > 0 },
            fellBack = pick.overLimit,
        )
    }

    private data class QualityPick(
        val row: QualityRow,
        /** 所有可选档位都超出 [maxHeight]：这是"兼容资源"。 */
        val overLimit: Boolean,
        /** 没有任何已知档位的编码被设备支持，只能退回最低档碰运气。 */
        val codecUnsupported: Boolean,
    )

    /**
     * 选档：先过滤设备解不了的已知编码，再取不超过上限的最高档（同高度 AVC 优先）。
     * 已知编码一档都不可用时才试**未知编码**——上游新增编码默认放行，但不能盖掉已知能解的档；
     * 连未知档也没有才退回整张表的最低档，并让调用方记"编码不受支持"。
     */
    private fun pickQuality(rows: List<QualityRow>, maxHeight: Int): QualityPick {
        val supported = rows.filter { it.codec?.let(decoders::supports) == true }
        val unknown = rows.filter { it.codec == null }
        pickWithin(supported, maxHeight)?.let { return QualityPick(it, overLimit = false, codecUnsupported = false) }
        pickWithin(unknown, maxHeight)?.let { return QualityPick(it, overLimit = false, codecUnsupported = false) }
        supported.lastOrNull()?.let { return QualityPick(pickLowest(supported), overLimit = true, codecUnsupported = false) }
        unknown.lastOrNull()?.let { return QualityPick(pickLowest(unknown), overLimit = true, codecUnsupported = false) }
        val lowest = pickLowest(rows)
        return QualityPick(lowest, overLimit = lowest.height > maxHeight, codecUnsupported = true)
    }

    /** 候选里取不超过上限的最高档；同高度 AVC 优先（AVC 的兼容性最好）。 */
    private fun pickWithin(rows: List<QualityRow>, maxHeight: Int): QualityRow? {
        val within = rows.filter { it.height <= maxHeight }
        if (within.isEmpty()) return null
        return preferAvcAt(within.first().height, within)
    }

    /** 候选里取最低档；同高度同样优先 AVC，不能按 JSON key 顺序碰运气。 */
    private fun pickLowest(rows: List<QualityRow>): QualityRow = preferAvcAt(rows.last().height, rows)

    private fun preferAvcAt(height: Int, rows: List<QualityRow>): QualityRow {
        val sameHeight = rows.filter { it.height == height }
        return sameHeight.firstOrNull { it.codec == VideoCodec.AVC } ?: sameHeight.first()
    }

    private val QualityRow.codec: VideoCodec? get() = Parsers.videoCodec(codecType)

    private companion object {
        /** 官网请求最少间隔：补搜多季时限流的实测安全值（旧行为）。 */
        const val SITE_INTERVAL_MS = 400L

        /** 补第 1 季时最多试几个候选：一次进页多三次请求已经是极限，不能为了补一季把页面拖成转圈。 */
        const val MAX_FIRST_SEASON_CANDIDATES = 2

        /** 搜索卡锚点证据缓存上限：会话内同一部剧最多补一次详情。 */
        const val ANCHOR_EVIDENCE_LIMIT = 16
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
