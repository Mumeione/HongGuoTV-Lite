package com.hongguotv.lite.feature.detail

import android.view.View
import com.hongguotv.lite.uikit.focus.FocusExits
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.focus.FocusSession
import com.hongguotv.lite.uikit.focus.navKey
import com.hongguotv.lite.uikit.page.PageScaffold
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.RailNav
import com.hongguotv.lite.uikit.ui.RailSlot
import com.hongguotv.lite.uikit.ui.UiKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 详情两页共用的动作列出口。
 *
 * 动作列左边是封面与简介（不可聚焦），按矩形的最近项是侧栏——不声明出口就会"按左跑出这一页"。
 *
 * 页头只剩「检查更新」一颗：主操作按上/左回到它，
 * 因为用户就是从那里按下来的，一来一回是同一条路。下面两颗（选集/收藏）的左边是封面，
 * 没有可去的地方，夹住自己——不让它们顺着几何越过检查键跳去侧栏。
 */
internal fun detailActionExits(
    play: FocusKey,
    pick: FocusKey,
    favorite: FocusKey,
    check: FocusKey?,
): FocusExits {
    val intoHeader = if (check != null) {
        FocusExits.left(check, listOf(play)) + FocusExits.up(check, listOf(play))
    } else {
        // 没有检查键（未收藏 / 多季剧在选季页检查）：主操作上面就是页头空白，夹住自己
        FocusExits.clampLeft(listOf(play))
    }
    return intoHeader + FocusExits.clampLeft(listOf(pick, favorite))
}

/**
 * 页头「检查更新」的方向键出口：它只当**上面那一排**的落点，自己不往上方/左侧跑。
 *
 * - 按下 / 按右 → 主操作：检查完最可能接着看；它下面是片名与简介（不可聚焦），
 *   交给几何会跨段落到「选集」上（维护者实测的旧行为）；
 * - 上 / 左 → 夹住自己：页头之上、左侧页边距都没有可去的地方。
 *
 * 进入它的两条路在页面里声明：主操作的上/左（[detailActionExits]）、选季页排序键的上（[sortExits]）。
 */
internal fun detailCheckExits(check: FocusKey, play: FocusKey): FocusExits =
    FocusExits.down(play, listOf(check)) +
        FocusExits.right(play, listOf(check)) +
        FocusExits.up(check, listOf(check)) +
        FocusExits.clampLeft(listOf(check))

/**
 * 选季页「正序/倒序」的方向键：左右都夹住自己，按上回页头的「检查更新」。
 *
 * 它上方隔着弹性留白与 hero，按几何的最近项会跳到右侧动作列——用户按上的意图是"回页头"。
 * 按右不接动作列：从下面这一行跨到最右列是一整行的跳跃，而按完排序用户接下来看的是季卡，
 * 不是最上面那一列按钮。没有检查键时不给上出口，仍由焦点图跨段到动作列。
 */
internal fun sortExits(sort: FocusKey, check: FocusKey?): FocusExits {
    val clamp = FocusExits.left(sort, listOf(sort)) + FocusExits.right(sort, listOf(sort))
    return if (check == null) clamp else clamp + FocusExits.up(check, listOf(sort))
}

/**
 * 网格行缘出口：**没有长条的一侧由页面夹住自己**。
 *
 * 视觉绑定按真实矩形算落点，单页网格（无翻组键）的行首按左会撞到侧栏的「设置」、
 * 行尾按右会撞到动作列——两者都不是用户按方向键的意图。长条在屏上时交给几何正常进条；
 * 不在的那一侧显式指向自身，把"边缘"变成真正的边缘。
 */
internal fun gridEdgeExits(
    keys: List<FocusKey>,
    columns: Int,
    leftFlank: FocusSection?,
    rightFlank: FocusSection?,
): FocusExits {
    if (keys.isEmpty() || columns <= 0) return FocusExits.NONE
    var exits = FocusExits.NONE
    if (leftFlank == null) {
        exits += FocusExits.clampLeft(keys.filterIndexed { index, _ -> index % columns == 0 })
    }
    if (rightFlank == null) {
        exits += FocusExits.clampRight(
            keys.filterIndexed { index, _ -> (index + 1) % columns == 0 || index == keys.lastIndex },
        )
    }
    return exits
}

