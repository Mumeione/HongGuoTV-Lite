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
import com.hongguotv.core.protocol.DeviceIdentity
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
     * 匿名设备身份。**内容端点与取流共用这一对**：内置的固定值已被平台拉黑，
     * 带它签名的取流请求一律 200 空体（看起来像"所有影片都放不了"）。
     *
     * 没有默认值：默认成内置那对等于把"所有影片都放不了"设成出厂状态。
     * 生产由 `AppGraph` 传 `AnonymousIdentity.of(store)`，测试传固定值。
     */
    identity: DeviceIdentity,
    private val diagnostics: DiagnosticsPort = SilentDiagnostics,
) : ContentPort {

    private val signers = SignerRegistry(listOf(FullSuiteSigner(identity), PlainSigner()))

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
        // 排序开关只排"这一页里与关键词同系列的季"。跨页的季由服务端分页负责，
        // 客户端拿不到也不该假装拿得到。
        val keywordKey = SeasonKeys.seasonKey(keyword)
        val seasons = cards
            .filter { it.series.season > 0 && SeasonKeys.seasonKey(it.series.title) == keywordKey }
            .sortedBy { it.series.season }
            .map { it.series }
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
            seasonLine = seasons,
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

    override suspend fun seasons(seriesId: String, anchor: Series?): SeasonLine =
        // 端口只传 Series（无介质信息）：季线的介质不被任何消费方使用，传播链到 port 为止。
        planCells(seriesId, anchor)

    /**
     * plan 接口返回「相关作品」cell（show_type=547）：本系列全部季（第 2 季起）自带季标签与集数；
     * 锚点本尊不在卡片里（如分宗篇=第 1 季），按其标题季号并入，无季号的锚点视为系列起点。
     * 返回季按季号升序（旧 → 新）。
     */
    private suspend fun planCells(seriesId: String, anchor: Series?): SeasonLine {
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
            SeasonLine(entries.sortedBy { it.first }.map { it.second })
        }
        diagnostics.event("seasons.loaded", mapOf("seasons" to line.seasons.size))
        return line
    }

    // ---------- 取流 ----------

    override suspend fun stream(vid: String, maxHeight: Int): StreamInfo {
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
