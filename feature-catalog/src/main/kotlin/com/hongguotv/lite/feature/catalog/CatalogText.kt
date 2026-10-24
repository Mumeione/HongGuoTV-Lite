package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.Series
import com.hongguotv.lite.uikit.text.TvText

/**
 * 四页共用的文案派生：卡片角标、空态、正序/倒序、信息栏说明段。
 *
 * 全部是纯函数（不 import `android.*`），因为文案是"用户看到什么"的一部分，
 * 必须能在 JVM 单测里钉死——尤其是"风控空体绝不能显示成没有结果"这条。
 * 时间、集数、季号、失败文案这四类**跨 feature 共用**的派生在 `uikit` 的 `TvText`，
 * 这里只管目录页独有的几种句子；领域词汇以 `docs/CONTEXT.md` 为准。
 */
internal object CatalogText {

    /** 搜索页一页几张：两行 × 5 列（1080p 的实测列数），也是上游一页的条数。 */
    const val PAGE_SIZE = 10

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
     * 搜索卡角标。[aggregated] = 这张卡来自完整季线聚合：角标带「第N季」，
     * 不用聚焦就能分清同一部剧的各季；非聚合（纯网页结果）维持"全 N 集"。
     * 未上线永远只写「未上线」（旧口径）。
     */
    fun searchBadge(series: Series, aggregated: Boolean = false): String = when {
        !series.released -> "未上线"
        aggregated && series.season > 0 && series.episodeCount > 0 ->
            "${TvText.season(series.season)} · 全 ${series.episodeCount} 集"
        aggregated && series.season > 0 -> TvText.season(series.season)
        series.episodeCount > 0 -> "全 ${series.episodeCount} 集"
        else -> ""
    }

    /**
     * 底部信息栏的说明段（B 方案）：搜索/历史结果 = **集数 + 已收藏 + 分类**。
     *
     * 维护者口径："信息栏只写信息和收藏状态、更新情况，不写观看记录"——
     * 我的状态（看到第几集）在卡片状态条与详情页说，信息栏不重复。
     * [badge] 用 [searchBadge] 的非聚合口径（集数/未上线），片名里已带季号时不再重复。
     */
    fun bandSegments(favorite: Boolean, tags: String, badge: String = ""): List<String> =
        listOfNotNull(
            badge.takeIf { it.isNotBlank() },
            "已收藏".takeIf { favorite },
            tags.takeIf { it.isNotBlank() }?.replace(" · ", " / "),
        )

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
     * 搜索分页行：`第 2 / 20 页 · 共 200 条`。
     *
     * **不按"第 11—20 条"写**：`totalCount` 是聚合估计（季线 + 服务端总数扣重叠），
     * 用它推区间会与"共 N 条"自相矛盾。页码 + 总数两句都是"尽力估计的事实"，
     * 精确边界由「下一页」按钮的可用性兜底（窗口还有数据或服务端还有下一页）。
     */
    fun searchRange(page: Int, totalPages: Int, totalCount: Int): String {
        val pages = totalPages.coerceAtLeast(1)
        val current = page.coerceIn(1, pages)
        return if (totalCount > 0) "第 $current / $pages 页 · 共 $totalCount 条" else "第 $current / $pages 页"
    }
}