/**
 * 这一次提交焦点结构，该不该动光标。
 *
 * 详情两页都会因为数据到位而重画（季线到手、分类补全），每次重画都重提一遍焦点结构，
 * 但**用户此刻可能已经把光标移到了别处**——这时再 `requestFocus` 就是"后台刷新把光标抢回去"
 * （实测：收藏详情页里把光标移到第 3 张季卡，分类补全的那一帧过后光标被打回「开始播放」）。
 *
 * 两条放行条件，其余一律不动：
 * - [explicit]：这一次渲染显式要落点（首次进页、点收藏、翻组）——用户的动作，不是刷新；
 * - 本页没有视图持焦（加载中的空窗期、刚换完视图树）——不兜一下遥控器就全灭。
 *
 * 抽成纯函数是为了能在 JVM 单测里钉死这四条分支：`View.isFocused` 只有真机/模拟器上才有。
 */
internal fun shouldRequest(pageHasFocus: Boolean, explicit: Boolean): Boolean = explicit || !pageHasFocus

/**
 * 一页的公共骨架：稳定树、左侧栏、页头、焦点会话、页面作用域。
 *
 * 生命周期：[DetailFeature] 换目标时销毁旧页（`release` 取消作用域）并新建新页；
 * 页内刷新只改文字与卡片内容，整棵树只在换页时重建。
 *
 * B 方案：详情两页也有左侧栏（mockup 画面 3/5），当前项固定亮「收藏」——
 * 这两页的内容（继续播放、季线）都是收藏语境的延伸。
 */
