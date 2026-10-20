package com.hongguotv.lite.data.content

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.UpstreamError
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

    fun cleanUrl(s: String): String = s.replace("\\/", "/").replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&").replace("&amp;", "&")

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
        val tags = o.optJSONArray(Fields.Card.TAGS) ?: o.optJSONArray(Fields.Card.CATEGORY_LIST) ?: JSONArray()
        val celebs = o.optJSONArray(Fields.Card.CELEBRITIES)
        val title = o.str(Fields.Card.SERIES_NAME).ifEmpty { o.str(Fields.Card.SERIES_TITLE).ifEmpty { item.str(Fields.Card.NAME) } }
        val series = Series(
            id = o.str(Fields.Card.SERIES_ID),
            title = title,
            cover = cleanUrl(o.str(Fields.Card.SERIES_COVER)),
            intro = o.str(Fields.Card.SERIES_INTRO),
            badge = o.str(Fields.Card.EPISODE_RIGHT_TEXT).ifEmpty { if (count > 0) "全 $count 集" else "" },
            tags = (0 until minOf(tags.length(), 5)).joinToString(" · ") { tags.optJSONObject(it)?.str(Fields.Card.NAME) ?: tags.optString(it) },
            season = TitleKeys.seasonNumber(title),
        )
        return Card(series, isComic = celebs == null || celebs.length() == 0)
    }

    /** 脏行过滤：id 必须是纯数字、标题非空，按 id 去重。 */
    fun valid(rows: List<Card>): List<Card> =
        rows.filter { TitleKeys.ID.matches(it.series.id) && it.series.title.isNotBlank() }.distinctBy { it.series.id }

    fun filterType(rows: List<Card>, filter: ContentFilter): List<Card> = when (filter) {
        ContentFilter.COMIC -> rows.filter { it.isComic }
        ContentFilter.SHORT -> rows.filter { !it.isComic }
        ContentFilter.ALL -> rows
    }

    /**
     * 官网 SSR 搜索结果：HTML → router 数据 → searchList 数组。结构变了带路径报 Shape。
     */
    fun searchList(html: String): JSONArray {
        val data = router(html).optJSONObject(Fields.Site.LOADER_DATA)
            ?: throw UpstreamError.Shape(Fields.Site.LOADER_DATA)
        val section = data.optJSONObject(Fields.Site.SEARCH_PAGE) ?: data.optJSONObject(Fields.Site.SEARCH_PAGE_ALT)
            ?: throw UpstreamError.Shape(Fields.Site.SEARCH_PAGE)
        return section.optJSONArray(Fields.Site.SEARCH_LIST)
            ?: throw UpstreamError.Shape("${Fields.Site.SEARCH_PAGE}.${Fields.Site.SEARCH_LIST}")
    }

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
            if (!TitleKeys.ID.matches(id)) continue
            val count = o.optInt(Fields.Card.EPISODE_CNT, 0)
            val n = TitleKeys.seasonNumber(card.optJSONObject(Fields.Plan.TAG_INFO)?.str(Fields.Plan.TAG_TEXT).orEmpty())
            // n<=0 的卡片是同 IP 无季标签条目，不进入季线
            if (n <= 0) continue
            seasons += n to Series(
                id = id,
                title = o.str(Fields.Card.TITLE).ifEmpty { o.str(Fields.Card.SERIES_NAME) },
                cover = cleanUrl(o.str(Fields.Card.COVER)),
                intro = o.str(Fields.Card.VIDEO_DESC),
                badge = if (count > 0) "全 $count 集" else SeriesBadge.UNRELEASED,
                tags = "",
                season = n,
                released = count > 0,
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
