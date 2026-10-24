package com.hongguotv.lite.data.content

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.SeriesIds
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.CoverUrls
import com.hongguotv.core.protocol.Fields
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "").trim()

/**
 * 搜索卡片 + 介质判定。[Series] 模型不带介质字段（这是刻意的：介质是筛选条件不是展示属性），
 * 所以漫剧/短剧的过滤必须发生在 JSON 行上，随卡片一起走。
 */
internal data class Card(val series: Series, val isComic: Boolean)

/** 官网搜索的一页：卡片 + 分页事实（`totalCount` 为 0 表示上游没给总数）。 */
internal data class SiteSearchPage(
    val cards: List<Card>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Int,
    val hasMore: Boolean,
)

/**
 * 上游 JSON → 领域模型的全部映射。字段名一律经 [Fields]，映射规则以旧仓库为验收基准。
 * 结构不合预期抛 [UpstreamError.Shape]（带上字段路径），不静默吞掉。
 */
internal object Parsers {

    /**
     * 上游给的地址有两个毛病：JSON 里的转义（`\/`、`&amp;`），以及有一部分封面是 HEIC 变体
     * （API 23 解不了，见 `CoverUrls`）。两件事都在这里一次做完——所有封面都经过这一个入口。
     */
    fun cleanUrl(s: String): String = CoverUrls.normalize(
        s.replace("\\/", "/").replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&").replace("&amp;", "&"),
    )

    /**
     * 卡片：搜索结果里的条目。介质判定（实测 2026-10-18）：celebrities 空 = 漫剧，有演员 = 真人短剧，
     * 两信号完全吻合；官网与 fqnovel 是同一套内容 ID 体系，celebrities 可直接采信。
     * 筛选三档语义：ALL=全部 / COMIC=漫剧 / SHORT=短剧（真人剧+AI剧，两者不区分）。
     */
    fun card(item: JSONObject): Card {
        val o = item.optJSONObject(Fields.Card.VIDEO_DATA) ?: item
        val count = o.optInt(
            Fields.Card.EPISODE_CNT,
            o.optJSONObject(Fields.Card.SERIES_EPISODE_INFO)?.optInt(Fields.Card.EPISODE_CNT, 0) ?: 0,
        )
        val celebs = o.optJSONArray(Fields.Card.CELEBRITIES)
        val title = o.str(Fields.Card.SERIES_NAME).ifEmpty { o.str(Fields.Card.SERIES_TITLE).ifEmpty { item.str(Fields.Card.NAME) } }
        val series = Series(
            id = o.str(Fields.Card.SERIES_ID),
            title = title,
            cover = cleanUrl(o.str(Fields.Card.SERIES_COVER)),
            intro = o.str(Fields.Card.SERIES_INTRO),
            badge = o.str(Fields.Card.EPISODE_RIGHT_TEXT).ifEmpty { if (count > 0) "全 $count 集" else "" },
            // tags 与 category_list 是同一信息的两种形态：前者可能为空/只有 id，
            // 这时必须退到后者，否则卡片预览的分类会时有时无（维护者实测）。
            tags = cardTags(o),
            season = SeasonKeys.seasonNumber(title),
            episodeCount = count,
        )
        return Card(series, isComic = celebs == null || celebs.length() == 0)
    }

    /**
     * 卡片分类。上游给分类有三种形态，必须按顺序试，少试一种就会丢分类：
     * 1. `tags` 数组（可能为空 / 只有 id）；
     * 2. `category_list` 数组（官网 SSR 用）；
     * 3. `category_schema`——**字符串形式的 JSON 数组**（plan / 相关作品卡片用，
     *    旧实现完全没读，于是季线卡片永远没有分类）。
     */
    private fun cardTags(o: JSONObject): String {
        val names = jsonNames(o.optJSONArray(Fields.Card.TAGS))
            .ifEmpty { jsonNames(o.optJSONArray(Fields.Card.CATEGORY_LIST)) }
            .ifEmpty { jsonNames(schemaArray(o)) }
        return names.distinct().take(5).joinToString(" · ")
    }

    /** `category_schema` 是 JSON 字符串：解不开就当没有分类，不让坏字段拖垮整张卡片。 */
    private fun schemaArray(o: JSONObject): JSONArray? {
        val raw = o.str(Fields.Card.CATEGORY_SCHEMA)
        if (raw.isEmpty()) return null
        return runCatching { JSONArray(raw) }.getOrNull()
    }