internal abstract class DetailPage(
    protected val host: DetailFeature,
    /** 页面标识（`"detail:<id>"` / `"series:<key>"`）：焦点记忆的隔离键。 */
    protected val pageKey: String,
) {

    protected val ui: UiKit = host.ui
    protected val tokens: Tokens = ui.tokens
    protected val scaffold: PageScaffold = ui.page()

    /** 页面作用域：换页/离开即取消。在途详情与季线请求都挂在这里。 */
    private val jobs = Job()
    protected val scope: CoroutineScope = CoroutineScope(host.scope.coroutineContext + jobs)

    protected val focus: FocusSession = scaffold.focusSession(pageKey)
    private lateinit var rail: RailNav

    fun view(): View = scaffold

    /** 搭树并装载首屏数据。只调用一次；各页在数据到手后自行提交焦点结构。 */
    fun build() {
        rail = scaffold.showRail(RailNav.standardItems { host.navigator.toCatalog(it) }, RAIL_SELECTED)
        onBuild()
    }

    /** 页面自己的树与首屏装载。侧栏已由 [build] 建好。 */
    protected abstract fun onBuild()

    fun release() {
        // 先把光标交给 feature 再取消作用域：页面重建（返回再进来、切组再回来）要按它落点
        focus.remembered()?.let { host.lastFocus[pageKey] = it }
        jobs.cancel()
    }

    /**
     * 提交焦点结构。空的段（失败态的重试、单页时的分页行）整段丢弃；
     * 内容段全空时**绑侧栏单独成图**：加载中的空窗期旧实现直接 return，
     * 焦点图停留在旧结构（换目标后即作废），遥控器整页失灵。
     * 侧栏在 [build] 里已建好，永远可绑；落焦由 [requestFocus] 兜到侧栏当前项。
     *
     * [preferred] 为 null 时用**本页上次的光标**（`host.lastFocus`）——页面换目标销毁重建后，
     * 这是"回来还在原处"的唯一来源。
     *
     * [ladder] 是页面的落焦阶梯（例如"主操作"）：加载中的二次刷新没有显式目标、也没有记忆，
     * 没有阶梯就会回退到焦点图首项——那是侧栏「搜索」，光标被扔到屏幕左缘（维护者实测过顶栏版）。
     *
     * [sideEntry] 是侧栏按右的显式落点：默认映射会进第一段，而详情页的第一段是页头的
     * 「检查更新」——侧栏按右的意图是"进内容干活"，不是去看一颗低频键。
     *
     * [visualExits] 是视觉方向键的显式出口（动作列按左回页头的检查键）：视觉绑定的几何落点
     * 不适合"左边只有不可聚焦的封面"这种布局，由页面指名。
     */
    protected fun submitPlan(
        sections: List<FocusSection?>,
        views: Map<FocusKey, View>,
        preferred: FocusKey? = null,
        ladder: List<FocusKey> = emptyList(),
        leftFlank: FocusSection? = null,
        rightFlank: FocusSection? = null,
        sideEntry: FocusKey? = null,
        visualExits: FocusExits = FocusExits.NONE,
    ) {
        val side = FocusSections.section(rail.keys(), columns = 1)
        // 空态兜底：内容段全空时把侧栏当内容段单独成图，遥控器至少能操作导航
        val plan = FocusSections.plan(
            sections,
            side = side,
            // 详情两页的当前项固定亮「收藏」：内容往左要回到它，而不是按竖直位置落到别的 tab
            sideAnchor = navKey(RAIL_SELECTED),
            leftFlank = leftFlank,
            rightFlank = rightFlank,
            sideEntry = sideEntry,
        ) ?: FocusSections.plan(listOfNotNull(side)) ?: return
        // 侧栏也要进 views：只声明段、不给 View，方向键的落点表里就没有它，
        // 视觉上在左侧的导航项永远选不中（与长条是同一个坑）
        val allViews = LinkedHashMap(views)
        rail.navItems.forEachIndexed { index, item -> allViews[navKey(index)] = item }
        // 详情两页按视觉位置走方向键：检查更新在页头左上、继续播放才在它右下
        focus.bind(plan, allViews, visual = true, visualExits = visualExits)
        // 刷新不抢焦：显式给了目标（首次进页 / 点收藏 / 翻组）才动光标；否则只要页面里
        // 已经有人持焦就什么都不做——异步刷新（季线到手、分类补全）不能把光标从用户手里抢走。
        // 跨页记忆 `host.lastFocus` 只在"页面里没人持焦"时参与回退，排在页面阶梯之后：
        // 它是上一次进来的位置，比本页记忆（只记用户主动移动）更旧。
        if (shouldRequest(pageHasFocus = focus.hasFocusInPage(), explicit = preferred != null)) {
            if (preferred != null) {
                requestFocus(preferred, ladder)
            } else {
                requestFocus(null, ladder + listOfNotNull(host.lastFocus[pageKey]), onlyIfIdle = true)
            }
        }
    }

    /**
     * 落焦带三级兜底：首次失败（视图还没挂窗口）→ `post` 再要一次；
     * 还失败 → 等一次布局完成再要；仍失败 → 直接把光标点名到侧栏当前项（收藏）。
     * 侧栏项总是存在且已挂窗口，这一级兜底保证页面永远不会"焦点在页外、遥控器全灭"。
     *
     * [onlyIfIdle] = 刷新型：页面里已经有人持焦就整段跳过（连重试也不做），
     * 每级兜底前都重新判一次，避免"布局回调晚到、把已经移走的光标又拽回来"。
     */
    private fun requestFocus(preferred: FocusKey?, ladder: List<FocusKey>, onlyIfIdle: Boolean = false) {
        // 阶梯末尾固定接侧栏当前项：内容 key 还不存在（加载中）时光标也有明确的归处
        val fullLadder = ladder + navKey(RAIL_SELECTED)
        if (onlyIfIdle && focus.hasFocusInPage()) return
        if (focus.request(preferred, fullLadder)) return
        scaffold.post {
            if (onlyIfIdle && focus.hasFocusInPage()) return@post
            if (focus.request(preferred, fullLadder)) return@post
            val observer = scaffold.viewTreeObserver
            if (!observer.isAlive) {
                focusFallback(preferred, fullLadder, onlyIfIdle)
                return@post
            }
            observer.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val live = scaffold.viewTreeObserver
                    if (live.isAlive) live.removeOnGlobalLayoutListener(this)
                    focusFallback(preferred, fullLadder, onlyIfIdle)
                }
            })
        }
    }

    private fun focusFallback(preferred: FocusKey?, ladder: List<FocusKey>, onlyIfIdle: Boolean) {
        if (onlyIfIdle && focus.hasFocusInPage()) return
        if (focus.request(preferred, ladder)) return
        rail.navItems.getOrNull(RAIL_SELECTED)?.requestFocus()
    }

    private companion object {
        /** 详情两页的侧栏当前项固定亮「收藏」（mockup 画面 3/5）——按名字取，不写死下标。 */
        val RAIL_SELECTED = RailSlot.FAVORITES.ordinal
    }
}
