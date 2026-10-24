package com.hongguotv.lite.feature.catalog

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hongguotv.core.model.SearchResult
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
 * 搜索页的状态：一个关键词 + **按页缓存的上游结果**。
 *
 * 官网搜索是**服务端分页**（一页 10 条、`totalPages` 可达 20+），所以这里存的是
 * "第 N 页返回了什么"，而不是一整条季线。翻回看过的页直接用缓存，不再打上游。
 *
 * 去重是必要的：上游翻页会重叠（实测第 2 页开头重复第 1 页末尾的季），
 * 同一部剧在一页里出现两次会让人以为搜索坏了。
 */
internal class SearchModel {
    var keyword: String = ""

    /** 当前页（1 起）。 */
    var page: Int = 1
    var totalPages: Int = 1
    var totalCount: Int = 0

    /** 上游还有下一页（`totalPages` 缺失时靠它兜底，不至于"翻页行整条消失"）。 */
    var hasMore: Boolean = false

    /** 端口给的展示序（当前页），排序开关来回切都从它派生。 */
    var raw: List<Series> = emptyList()
    var items: List<Series> = emptyList()
    var seasonLine: List<Series> = emptyList()
    var loading: Boolean = false
    var error: Throwable? = null
    var hasSearched: Boolean = false

    private val pages = LinkedHashMap<Int, SearchResult>()

    val pageCount: Int get() = totalPages.coerceAtLeast(1)

    /** 下一页是否可用：以 `totalPages` 为准，字段缺失时退回 `hasMore`。 */
    val hasNext: Boolean get() = page < pageCount || hasMore

    fun clearResult() {
        page = 1
        totalPages = 1
        totalCount = 0
        hasMore = false
        raw = emptyList()
        items = emptyList()
        seasonLine = emptyList()
        error = null
        pages.clear()
    }

    /** 只用到结果的两个字段，抽出来是为了让页面代码读起来像业务。 */
    fun apply(result: SearchResult, ascending: Boolean) {
        pages[result.page] = result
        page = result.page.coerceAtLeast(1)
        totalPages = result.totalPages.coerceAtLeast(1)
        totalCount = result.totalCount.coerceAtLeast(0)
        hasMore = result.hasMore
        hasSearched = true
        error = null
        seasonLine = result.seasonLine
        raw = withoutRepeats(result)
        reorder(ascending)
    }

    /** 已经拿过的页：翻回去不再请求。 */
    fun cached(target: Int): SearchResult? = pages[target]

    /** 排序开关来回切都从 [raw] 派生：正序结果不是"另一种原序"，不能拿它再排一次。 */
    fun reorder(ascending: Boolean) {
        items = CatalogText.ordered(raw, seasonLine, ascending)
    }

    /**
     * 去掉与**前面几页**重复的条目。只比前面的页（不比后面的），
     * 所以同一页反复应用的结果稳定，翻回上一页也不会被自己过滤空。
     */
    private fun withoutRepeats(result: SearchResult): List<Series> {
        if (result.page <= 1) return result.items
        val earlier = pages.filterKeys { it < result.page }.values.flatMap { it.items }
        if (earlier.isEmpty()) return result.items
        val seen = earlier.mapTo(HashSet(earlier.size)) { it.id }
        return result.items.filter { it.id !in seen }
    }
}
