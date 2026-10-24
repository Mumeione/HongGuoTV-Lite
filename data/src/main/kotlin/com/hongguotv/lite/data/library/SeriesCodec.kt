package com.hongguotv.lite.data.library

import com.hongguotv.core.model.Series
import com.hongguotv.lite.data.content.SeriesBadge
import com.hongguotv.lite.data.content.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * Series 的磁盘 JSON。键名与旧版逐字相同（旧版 prefs 必须能读，见交接 01 的坑 3），
 * 唯一映射：旧盘的 description → 模型的 intro。
 *
 * released 不落盘：由 badge 派生（"未上线" 是协议文案，[SeriesBadge.UNRELEASED]）。
 * createTime / isComic 是旧格式的遗留列，写 0 / false 占位以保持行结构稳定。
 */
internal object SeriesCodec {

    fun toJson(series: Series): JSONObject = JSONObject()
        .put("id", series.id)
        .put("title", series.title)
        .put("cover", series.cover)
        .put("description", series.intro)
        .put("badge", series.badge)
        .put("tags", series.tags)
        .put("createTime", 0L)
        .put("isComic", false)
        .put("season", series.season)
        // 所属专辑：季线并入与"是不是同 IP 作品"都靠它，必须跟着收藏行走（旧盘没有这个键，读回空串）
        .put("albumId", series.albumId)
        // 收藏卡"更新到多少集"的数据来源；旧盘没有这个键，读回 0（等下次检查更新/搜索补上）
        .put("episodeCnt", series.episodeCount)

    fun fromJson(o: JSONObject): Series = Series(
        id = o.str("id"),
        title = o.str("title"),
        cover = o.str("cover"),
        intro = o.str("description"),
        badge = o.str("badge"),
        tags = o.str("tags"),
        season = o.optInt("season", 0),
        albumId = o.str("albumId"),
        released = o.str("badge") != SeriesBadge.UNRELEASED,
        episodeCount = o.optInt("episodeCnt", 0),
    )

    /** 坏行跳过、不拖垮整个分区；调用方需要感知时给 onCorrupt。 */
    fun decodeList(raw: String?, onCorrupt: (Throwable) -> Unit = {}): List<Series> {
        if (raw.isNullOrEmpty()) return emptyList()
        val rows = runCatching { JSONArray(raw) }.onFailure(onCorrupt).getOrNull() ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            runCatching { fromJson(rows.getJSONObject(i)) }.onFailure(onCorrupt).getOrNull()
        }
    }
}
