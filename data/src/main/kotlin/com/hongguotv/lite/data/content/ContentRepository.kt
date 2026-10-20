package com.hongguotv.lite.data.content

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.Episode
import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.StreamInfo
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.Fields
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.MediaCrypto
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.core.protocol.SignerRegistry
import com.hongguotv.core.protocol.urlEncode
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
) : ContentPort {

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
        val anchor = rows.firstOrNull { TitleKeys.seasonKey(it.series.title) == TitleKeys.normalizeTitle(keyword) }
            ?: return SearchResult(displayOf(rows, filter), emptyList())

        // plan 失败（网络/风控/结构）不是搜索失败：降级为官网原序，与旧行为一致
        val line = runCatchingIo { planCells(anchor.series.id, anchor.isComic, anchor.series) }.getOrNull()
        if (line == null || line.seasons.size < 2) return SearchResult(displayOf(rows, filter), emptyList())

        val lineIds = line.seasons.map { it.id }.toSet()
        val keywordKey = TitleKeys.normalizeTitle(keyword)
        val rest = rows.filter { it.series.id !in lineIds && TitleKeys.normalizeTitle(it.series.title).contains(keywordKey) }
        val displayRest = Parsers.filterType(rest, filter).map { it.series }
        // 展示序：季线新 → 旧置顶，其余条目保持官方原序
        return SearchResult(filterLine(line, filter).asReversed() + displayRest, line.seasons)
    }

    private suspend fun siteCards(keyword: String): List<Card> {
        // keyword 必须经 urlEncode 再进 path 占位符——编码口径只有 core 里一个
        val signed = signers.sign("siteSearch", mapOf("keyword" to urlEncode(keyword)))
        val html = fetchSite(signed)
        return Parsers.searchList(html).objects().map(Parsers::card)
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
        val signed = signers.sign("suggest", mapOf("keyword" to keyword))
        val raw = runCatchingIo { http.text(signed) }.getOrElse { return emptyList() }
        val data = runCatching { JSONObject(raw).optJSONObject(Fields.Envelope.DATA) }.getOrNull() ?: return emptyList()
        val rows = data.optJSONArray(Fields.Suggest.QUERY_RESULT) ?: return emptyList()
        return rows.objects().mapNotNull { o ->
            val video = o.optJSONObject(Fields.Suggest.VIDEO_DATA) ?: return@mapNotNull null
            val id = video.str(Fields.Suggest.SERIES_ID)
            if (!TitleKeys.ID.matches(id)) return@mapNotNull null
            val series = Series(
                id = id,
                title = o.str(Fields.Suggest.NAME),
                cover = Parsers.cleanUrl(video.str(Fields.Suggest.COVER)),
                badge = o.str(Fields.Suggest.SUG_ABSTRACT),
            )
            Suggestion(series.title, series)
        }.distinctBy { it.target?.id }
    }

    // ---------- 详情 ----------

    override suspend fun detail(seriesId: String): Detail {
        require(TitleKeys.ID.matches(seriesId)) { "剧集 id 无效：$seriesId" }
        // 全集顺序以服务端下发为准（本地不重排）；官网详情页 2026-10 起全网 404，不再回退。
        val signed = signers.sign("detail", mapOf("seriesId" to seriesId))
        val raw = http.text(signed)
        val envelope = runCatching { JSONObject(raw) }.getOrElse { throw UpstreamError.Shape("body") }
        val code = envelope.optInt(Fields.Envelope.CODE, envelope.optInt(Fields.Envelope.CODE_CAP, 0))
        if (code != 0) throw UpstreamError.RiskControl(code)
        val vd = envelope.optJSONObject(Fields.Envelope.DATA)?.optJSONObject(Fields.Detail.VIDEO_DATA)
            ?: throw UpstreamError.Shape("data.video_data")
        val vids = (vd.optJSONArray(Fields.Detail.VIDEO_LIST)?.objects() ?: emptyList())
            .map { it.optString(Fields.Detail.VID) }
            .filter { TitleKeys.ID.matches(it) }
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
            season = TitleKeys.seasonNumber(title),
        )
        return Detail(series, vids.mapIndexed { index, vid -> Episode(vid, index) })
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
        require(TitleKeys.ID.matches(seriesId)) { "剧集 id 无效：$seriesId" }
        val signed = signers.sign("seasonLine", mapOf("seriesId" to seriesId))
        val raw = http.text(signed)
        val data = runCatching { JSONObject(raw).optJSONArray(Fields.Envelope.DATA) ?: JSONArray() }
            .getOrElse { throw UpstreamError.Shape("body") }
        val entries = Parsers.planCell(data).toMutableList()
        if (anchor != null && entries.none { it.second.id == anchor.id }) {
            val n = TitleKeys.seasonNumber(anchor.title)
            entries += n to (if (n > 0) anchor.copy(season = n) else anchor)
        }
        return PlanLine(entries.sortedBy { it.first }.map { it.second }, isComic)
    }

    // ---------- 取流 ----------

    override suspend fun stream(vid: String, maxHeight: Int): StreamInfo {
        require(TitleKeys.ID.matches(vid)) { "剧集 id 无效：$vid" }
        // 旧入口 video_model / multi_video_model 2026-10 起返回 200 空体，preload 是唯一还吐数据的入口；
        // 其风控只放行 onedrama 6905554 同款简化请求（PreloadSigner 负责），设备号是本类自己的随机身份。
        val signed = signers.sign(
            "preload",
            mapOf("vid" to vid, "deviceId" to streamDeviceId, "installId" to streamInstallId),
        )
        val raw = http.text(signed)
        val envelope = runCatching { JSONObject(raw) }.getOrElse { throw UpstreamError.Shape("body") }
        val code = envelope.optInt(Fields.Envelope.CODE_CAP, envelope.optInt(Fields.Envelope.CODE, 0))
        if (code != 0) throw UpstreamError.RiskControl(code)
        val data = envelope.optJSONObject(Fields.Envelope.DATA) ?: throw UpstreamError.Shape("data")
        // preload 是批量接口：data 以 vid 为键，只认请求的那一集，不拿其他条目顶上。
        val entry = data.optJSONObject(vid) ?: throw UpstreamError.Shape("data.$vid")
        val fallback = Parsers.fallbackUrl(entry)
        val info = runCatching {
            JSONObject(http.text(SignedRequest(HttpMethod.GET, fallback, emptyMap(), null)))
                .optJSONObject(Fields.Stream.VIDEO_INFO)?.optJSONObject(Fields.Envelope.DATA)
        }.getOrNull() ?: throw UpstreamError.Shape("video_info.data")
        val videoList = info.optJSONObject(Fields.Stream.VIDEO_LIST) ?: throw UpstreamError.Shape("video_list")

        val rows = Parsers.qualityRows(videoList)
        if (rows.isEmpty()) throw UpstreamError.Shape("video_list")
        // 首选不超过上限的最高清晰度；没有就退到最低档（"兼容资源"，fellBack 单独成事实）
        val selected = rows.firstOrNull { it.first <= maxHeight } ?: rows.last()
        val item = selected.third
        var url = Parsers.cleanUrl(item.str(Fields.Stream.MAIN_URL))
        val seed = info.str(Fields.Stream.KEY_SEED)
        if (seed.isNotEmpty()) {
            url = runCatching { MediaCrypto.decryptUrl(url, seed) }.getOrElse { throw UpstreamError.Decrypt("url") }
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw UpstreamError.Shape("main_url")
        val key = item.str(Fields.Stream.SPADE_A).takeIf { it.isNotEmpty() }?.let { seedValue ->
            runCatching { MediaCrypto.deriveKey(seedValue) }.getOrElse { throw UpstreamError.Decrypt("key") }
        }
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
