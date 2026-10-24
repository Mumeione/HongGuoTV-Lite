package com.hongguotv.lite.data.content

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.SeriesIds
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
     * 官网搜索结果：HTML → router 数据 → searchList 数组。结构变了带路径报 Shape。
     *
     * **例外：官网把搜索页改成纯前端渲染了**（2026-10-23 实测）。页面 body 只剩
     * `<div id="root"><!--<?- html ?>--></div>` 与一堆 JS，`_ROUTER_DATA` 整个不再下发——
     * 这不是"结构变了"，客户端拿不到任何可解析的内容，报 Shape 只会让用户看到
     * 「内容结构发生变化」，而实际上一条结果都没法取。这时按"没有结果"返回，
     * 由页面显示「没找到」，并把这次降级记进诊断（`ContentRepository.siteCards`）。
     *
     * 判据就是标记在不在：标记在、路径不在 → 真的结构变了，照旧报 Shape。
     */
    fun searchList(html: String): JSONArray {
        if (!html.contains(Fields.Site.ROUTER_DATA)) return JSONArray()
        val data = router(html).optJSONObject(Fields.Site.LOADER_DATA)
            ?: throw UpstreamError.Shape(Fields.Site.LOADER_DATA)
        val section = data.optJSONObject(Fields.Site.SEARCH_PAGE) ?: data.optJSONObject(Fields.Site.SEARCH_PAGE_ALT)
            ?: throw UpstreamError.Shape(Fields.Site.SEARCH_PAGE)
        return section.optJSONArray(Fields.Site.SEARCH_LIST)
            ?: throw UpstreamError.Shape("${Fields.Site.SEARCH_PAGE}.${Fields.Site.SEARCH_LIST}")
    }

    /** 官网这一页是否已经是"纯前端壳"（没有服务端数据，只有 JS）：诊断与降级共用这一条判据。 */
    fun clientRendered(html: String): Boolean = !html.contains(Fields.Site.ROUTER_DATA)

    /**
     * HTML 里扒 _ROUTER_DATA 的 JSON（嵌套花括号配对，SSR 页面没有现成边界）。
     * 纯函数，供单测。
     */
    fun router(html: String): JSONObject {
        val marker = html.indexOf(Fields.Site.ROUTER_DATA)
        if (marker < 0) throw UpstreamError.Shape(Fields.Site.ROUTER_DATA)
        val first = html.indexOf('{', marker)
        if (first < 0) throw UpstreamError.Shape(Fields.Site.ROUTER_DATA)
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in first until html.length) {
            val ch = html[i]
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                continue
            }
            when (ch) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return JSONObject(html.substring(first, i + 1)) }
            }
        }
        throw UpstreamError.Shape(Fields.Site.ROUTER_DATA)
    }

    /**
     * plan 响应解析（纯函数，供单测）：data 里 show_type=547 的 cell 即「相关作品」。
     * 卡片 tag_info.text 带「第N季」→ 季线；无季标签 → 同 IP 卡片（明确不展示，解析后丢弃）。
     * 介质不在卡片上：plan 卡片无 celebrities 字段，过滤由调用方按锚点整线传播。
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
            val n = SeasonKeys.seasonNumber(card.optJSONObject(Fields.Plan.TAG_INFO)?.str(Fields.Plan.TAG_TEXT).orEmpty())
            // n<=0 的卡片是同 IP 无季标签条目，不进入季线
            if (n <= 0) continue
            seasons += n to Series(
                id = id,
                title = o.str(Fields.Card.TITLE).ifEmpty { o.str(Fields.Card.SERIES_NAME) },
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
        return seasons
    }

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
     * preload 响应的条目 → fallback_api 地址。历史上有对象/数组/字符串三种形态，全兼容；
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
