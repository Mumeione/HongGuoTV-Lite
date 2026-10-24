package com.hongguotv.core.port

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.Episode
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.Settings
import com.hongguotv.core.model.StreamInfo
import com.hongguotv.core.model.Suggestion

/**
 * 端口 = 接缝。三个 feature 只认识这些接口，实现由 app 注入。
 *
 * 约定（每个端口的接口都包含这些，不只是方法签名）：
 * - 实现必须线程安全，调用方不保证在哪个线程调。
 * - 运行期失败一律以 [UpstreamError] 或 [LocalError] 抛出，不用魔法返回值。
 *   例外：端点表之类的静态配置错误属于**装配错误**，在启动时直接抛参数异常——
 *   把它们包成 UpstreamError 会让人误以为上游变了。
 * - 不返回"未过滤/已排序不清楚"的集合：语义写进返回类型名。
 */

/** 内容端口：5 个方法。上游的端点、签名、字段、季线排序、介质过滤全部藏在背后。 */
interface ContentPort {
    /** 搜索：官网服务端分页，一页 10 条；[page] 从 1 起。 */
    suspend fun search(keyword: String, filter: ContentFilter, page: Int = 1): SearchResult
    suspend fun suggest(keyword: String): List<Suggestion>

    /** 详情：分集清单以服务端顺序为准。 */
    suspend fun detail(seriesId: String): Detail

    /** 季线：旧 → 新。[anchor] 用于把本尊按季号并入（plan cell 不含锚点）。 */
    suspend fun seasons(seriesId: String, anchor: Series?): SeasonLine

    /** 取流：返回解密后的地址与内容密钥。 */
    suspend fun stream(vid: String, maxHeight: Int): StreamInfo
}

/** 进度端口。写操作是"提交意图"，实现自行合并与节流；调用方不关心写盘时机。 */
interface ProgressPort {
    fun progressOf(seriesId: String): Progress?

    /** 历史按更新时间降序。[HistoryEntry] 同时给出作品元数据：历史页要标题与封面。 */
    fun history(limit: Int = 30): List<HistoryEntry>
    fun isWatched(seriesId: String): Boolean
    fun watchedUpTo(seriesId: String): Int
    suspend fun save(progress: Progress)

    /**
     * 补全**已有**历史行的作品元数据（标题 / 封面 / 分类）。
     *
     * [save] 只写进度，元数据靠"上次那条记录"沿用——旧版本落盘的记录里分类常常是空的，
     * 历史页的信息栏于是缺一段。播放页拿到详情时把完整 [Series] 递进来，把这一行补齐。
     * **只更新已存在的行**：不因为浏览详情就凭空造一条"看过"。
     */
    suspend fun rememberSeries(series: Series)
    suspend fun setWatched(seriesId: String, watched: Boolean)
    suspend fun removeHistory(seriesId: String)
    suspend fun clearHistory()
}

/** 收藏端口。收藏的是系列（去季号的 base），不是单季。 */
interface FavoritesPort {
    fun all(): List<FavoriteEntry>
    fun isFavorite(seriesKey: String): Boolean
    fun entry(seriesKey: String): FavoriteEntry?
    suspend fun toggle(series: Series): Boolean
    /**
     * 把收藏的"当前季"换成 [series]：系列页里点了哪一季，之后卡片角标与「继续播放」就以它为准。
     * 不删任何季的进度——切换浏览不该抹掉"看过"的痕迹（旧行为）。
     */
    suspend fun setCurrentSeason(seriesKey: String, series: Series)
    /** 季线落库（LRU）。 */
    fun seasonLine(seriesKey: String): SeasonLine?
    fun rememberSeasonLine(seriesKey: String, line: SeasonLine)
    fun acknowledge(seriesId: String, totalEpisodes: Int, at: Long)
}

/** 设置端口。读同步、写异步；六项偏好一个入口。 */
interface SettingsPort {
    fun current(): Settings
    suspend fun update(transform: (Settings) -> Settings)
}

