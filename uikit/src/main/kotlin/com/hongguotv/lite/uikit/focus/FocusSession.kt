package com.hongguotv.lite.uikit.focus

import android.view.View

/**
 * 一页的焦点会话：把"声明焦点结构、绑定邻居、记住光标、恢复光标"这四件事串起来，
 * 让 feature 不用每页重写一遍（旧实现的 44 处 `nextFocus*` 就是每页重写的结果）。
 *
 * 用法：
 * 1. 页面拿到 [FocusSession]，用 [bind] 提交焦点结构（每次重建视图后重新提交）；
 * 2. 页面根视图的全局焦点变化回调里调 [onFocusChanged]（[com.hongguotv.lite.uikit.page.PageScaffold]
 *    已经接好了，feature 不需要自己接）；
 * 3. 需要把光标放回本页时调 [request]。
 *
 * 记忆按 [page] 隔离：目录页的"收藏"和"历史"互不影响。
 */
class FocusSession(
    private val page: String,
    private val memory: FocusMemory = FocusMemory(),
) {

    private var graph: FocusGraph? = null
    private var views: Map<FocusKey, View> = emptyMap()
    private var keyById: Map<Int, FocusKey> = emptyMap()
    private var lastFocused: FocusKey? = null

    /** 本页当前的焦点结构。还没 [bind] 时为 null。 */
    val graphOrNull: FocusGraph? get() = graph

    /**
     * 提交焦点结构并绑定邻居关系。返回图本身，便于调用方查 key 是否存在。
     * @param views key → 视图；只有这里出现的 key 才能被真正聚焦。
     */
    fun bind(plan: FocusPlan, views: Map<FocusKey, View>): FocusGraph {
        val built = FocusGraph.of(plan)
        FocusBinder.bind(built, views)
        graph = built
        this.views = views
        keyById = views.entries.associate { (key, view) -> view.id to key }
        return built
    }

    /**
     * 视图树里任意焦点变化都记到本页（由页面的全局焦点监听调用）。
     *
     * **被动落焦不记**：旧焦点视图已经不可见（被移除、或整块被 `GONE` 收起）时，
     * 框架会自己把焦点交给页面里第一个可聚焦项——通常是侧栏。那次变化不是用户选的，
     * 记下来就会变成「任何操作之后光标都停在侧栏」：数据刷新把卡片重建一遍、空态把列表收起来，
     * 记忆就指向侧栏了。
     *
     * **冷启动的自动聚焦也不记**：窗口挂上时框架会把焦点给树的第一个可聚焦项（侧栏第一项），
     * 那时 [oldFocus] 为 null，上面的判据拦不住——而这次"记忆"会**压过页面的落焦阶梯**
     * （记忆在回退链里排在阶梯前面）。侧栏 key 本来就不该进记忆（页面保存光标时会过滤
     * [NAV_KEY_PREFIX] 开头的项），这里在写入侧再多拦一道。
     *
     * @param oldFocus 变化前的焦点视图。不可见（`isShown == false`）或已脱离窗口时不记。
     */
    fun onFocusChanged(oldFocus: View?, newFocus: View?) {
        val key = newFocus?.let { keyById[it.id] } ?: return
        if (oldFocus != null && (!oldFocus.isShown || oldFocus.windowToken == null)) return
        if (oldFocus == null && key.value.startsWith(NAV_KEY_PREFIX)) return
        lastFocused = key
        memory.remember(page, key)
    }

    /** 本页上次的焦点 key。 */
    fun remembered(): FocusKey? = memory.recall(page)

    /**
     * 把光标放回本页：显式目标 → 本页记忆 → 页面阶梯（[ladder]）→ 焦点图首项。
     *
     * [ladder] 由页面声明（例如"第一张结果卡 → 搜索框"），见 [FocusStart]。
     * @return 是否成功（视图还没挂上窗口时会失败，调用方 `post` 一次即可）
     */
    fun request(preferred: FocusKey? = null, ladder: List<FocusKey> = emptyList()): Boolean {
        val currentGraph = graph ?: return false
        return FocusBinder.request(
            graph = currentGraph,
            views = views,
            preferred = preferred,
            remembered = remembered(),
            ladder = ladder,
        )
    }
}
