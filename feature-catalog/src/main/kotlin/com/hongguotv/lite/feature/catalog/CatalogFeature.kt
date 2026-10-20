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
        page?.release()
        page = null
    }
}

/** 搜索页的状态：一个关键词、一份原始展示序、一份当前展示序。 */
internal class SearchModel {
    var keyword: String = ""

    /** 端口给的展示序（季线新→旧 + 其余原序），排序开关来回切都从它派生。 */
    var raw: List<Series> = emptyList()
    var items: List<Series> = emptyList()
    var seasonLine: List<Series> = emptyList()
    var loading: Boolean = false
    var error: Throwable? = null
    var hasSearched: Boolean = false

    fun clearResult() {
        raw = emptyList()
        items = emptyList()
        seasonLine = emptyList()
        error = null
    }

    /** 只用到结果的两个字段，抽出来是为了让页面代码读起来像业务。 */
    fun apply(result: SearchResult, ascending: Boolean) {
        raw = result.items
        seasonLine = result.seasonLine
        hasSearched = true
        error = null
        reorder(ascending)
    }

    /** 排序开关来回切都从 [raw] 派生：正序结果不是"另一种原序"，不能拿它再排一次。 */
    fun reorder(ascending: Boolean) {
        items = CatalogText.ordered(raw, seasonLine, ascending)
    }
}