/**
 * 媒体端口：把"地址 + 密钥"变成一个可读流。
 * 调用方拿到的是 [MediaStream]，不知道 Range、解密、MP4 索引的存在。
 */
interface MediaPort {
    suspend fun open(vid: String, maxHeight: Int): MediaStream
}

interface MediaStream {
    val length: Long
    /** 顺序读取；实现内部负责分段拉取与逐样本解密。 */
    fun read(offset: Long, size: Int): ByteArray
    fun close()
}

/** 导航端口。feature 之间唯一的通信方式：说"要去哪"，不说"怎么去"。 */
interface Navigator {
    fun toCatalog(tab: Int)
    fun toDetail(seriesId: String, resumeRecording: Boolean = false)
    fun toSeries(seriesKey: String)
    fun toPlayer(seriesId: String, episodeIndex: Int, positionMs: Long = 0)
    fun back()
}

/** 封面端口：调用方只给"要什么规格"，策略在实现里。 */
interface ArtworkPort {
    fun load(host: Any, url: String, spec: ArtworkSpec)
    fun cancelPage(clearMemory: Boolean)
    fun trim(level: Int)
}

data class ArtworkSpec(
    val widthPx: Int,
    val heightPx: Int,
    /** 是否允许落盘（只有收藏/历史封面允许，避免占电视存储）。 */
    val persist: Boolean = false,
    val priority: Int = PRIORITY_VISIBLE,
) {
    companion object {
        /**
         * 优先级：**数值越小越先做**（沿用旧实现的排序口径）。
         * 光标当前所在项最先——用户马上要看到它。
         */
        const val PRIORITY_FOCUSED = 0
        /** 已在屏幕内但未聚焦：常规优先级（也是默认值）。 */
        const val PRIORITY_VISIBLE = 1
    }
}

/** 诊断端口：五段探针与关键日志的唯一出口，保证日志字段不随重构漂移。 */
interface DiagnosticsPort {
    fun stage(stage: Stage, ok: Boolean, detail: String)
    fun event(name: String, fields: Map<String, Any?>)

    enum class Stage { SIGN, CONNECT, PARSE, RESOLVE, DECRYPT }
}

/**
 * 不记任何东西。测试与"组装层还没接诊断"的场合用它，避免每个构造点写判空分支。
 *
 * 注意：**不要在 [DiagnosticsPort] 里给默认空实现**——那会让"忘了接诊断"变成静默通过；
 * 默认值应该是一个显式的对象，谁在构造时用了一目了然。
 */
object SilentDiagnostics : DiagnosticsPort {
    override fun stage(stage: DiagnosticsPort.Stage, ok: Boolean, detail: String) = Unit
    override fun event(name: String, fields: Map<String, Any?>) = Unit
}

/** 失败分类：UI 只按类型映射文案，不再匹配中文字符串。 */
sealed class UpstreamError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : UpstreamError("network", cause)
    class HttpStatus(val code: Int) : UpstreamError("http $code")
    /** 200 空体：风控的强特征，必须单独成类，绝不能归成"这部剧没有剧集"。 */
    class EmptyBody : UpstreamError("empty body")
    /** 结构变化：带上出错的字段路径，一眼看出上游改了哪个字段。 */
    class Shape(val path: String) : UpstreamError("shape $path")
    class RiskControl(val code: Int) : UpstreamError("risk control $code")
    class Decrypt(val step: String) : UpstreamError("decrypt $step")
    /** 设备注册失败（被拒或响应没有下发身份）：内容接口按设备放行，没有身份就取不到数据。 */
    class Device(val step: String) : UpstreamError("device $step")
}

sealed class LocalError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Storage(cause: Throwable) : LocalError("storage", cause)
    class Corrupt(val partition: String, cause: Throwable?) : LocalError("corrupt $partition", cause)
}
