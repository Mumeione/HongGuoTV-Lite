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

    /** 视图树里任意焦点变化都记到本页（由页面的全局焦点监听调用）。 */
    fun onFocusChanged(view: View?) {
        val key = view?.let { keyById[it.id] } ?: return
        lastFocused = key
        memory.remember(page, key)
    }

    /** 本页上次的焦点 key。 */
    fun remembered(): FocusKey? = memory.recall(page)

    /**
     * 把光标放回本页：显式目标 → 本页记忆 → 页面首项。
     * @return 是否成功（视图还没挂上窗口时会失败，调用方 `post` 一次即可）
     */
    fun request(preferred: FocusKey? = null): Boolean {
        val currentGraph = graph ?: return false
        return FocusBinder.request(
            graph = currentGraph,
            views = views,
            preferred = preferred,
            remembered = remembered(),
        )
    }
}