    private fun jsonNames(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index)
            (obj?.str(Fields.Card.NAME) ?: array.optString(index)).takeIf { it.isNotBlank() }
        }
    }

    /** 脏行过滤：id 必须是纯数字、标题非空，按 id 去重。 */
    fun valid(rows: List<Card>): List<Card> =
        rows.filter { SeriesIds.ID.matches(it.series.id) && it.series.title.isNotBlank() }.distinctBy { it.series.id }

    fun filterType(rows: List<Card>, filter: ContentFilter): List<Card> = when (filter) {
        ContentFilter.COMIC -> rows.filter { it.isComic }
        ContentFilter.SHORT -> rows.filter { !it.isComic }
        ContentFilter.ALL -> rows
    }

    /**
     * 官网搜索页（`/api/search/result`）：`searchList` + 分页事实。
     *
     * 这里**不再解析 HTML**：官网 2026-10-23 起换成 React SPA，`_ROUTER_DATA` 整个不再下发，
     * 页面改调这个 JSON 接口。分页是上游的真实能力（`page` 有效），界面据此一页 10 条地翻。
     */
    fun siteSearchPage(body: String): SiteSearchPage {
        val root = runCatching { JSONObject(body) }.getOrElse { throw UpstreamError.Shape("body") }
        val list = root.optJSONArray(Fields.Site.SEARCH_LIST)
            ?: throw UpstreamError.Shape(Fields.Site.SEARCH_LIST)
        return SiteSearchPage(
            cards = list.objects().map(::card),
            page = root.intOr(Fields.Site.PAGE_NUM, 1).coerceAtLeast(1),
            totalPages = root.intOr(Fields.Site.TOTAL_PAGES, 1).coerceAtLeast(1),
            totalCount = root.intOr(Fields.Site.TOTAL_COUNT, 0).coerceAtLeast(0),
            hasMore = root.optBoolean(Fields.Site.HAS_MORE, false),
        )
    }

    /** 上游把计数写成字符串（`"totalCount":"204"`）与数字两种形态都有，按字符串兜一层。 */
    private fun JSONObject.intOr(key: String, fallback: Int): Int = when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: fallback
        else -> fallback
    }

    /**
     * 官网联想词：`suggest_list[]`。`video_data` 是完整卡片时可以直接进详情，
     * 空对象时这一条只能当查询词（`word_type` 的后五种都是这样）。
     */
    fun suggestList(body: String): List<Suggestion> {
        val root = runCatching { JSONObject(body) }.getOrElse { throw UpstreamError.Shape("body") }
        val rows = root.optJSONArray(Fields.Suggest.SUGGEST_LIST)?.objects() ?: return emptyList()
        return rows.mapNotNull { row ->
            val video = row.optJSONObject(Fields.Suggest.VIDEO_DATA)
            val id = video?.str(Fields.Suggest.SERIES_ID).orEmpty()
            val name = row.str(Fields.Suggest.NAME)
                .ifEmpty { row.optJSONArray(Fields.Suggest.DISPLAY_WORDS)?.optString(0).orEmpty() }
            if (name.isBlank()) return@mapNotNull null
            val target = if (SeriesIds.ID.matches(id)) {
                Series(
                    id = id,
                    title = name,
                    cover = cleanUrl(video?.str(Fields.Card.SERIES_COVER).orEmpty()),
                    badge = video?.str(Fields.Card.EPISODE_RIGHT_TEXT).orEmpty(),
                )
            } else {
                null
            }
            Suggestion(name, target)
        }.distinctBy { it.keyword }
    }

    /**
     * plan 响应解析（纯函数，供单测）：data 里 show_type=547 的 cell 即「相关作品」。
     *
     * **判定只看卡片自己的季号，绝不看标题**（2026-10-24 第二次验收）：
     * 同 IP 作品的标题同样叫「…3D版第一季」「…月落篇第二季」，按标题兜底会把它们全当成
     * 本系列的季——维护者实测一条季线 37 季、一屏全是「第一季」。
     * `tag_info.text` 写「同IP」的卡片、或 `season_index` 缺失且标签解析不出季号的卡片，
     * 一律不进季线（产品口径：一部剧只有一个制作组能活）。
     *
     * 季号优先取 `season_index`（服务端序号），标签是展示文案、只作后备。
     */
    fun planCell(data: JSONArray): List<Pair<Int, Series>> {
        val cell = data.objects().firstOrNull { it.optInt(Fields.Plan.SHOW_TYPE) == Fields.Plan.SHOW_TYPE_RELATED }
            ?: return emptyList()
        val seasons = mutableListOf<Pair<Int, Series>>()
        for (card in cell.optJSONArray(Fields.Plan.VIDEO_DATA)?.objects() ?: emptyList()) {
            val o = card.optJSONObject(Fields.Plan.VIDEO_DATA) ?: card
            val id = o.str(Fields.Card.SERIES_ID)
            if (!SeriesIds.ID.matches(id)) continue
            val count = o.optInt(Fields.Card.EPISODE_CNT, 0)
            val title = o.str(Fields.Card.TITLE).ifEmpty { o.str(Fields.Card.SERIES_NAME) }
            val tag = card.optJSONObject(Fields.Plan.TAG_INFO)?.str(Fields.Plan.TAG_TEXT).orEmpty()
            // 标签写明「同IP」（可能带后缀，如「同IP作品」）= 服务端自己说它不是本系列，别猜
            if (tag.startsWith(SAME_IP_TAG, ignoreCase = true)) continue
            val n = o.optInt(Fields.Plan.SEASON_INDEX, 0).takeIf { it > 0 }
                ?: SeasonKeys.seasonNumber(tag)
            // 两个来源都读不出季号 = 同 IP 无季标签条目，不进入季线
            if (n <= 0) continue
            seasons += n to Series(
                id = id,
                title = title,
                cover = cleanUrl(o.str(Fields.Card.COVER)),
                intro = o.str(Fields.Card.VIDEO_DESC),
                badge = if (count > 0) "全 $count 集" else SeriesBadge.UNRELEASED,
                // 季线卡片自己也有分类（category_schema）；读不到时由 ContentRepository 从锚点传播
                tags = cardTags(o),
                season = n,
                released = count > 0,
                episodeCount = count,
            )
        }
        // 同一季号只留一张：服务端偶尔把两个制作组的「第 N 季」都塞进相关作品，
        // 取集数更多的那个（口径：只认市场认可的那个制作组，不让页面出现两张「第N季」）。
        return seasons.groupBy { it.first }.values.map { group ->
            group.maxByOrNull { it.second.episodeCount }!!
        }
    }

    /** 相关作品卡片上"不是本系列"的标签文案（服务端原文，大小写不敏感地比）。 */
    private const val SAME_IP_TAG = "同IP"

    /** 详情响应没有 tags 字段：分类在 category_schema（JSON 字符串），再退 secondary_infos 里 data_type=3 的标签位。 */
    fun detailTags(vd: JSONObject): String {
        val schema = runCatching { JSONArray(vd.str(Fields.Detail.CATEGORY_SCHEMA)).objects().map { it.str(Fields.Card.NAME) } }
            .getOrDefault(emptyList())
        val names = schema.filter { it.isNotBlank() }.ifEmpty {
            runCatching {
                (vd.optJSONArray(Fields.Detail.SECONDARY_INFOS)?.objects() ?: emptyList())
                    .filter { it.optInt(Fields.Detail.DATA_TYPE) == Fields.Detail.DATA_TYPE_CATEGORY }
                    .map { it.str(Fields.Detail.CONTENT) }
            }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        }
        return names.distinct().take(5).joinToString(" · ")
    }

    /**
     * 取流响应的 `data`（含 `video_model`）→ fallback_api 地址。历史上有对象/数组/字符串三种形态，全兼容；
     * 解不出合法地址抛 Shape——宁可响亮失败，不拿空串往下走。
     */
    fun fallbackUrl(entry: JSONObject): String {
        val model = entry.optJSONObject(Fields.Stream.VIDEO_MODEL)
            ?: runCatching { JSONObject(entry.str(Fields.Stream.VIDEO_MODEL)) }.getOrElse { throw UpstreamError.Shape("video_model") }
        val raw = model.opt(Fields.Stream.FALLBACK_API)
        val fallback = when (raw) {
            is JSONObject -> raw.str(Fields.Stream.FALLBACK_API)
            is JSONArray -> raw.optString(0)
            else -> runCatching { JSONObject(raw.toString()).str(Fields.Stream.FALLBACK_API) }.getOrDefault(raw.toString())
        }
        if (!fallback.startsWith("https://")) throw UpstreamError.Shape(Fields.Stream.FALLBACK_API)
        return fallback
    }

    /** "720P"/"1920"/"v0" → 质量数字。legacy 编码位缺失时按 key 兜底（旧行为）。 */
    fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt()
        ?: mapOf("1920" to 1080, "1280" to 720, "1024" to 576, "854" to 480, "640" to 360)[value]
        ?: value.toIntOrNull() ?: 0

    /**
     * 清晰度候选：过滤空地址与不兼容编码（ByteVC2 在 Android 的 MP4 提取器里可能只有音轨没有视频轨），
     * 按质量降序。返回 (质量, key, 条目)。
     */
    fun qualityRows(videoList: JSONObject): List<Triple<Int, String, JSONObject>> =
        videoList.keys().asSequence()
            .mapNotNull { key -> videoList.optJSONObject(key)?.let { key to it } }
            .filter { (_, o) ->
                o.str(Fields.Stream.MAIN_URL).isNotEmpty() &&
                    o.str(Fields.Stream.CODEC_TYPE).lowercase() !in Fields.Stream.INCOMPATIBLE_CODECS
            }
            .map { (key, o) ->
                Triple(
                    qualityNumber(
                        o.str(Fields.Stream.QUALITY_DESC).ifEmpty {
                            o.str(Fields.Stream.HEIGHT).ifEmpty {
                                o.str(Fields.Stream.VHEIGHT).ifEmpty {
                                    o.str(Fields.Stream.QUALITY).ifEmpty { key }
                                }
                            }
                        },
                    ),
                    key,
                    o,
                )
            }
            .sortedByDescending { it.first }
            .toList()
}

/** 徽标文案是协议的一部分（"未上线"决定 released 派生），集中在这一处。 */
internal object SeriesBadge {
    const val UNRELEASED = "未上线"
}
