package com.hongguotv.lite.feature.catalog

import android.view.View
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.focus.FocusSession
import com.hongguotv.lite.uikit.focus.NAV_KEY_PREFIX
import com.hongguotv.lite.uikit.focus.navKey
import com.hongguotv.lite.uikit.page.PageScaffold
import com.hongguotv.lite.uikit.ui.RailNav
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.UiKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 一页的公共骨架：稳定树、左侧栏、焦点会话、页面作用域。
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
    protected lateinit var rail: RailNav

    /** build 期间的目标焦点：先按它落点，而不是先落到首项再跳一次。 */
    private var preferredOnBuild: FocusKey? = null

    /**
     * **本次渲染**要落的焦点（一次性）。用于"状态变了，光标必须跟着走"的场合：
     * 搜索出结果落到第一张卡、联想被清掉回到输入框。
     *
     * 为什么需要它：被聚焦的那个视图被移除时，框架会自己把焦点交给某个视图（通常是侧栏第一项），
     * 而那次变化会被本页焦点记忆记下来——之后"记忆优先"就会把光标钉在那个意外落点上。
     * 显式目标优先于记忆，正好解这个问题。
     */
    protected var preferredOnRender: FocusKey? = null

    fun view(): View = scaffold

    fun build(preferred: FocusKey?) {
        preferredOnBuild = preferred
        // 导航项的标签与图标由 uikit 的 RailSlot 提供，页面只决定"选中谁、点了去哪"
        //（点击 = 导航意图、清目标页记忆，见 [CatalogFeature.showFromRail]）。
        rail = scaffold.showRail(
            RailNav.standardItems { index -> host.showFromRail(index) },
            tab.ordinal,
        )
        onBuild()
        preferredOnBuild = null
        // 这里也要给阶梯：onBuild 里的首帧请求常常在"视图还没挂上窗口"时失败，
        // 真正落定的往往是这一次（或它的 post 重试）——不给阶梯就又落回侧栏了。
        // 没有跨页记忆时把阶梯第一项升格成显式目标：显式目标在回退链里排在记忆前面，
        // 冷启动时框架的自动聚焦（侧栏第一项）就不会压过阶梯。
        requestFocus(preferred ?: startFocus().firstOrNull(), startFocus())
    }

    /** 搭树并装载首屏数据。只调用一次。 */
    protected abstract fun onBuild()

    /**
     * 本页的**首焦阶梯**：进页（或重建）后光标该落在哪，按顺序取第一个存在的。
     *
     * 这是页面必须自己声明的落点：不给的话回退链最后会落到焦点图首项——也就是侧栏「搜索」，
     * 那正是「无论干什么焦点都回到侧栏」的来源。
     * 约定：**第一项是本页内容，侧栏只能出现在链尾**。
     */
    protected abstract fun startFocus(): List<FocusKey>

    /**
     * 本页当前光标，切页时保存、回来时恢复。
     *
     * **侧栏项不算**：光标停在导航上才能切 tab，切走那一刻它多半正停在目标 tab 的导航项上；
     * 把它当成"上一页的光标位置"存下来，回来时就落在别人的导航项上了（S9 实测踩到）。
     * 空页确实停在导航上，但那由 [startFocus] 的阶梯给出，不靠记忆。
     */
    fun currentFocus(): FocusKey? = focus.remembered()?.takeUnless { it.value.startsWith(NAV_KEY_PREFIX) }

    /**
     * MENU 键（B 方案：历史页"管理走 MENU"）。页面没接就返回 false，宿主走默认行为。
     * 只在按键**按下**时由宿主调用（重复的 ACTION_UP 不触发）。
     */
    open fun onMenuKey(): Boolean = false

    /**
     * 目录页的返回键，宿主在路由栈回退**之前**先问。返回 true = 已消费。
     * 默认不接：返回键对目录页保持"栈底退出/回搜索页"的既有行为。
     */
    open fun onBack(): Boolean = false

    /** 子页需要摘对外回调（如更新监听）时覆写；覆写方必须调 super。 */
    open fun release() {
        jobs.cancel()
    }

    protected fun navViews(): Map<FocusKey, View> =
        rail.keys().mapIndexed { index, key -> key to (rail.navItems[index] as View) }.toMap()

    /** 侧栏这一段：四个导航项竖排。作为**侧段**拼进焦点图（左右与内容互通）。 */
    protected fun navSection(): FocusSection? =
        FocusSections.section(rail.keys(), columns = 1)

    /** 提交焦点结构。空的段（没有联想、没有结果、页脚没开）由 [FocusSections] 丢弃。 */
    protected fun applyPlan(
        sections: List<FocusSection?>,
        views: Map<FocusKey, View>,
        sideEntry: FocusKey? = null,
    ) {
        // 内容往左回侧栏时落在**本页的 tab** 上：内容通常只有一行卡，按竖直位置映射
        // 会落到侧栏中段（实测：搜索页按左键落在「收藏」上，再按确认就换了页）。
        // [sideEntry] 是对称的另一半：侧栏按右进内容的落点（机制见 FocusPlan.sideEntry）。
        val plan = FocusSections.plan(
            sections,
            side = navSection(),
            sideAnchor = navKey(tab.ordinal),
            sideEntry = sideEntry,
        ) ?: return
        focus.bind(plan, views)
        val wanted = preferredOnBuild ?: preferredOnRender
        preferredOnRender = null
        requestFocus(wanted, startFocus())
    }

    private fun requestFocus(preferred: FocusKey?, ladder: List<FocusKey> = emptyList()) {
        if (focus.request(preferred, ladder)) return
        // 视图还没挂上窗口时一定失败：等一次 post 再要，别让光标留在页外
        scaffold.post { focus.request(preferred, ladder) }
    }
}
