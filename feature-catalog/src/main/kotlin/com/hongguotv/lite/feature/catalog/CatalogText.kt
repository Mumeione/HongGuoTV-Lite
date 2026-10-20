package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.LocalError
import com.hongguotv.core.port.UpstreamError

/**
 * 四页共用的文案派生：季号、时间、进度、预览三行、空态、失败。
 *
 * 全部是纯函数（不 import `android.*`），因为文案是"用户看到什么"的一部分，
 * 必须能在 JVM 单测里钉死——尤其是"风控空体绝不能显示成没有结果"这条。
 * 领域词汇以 `docs/CONTEXT.md` 为准：季键、季线、作品都由 core 的类型表达。
 */
internal object CatalogText {

    private val cnDigits = "零一二三四五六七八九"

    /** 季号用中文数字，与官方季名（「万妖图录传第五季」）一致；超过 99 季直接用阿拉伯数字。 */
    fun chineseNumber(n: Int): String {
        if (n <= 0 || n >= 100) return n.toString()
        return when {
            n < 10 -> cnDigits[n].toString()
            n == 10 -> "十"
            n < 20 -> "十" + cnDigits[n % 10]
            n % 10 == 0 -> cnDigits[n / 10] + "十"
            else -> cnDigits[n / 10] + "十" + cnDigits[n % 10]
        }
    }

    /** 「第七季」；无季号返回空串（单季剧不写季号）。 */
    fun season(n: Int): String = if (n > 0) "第${chineseNumber(n)}季" else ""

    /** `mm:ss`。手写补零，不用 `String.format`——它按默认 Locale 出数字，可能变成别的字符。 */
    fun clock(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return "${twoDigits(seconds / 60)}:${twoDigits(seconds % 60)}"
    }

    private fun twoDigits(value: Long): String = if (value < 10) "0$value" else value.toString()

    /** 卡片角标：`第 9 集 · 12:34`。 */
    fun progress(index: Int, positionMs: Long): String = "第 ${index + 1} 集 · ${clock(positionMs)}"

    /** 预览第三行：分类与集数。上游的 `" · "` 分隔改成 `" / "`，与集数分隔区分开。 */
    fun meta(series: Series): String = listOf(series.badge, series.tags.replace(" · ", " / "))
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")

    /**
     * 预览第二行：状态。**有记录就必须说出来**——"上次看到第七季 第 9 集"是用户回到这一页的理由。
     * 多季剧要把季名带上，否则同一部剧的几条记录分不清是哪一季。
     */
    fun previewStatus(favorite: Boolean, season: Int, progress: Progress?, watched: Boolean): String {
        val parts = mutableListOf<String>()
        if (favorite) parts += "已收藏"
        val seasonPart = season(season).let { if (it.isBlank()) "" else " $it" }
        val tail = when {
            progress != null && !progress.completed ->
                "上次看到$seasonPart 第 ${progress.episodeIndex + 1} 集 · ${clock(progress.positionMs)}"
            progress != null -> "上次已看完整季"
            watched -> "整剧已看完"
            else -> ""
        }
        if (tail.isNotBlank()) parts += tail
        return parts.joinToString(" · ")
    }

    /** 搜索空态。分类筛选下的空结果先提示换分类，否则用户会以为这部剧不存在。 */
    fun emptySearch(keyword: String, filter: ContentFilter): String = when {
        keyword.isBlank() -> "输入完整剧名，按确认开始搜索"
        filter != ContentFilter.ALL -> "该分类下没有找到相关剧集，试试切换到「全部」"
        else -> "没有找到相关剧集，试试输入完整剧名"
    }

    /**
     * 失败文案按**类型**映射，不匹配中文字符串。
     * 风控空体（[UpstreamError.EmptyBody]）必须单独说"被限制"，不能说成"没有内容"——
     * 后者会让用户去换剧名，而不是等一会儿（`docs/CONTEXT.md`「风控空体」）。
     */
    fun error(cause: Throwable): String = when (cause) {
        is UpstreamError.Network -> "连不上内容网站，请检查网络后重试。"
        is UpstreamError.HttpStatus -> "服务暂时不可用（${cause.code}），请稍后重试。"
        is UpstreamError.EmptyBody -> "请求被临时限制，请稍后重试。"
        is UpstreamError.RiskControl -> "请求被限制（${cause.code}），请稍后再试。"
        is UpstreamError.Shape -> "内容结构发生变化，暂时无法显示。"
        is UpstreamError.Decrypt -> "内容解析失败，请稍后重试。"
        is LocalError -> "读取本机数据失败，请重试。"
        else -> "内容加载失败，请稍后重试。"
    }

    /**
     * 搜索结果的展示序。端口给的 [raw] 已是"季线新→旧 + 其余原序"；
     * 用户切到正序时把**季线成员**按季线（旧→新）重排，其余条目保持原序。
     *
     * 调用方每次都传 [raw]，不要拿上一次的结果再排一次——正序结果不是"另一种原序"。
     */
    fun ordered(raw: List<Series>, line: List<Series>, ascending: Boolean): List<Series> {
        if (!ascending || line.size < 2) return raw
        val shown = raw.mapTo(HashSet(raw.size)) { it.id }
        val lineOrder = line.filter { it.id in shown }
        val lineIds = lineOrder.mapTo(HashSet(lineOrder.size)) { it.id }
        return lineOrder + raw.filter { it.id !in lineIds }
    }
}
