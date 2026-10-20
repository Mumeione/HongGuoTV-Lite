package com.hongguotv.core.model

/**
 * 领域模型。刻意与协议字段解耦：这里的字段名谈的是业务，不是上游 JSON。
 * 上游改字段名，只影响 data 层的映射，不影响这些类型。
 */

data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val intro: String = "",
    val badge: String = "",
    val tags: String = "",
    /** 季号；0 = 单季或未知。仅用于收藏 base 键与季线定序。 */
    val season: Int = 0,
    /** false = 未上线季（episode_cnt=0）：保留在季线中但不可点击、不参与连播。 */
    val released: Boolean = true,
)

data class Episode(
    /** 播放标识。上游会重签，永不落盘播放地址，只落盘这个。 */
    val vid: String,
    val index: Int,
)

data class Detail(
    val series: Series,
    /** 播出顺序以服务端为准，本地不重排。 */
    val episodes: List<Episode>,
)

/** 季线：同一系列的各个季，旧 → 新。同 IP 卡片按产品决策不进入此类型。 */
data class SeasonLine(val seasons: List<Series>)

/**
 * 取流结果。高度与"是否降级"是两个独立事实——
 * 旧实现把它们拼成 `"720P（兼容资源）"` 一个展示串，导致调用方必须再解析中文。
 */
data class StreamInfo(
    val url: String,
    /** CENC 内容密钥；null = 未加密。 */
    val key: ByteArray?,
    val height: Int?,
    val fellBack: Boolean,
)

data class Progress(
    val seriesId: String,
    val episodeVid: String,
    val episodeIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
)

data class FavoriteEntry(
    val series: Series,
    val addedAt: Long,
    val status: FavoriteStatus,
    /** 季线里的最新季号；0 = 未知。 */
    val latestSeason: Int = 0,
)

enum class FavoriteStatus { UP_TO_DATE, NEW_SEASON, UNCHECKED, FAILED }

enum class ContentFilter { ALL, SHORT, COMIC }

enum class FrameMode { FIT, ZOOM, FILL }

data class Settings(
    val quality: Int = 1080,
    val speed: Float = 1f,
    val frame: FrameMode = FrameMode.FIT,
    val autoNext: Boolean = true,
    val filter: ContentFilter = ContentFilter.ALL,
    val startupTab: Int = 0,
    val seasonSortAsc: Boolean = false,
)

/**
 * 搜索结果。两个字段语义不同，用类型名写清楚，不靠注释：
 * - [items]：已按当前筛选与排序处理好的展示序（UI 直接用）
 * - [seasonLine]：未过滤的完整季线，旧 → 新（落库用）
 */
data class SearchResult(
    val items: List<Series>,
    val seasonLine: List<Series>,
)

/** 建议联想：允许不完整（实测会漏个别季），只作输入辅助。 */
data class Suggestion(val keyword: String, val target: Series?)
