package com.hongguotv.lite.feature.catalog

import android.view.View
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import com.hongguotv.lite.uikit.focus.FocusSession
import com.hongguotv.lite.uikit.page.PageScaffold
import com.hongguotv.lite.uikit.page.TopBar
import com.hongguotv.lite.uikit.page.TopBarItem
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.UiKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 一页的公共骨架：稳定树、顶栏、焦点会话、页面作用域。
 *
 * 生命周期：切页时由 [CatalogFeature] 销毁旧页（`release` 取消作用域）并新建新页，
 * 页内刷新一律走 `grid.render()` 的增量路径——整页重建只发生在切页。
 */
internal abstract class CatalogPage(
    protected val host: CatalogFeature,
    val tab: CatalogTab,
) {

    protected val ui: UiKit = host.ui
    protected val tokens: Tokens = ui.tokens
    protected val scaffold: PageScaffold = ui.page()

    /** 页面作用域：切页/离开即取消。在途搜索与收藏检查都挂在这里。 */
    private val jobs = Job()
    protected val scope: CoroutineScope = CoroutineScope(host.scope.coroutineContext + jobs)

    protected val focus: FocusSession = scaffold.focusSession("catalog:${tab.label}")
    protected lateinit var bar: TopBar

    /** build 期间的目标焦点：先按它落点，而不是先落到首项再跳一次。 */
    private var preferredOnBuild: FocusKey? = null

    fun view(): View = scaffold

    fun build(preferred: FocusKey?) {
        preferredOnBuild = preferred
        bar = scaffold.showTopBar(
            BRAND,
            CatalogTab.entries.map { item -> TopBarItem(item.label, item.iconRes) { host.show(item) } },
            tab.ordinal,
        )
        onBuild()
        preferredOnBuild = null
        requestFocus(preferred)
    }

    /** 搭树并装载首屏数据。只调用一次。 */
    protected abstract fun onBuild()

    /** 本页当前光标，切页时保存、回来时恢复。 */
    fun currentFocus(): FocusKey? = focus.remembered()

    fun release() {
        jobs.cancel()
    }

    protected fun navViews(): Map<FocusKey, View> =
        bar.navItems.mapIndexed { index, view -> FocusKey("nav:$index") to (view as View) }.toMap()

    /** 顶栏这一段：四个导航项一行。每页都是第一段，拼一次。 */
    protected fun navSection(): FocusSection? =
        CatalogSections.section(CatalogSections.keys("nav", bar.navItems.size), bar.navItems.size)

    /** 提交焦点结构。空的段（没有联想、没有结果、页脚没开）由 [CatalogSections] 丢弃。 */
    protected fun applyPlan(sections: List<FocusSection?>, views: Map<FocusKey, View>) {
        val plan = CatalogSections.plan(sections) ?: return
        focus.bind(plan, views)
        requestFocus(preferredOnBuild ?: focus.remembered())
    }

    private fun requestFocus(preferred: FocusKey?) {
        if (focus.request(preferred)) return
        // 视图还没挂上窗口时一定失败：等一次 post 再要，别让光标留在页外
        scaffold.post { focus.request(preferred) }
    }

    private companion object {
        const val BRAND = "红果TV"
    }
}
