package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Series

/**
 * 四页共用的文案派生：卡片角标、空态、正序/倒序、信息栏说明段。
 *
 * 全部是纯函数（不 import `android.*`），因为文案是"用户看到什么"的一部分，
 * 必须能在 JVM 单测里钉死——尤其是"风控空体绝不能显示成没有结果"这条。
 * 时间、集数、季号、失败文案这四类**跨 feature 共用**的派生在 `uikit` 的 `TvText`，
 * 这里只管目录页独有的几种句子；领域词汇以 `docs/CONTEXT.md` 为准。
 */
internal object CatalogText {

    /**
     * 预览第三行：这部剧本身的属性——收藏的更新进度、集数、分类。
     *
     * 三个来源分开排：**它的状态**（更新到哪、共几集、什么分类）走这一行；
     * 我的状态（看到哪）在卡片状态条上，B 方案不再有预览区。混在一行会变成
     * "已收藏 · 上次看到第九季 第 20 集 · 05:00 · 更新到第九季第352集" 这种一长串，
     * 中间还夹着两种写法（维护者实测"这行文字的排列也不美观"）。
     *
     * 上游的 `" · "` 分隔改成 `" / "`，与行间的 `"  ·  "` 区分开。
     */
    fun meta(series: Series, update: String = ""): String = listOf(update, series.badge, series.tags.replace(" · ", " / "))
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")

    /**
     * 底部信息栏的说明段（B 方案）：搜索/历史结果 = **已收藏 + 分类**。
     *
     * 维护者口径："信息栏只写信息和收藏状态、更新情况，不写观看记录"——
     * 我的状态（看到第几集）在卡片状态条与详情页说，信息栏不重复。
     */
    fun bandSegments(favorite: Boolean, tags: String): List<String> =
        listOfNotNull("已收藏".takeIf { favorite }, tags.takeIf { it.isNotBlank() }?.replace(" · ", " / "))

    /**
     * 历史页信息栏的分类来源（审计 2026-10-22 #5-b）：历史记录的 `tags` 经常是空串
     * （早期记录只存了片名/封面），回退到收藏记录里那份（收藏时会补全）。
     *
     * **不拿角标兜底**：角标是「全 90 集」这类集数文案，塞进分类位会被读成"分类=90 集"。
     * 两处都没有分类时宁可不显示——信息栏少一段，比写错一段好。
     */
    fun historyTags(historyTags: String, favoriteTags: String): String =
        listOf(historyTags, favoriteTags).firstOrNull { it.isNotBlank() } ?: ""

    /** 收藏页信息栏 = **已收藏 + 更新到…**（还没检查出来时只说收藏状态）。 */
    fun favoriteSegments(update: String): List<String> =
        listOfNotNull("已收藏", update.takeIf { it.isNotBlank() })

    /** 搜索空态。分类筛选下的空结果先提示换分类，否则用户会以为这部剧不存在。 */
    fun emptySearch(keyword: String, filter: ContentFilter): String = when {
        keyword.isBlank() -> "输入完整剧名，按确认开始搜索"
        filter != ContentFilter.ALL -> "该分类下没有找到相关剧集，试试切换到「全部」"
        else -> "没有找到相关剧集，试试输入完整剧名"
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
