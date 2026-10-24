package com.hongguotv.lite.feature.catalog

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hongguotv.core.model.SearchResult
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.Navigator
import com.hongguotv.core.port.ProgressPort
import com.hongguotv.core.port.SettingsPort
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.ui.UiKit
import kotlinx.coroutines.CoroutineScope

/**
 * 目录类四页的入口（S4）。`app` 组装一次、注入端口，`view()` 拿到视图，`show(tab)` 切页。
 *
 * 状态归属：搜索词与结果属于本类（切页不丢，回到搜索页还在）；收藏/历史/设置属于端口背后
 * 的本地库，页面每次读最新值。焦点位置按页记在 [lastFocus]，回来时按 key 恢复。
 *
 * 依赖只到 `core.port` 与 `uikit`：换存储介质、换页面外壳都不需要改这里。
 */
class CatalogFeature(
    val ui: UiKit,
    val content: ContentPort,
    val progress: ProgressPort,
    val favorites: FavoritesPort,
    val settings: SettingsPort,
    val navigator: Navigator,
    /** 主线程作用域，由 `app` 提供（`Dispatchers.Main`）。feature 不自建全局作用域。 */
    val scope: CoroutineScope,
    /** 设置页脚显示的版本号；空串则不显示版本。 */
    val versionName: String = "",
    initialTab: Int = 0,
) {

    private val container = FrameLayout(ui.context)
    private var tab: CatalogTab = CatalogTab.of(initialTab)
    private var page: CatalogPage? = null

    /**
     * 当前页。**tab 的唯一所有者是本类**：宿主需要知道"用户在哪一页"时读这里，
     * 不要在自己的路由里再存一份（两份必然漂移）。
     */
    val currentTab: CatalogTab get() = tab

    /** 每页最后一次的焦点 key：切页时保存，回来时按它落点（不依赖 ScrollView 的滚动位置）。 */
    private val lastFocus = LinkedHashMap<CatalogTab, FocusKey>()

    /** 搜索结果与关键词。放这里而不是页面里：切到收藏再回来，用户不该重搜一次。 */
    internal val searchModel = SearchModel()

    /** 惰性搭首屏：`app` 拿到视图就能用，不需要额外的一次 `start()` 调用。 */
    fun view(): View {
        if (page == null) show(tab)
        return container
    }

    fun show(next: CatalogTab) {
        if (page != null && next == tab) return
        page?.let { current ->
            current.currentFocus()?.let { lastFocus[tab] = it }
            current.release()
        }
        container.removeAllViews()
        page = null
        tab = next

        val created: CatalogPage = when (next) {
            CatalogTab.SEARCH -> SearchPage(this)
            CatalogTab.FAVORITES -> FavoritesPage(this)
            CatalogTab.HISTORY -> HistoryPage(this)
            CatalogTab.SETTINGS -> SettingsPage(this)
        }
        page = created
        container.addView(created.view(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        created.build(lastFocus[next])
    }

    /** 离开目录（进详情/播放或宿主销毁）：取消在途请求与收藏更新检查。 */
    fun release() {
        val current = page ?: return
        // 先把光标存下来再丢页面：只切 tab 时存过，从详情返回这一路一直没存——
        // 「从详情返回目录，光标还停在那张卡上」就是缺了这一行
        current.currentFocus()?.let { lastFocus[tab] = it }
        current.release()
        page = null
    }

    /**
     * MENU 键（B 方案：历史页"管理走 MENU"）。由宿主在目录页转发；
     * 返回是否被消费（消费了宿主就不再走系统默认）。
     */
    fun onMenuKey(): Boolean = page?.onMenuKey() ?: false
}

/**
 * 搜索页的状态：一个关键词 + **内存聚合视图**（2026-10-24 下午规格）。
 *
 * 条目仍按服务端分页拉（一页 10 条），但拉过就进内存；完整剧名命中季线后，
 * 季线整段（含未上线季）一次请求进内存。**聚合态只显示季线**（新→旧，完整剧名的
 * 目的就是只看这部剧，杂项不混进来）；未聚合 = 网页原序（第 1 页 + 已缓存的后续页）。
 * 翻页与正/倒序都是在这份视图上滑窗/重排，只有窗口尾部超出缓存时才补拉服务端
 * ——低配电视上的代价是 KB 级内存与一次毫秒级列表操作，网格每页仍只绑 10 张。
 *
 * 去重是必要的：上游翻页会重叠（实测第 2 页开头重复第 1 页末尾的季）。
 * 去重只比"先到手的数据"，同一页反复吸收结果稳定，翻回去不会被过滤空。
 */
internal class SearchModel {
    var keyword: String = ""

    /** 当前窗口页（1 起），一页 [CatalogText.PAGE_SIZE] 张。 */
    var page: Int = 1

    /** 服务端口径：关键词总条数、页数、还有没有下一页（估计与补拉边界用）。 */
    var totalCount: Int = 0
    var totalPages: Int = 1
    var hasMore: Boolean = false

    var loading: Boolean = false
    var error: Throwable? = null
    var hasSearched: Boolean = false

    /** 季线整段，存储序 = 新→旧（默认展示序）。空 = 未聚合（纯网页结果）。 */
    var seasons: List<Series> = emptyList()
        private set

    /** 第 1 页原序（锚点识别、"其余条目"的第 1 段）。 */
    var firstPage: List<Series> = emptyList()
        private set

    /** 第 2 页起的"其余条目"，按服务端页缓存（已对先到手的数据去重）。 */
    private val restPages = LinkedHashMap<Int, List<Series>>()

    /** 服务端已拉到第几页（补拉按顺序，页与页的去重要求前面的页在场）。 */
    private var serverFetched: Int = 0

    /** 当前窗口（页面渲染用），由 [present] / [reorder] 维护。 */
    var items: List<Series> = emptyList()
        private set

    /** 是否处于季线聚合态（卡片角标与空态文案的分支条件）。 */
    val aggregated: Boolean get() = seasons.isNotEmpty()

    /** 服务端口径下是否还有下一页可拉。聚合态季线已全在内存，不再拉杂项。 */
    val serverHasNext: Boolean get() = !aggregated && hasMore && serverFetched < totalPages

    /**
     * 聚合总量的估计：**聚合态只显示季线**（完整剧名的目的就是只看这部剧，
     * 其余杂项不混进来）；未聚合 = 服务端给的总量。
     */
    val estimatedTotal: Int
        get() = if (aggregated) seasons.size else maxOf(knownCount, totalCount)

    /** 页数估计（展示用，至少 1）；「下一页」的可用性还看 [serverHasNext]。 */
    val pageCount: Int get() = ((estimatedTotal + CatalogText.PAGE_SIZE - 1) / CatalogText.PAGE_SIZE).coerceAtLeast(1)

    /** 下一页是否可用：窗口里还有数据，或服务端还有页可拉。 */
    val hasNext: Boolean get() = page < pageCount || serverHasNext

    private val seasonIds: Set<String> get() = seasons.mapTo(HashSet(seasons.size)) { it.id }

    /** 当前内存里的展示序条数（与排序方向无关，窗口/估计都用它）。 */
    private val knownCount: Int get() = view(ascending = false).size

    private fun view(ascending: Boolean): List<Series> {
        val line = if (ascending) seasons.asReversed() else seasons
        if (aggregated) return line
        return firstPage + restPages.values.flatten()
    }

    fun clearResult() {
        page = 1
        totalCount = 0
        totalPages = 1
        hasMore = false
        seasons = emptyList()
        firstPage = emptyList()
        restPages.clear()
        serverFetched = 0
        items = emptyList()
        error = null
        hasSearched = false
    }

    /**
     * 吸收一页服务端结果。第 1 页存原序（锚点识别要用）；第 2 页起对
     * "先到手的数据"（季线 + 前面的页）去重后入缓存。
     */
    fun absorb(result: SearchResult) {
        hasSearched = true
        error = null
        totalCount = result.totalCount.coerceAtLeast(0)
        totalPages = result.totalPages.coerceAtLeast(1)
        hasMore = result.hasMore
        serverFetched = maxOf(serverFetched, result.page)
        if (result.page <= 1) {
            firstPage = result.items
            return
        }
        val seen = mutableSetOf<String>()
        seen.addAll(seasonIds)
        firstPage.forEach { seen.add(it.id) }
        // 只比"先到手且不是本页"的数据：同一页反复吸收结果稳定（S10 口径），
        // 自己上一次的内容不能当去重基准，否则重吸收会把整页过滤空
        restPages.entries
            .filter { it.key != result.page }
            .forEach { (_, rows) -> rows.forEach { seen.add(it.id) } }
        restPages[result.page] = result.items.filter { it.id !in seen }
    }

    /**
     * 完整剧名的锚点：第 1 页里"去季号归一化后等于关键词归一化"的条目。
     * 只认完整剧名（旧仓库口径）：搜「万妖图录传」命中，搜「万妖图录传第三季」不命中。
     *
     * **优先取带季号的那张**（2026-10-24 第二轮）：搜索结果里同时有「万妖图录传第一季」
     * 和一部无季号的同 IP 作品时，拿同 IP 那张当锚点会把季线拉成另一条线；
     * 带季号的卡至少证明它属于某条季线。没有带季号的卡才退回第一张。
     */
    fun anchor(): Series? {
        val key = SeasonKeys.normalizeTitle(keyword)
        if (key.isEmpty()) return null
        val matches = firstPage.filter { SeasonKeys.seasonKey(it.title) == key }
        return matches.firstOrNull { it.season > 0 } ?: matches.firstOrNull()
    }

    /** 接入完整季线（端口给旧→新，这里转成新→旧的展示存储序）。 */
    fun setSeasonLine(line: List<Series>) {
        seasons = line.asReversed()
    }

    /**
     * 目标窗口是否已经完整在手：窗口起点在已知数据内，且窗口终点也在
     * （或服务端已到头——末页允许不满一页）。
     */
    fun hasWindow(target: Int): Boolean {
        val start = (target - 1) * CatalogText.PAGE_SIZE
        if (start >= knownCount) return false
        return target * CatalogText.PAGE_SIZE <= knownCount || !serverHasNext
    }

    /** 还需要拉的服务端页；null = 没有可拉的了（聚合态季线全在内存、到头或字段缺失）。 */
    fun nextServerPage(): Int? {
        if (aggregated || !serverHasNext) return null
        return serverFetched + 1
    }

    /** 把光标页切到 [target] 并按 [ascending] 重排窗口。 */
    fun present(target: Int, ascending: Boolean) {
        page = target.coerceAtLeast(1)
        items = view(ascending)
            .drop((page - 1) * CatalogText.PAGE_SIZE)
            .take(CatalogText.PAGE_SIZE)
    }

    /** 排序开关来回切都从内存视图现算：正序结果不是"另一种原序"。 */
    fun reorder(ascending: Boolean) {
        present(page, ascending)
    }
}
