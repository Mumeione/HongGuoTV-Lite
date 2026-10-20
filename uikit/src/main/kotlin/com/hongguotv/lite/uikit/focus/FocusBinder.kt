package com.hongguotv.lite.uikit.focus

import android.view.View

/**
 * 把焦点图落到真实 View 上。
 *
 * 这是"纯函数 → 真机手感"之间唯一的桥：焦点关系由 [FocusGraph] 算出，
 * 这里只负责把 key 翻译成 `nextFocus*Id`，并处理"目标 key 没有对应视图"的情形——
 * 一律夹到自己身上，**不交给系统去猜**（旧实现里"按了跳到奇怪的地方"就是系统猜出来的）。
 *
 * 只有这一个地方写 `nextFocus*Id`：新页面加一段焦点只需要声明 [FocusSection]。
 */
object FocusBinder {

    fun bind(graph: FocusGraph, views: Map<FocusKey, View>) {
        views.forEach { (key, view) ->
            val neighbors = graph.neighborsOf(key) ?: return@forEach
            view.nextFocusLeftId = targetId(views, neighbors.left, view)
            view.nextFocusRightId = targetId(views, neighbors.right, view)
            view.nextFocusUpId = targetId(views, neighbors.up, view)
            view.nextFocusDownId = targetId(views, neighbors.down, view)
        }
    }

    /**
     * 按"显式目标 → 本页记忆 → 该页第一个焦点项"的顺序挑一个**确实存在视图**的 key 并请求焦点。
     *
     * 之所以不直接用 [FocusGraph.resolve] 的结果：resolve 只知道 key 在不在图里，
     * 不知道视图在不在。视图可能因为单页卡片上限被截断而不存在——那时应当落到首项，
     * 而不是什么都不做（旧实现在这里的表现是"光标凭空消失"）。
     *
     * @return 是否真的把焦点给了某个视图（视图还没挂到窗口上时是 false，调用方用 post 重试）
     */
    fun request(
        graph: FocusGraph,
        views: Map<FocusKey, View>,
        preferred: FocusKey? = null,
        remembered: FocusKey? = null,
    ): Boolean {
        val candidates = listOfNotNull(preferred, remembered) + graph.order
        val view = candidates.firstNotNullOfOrNull { views[it] } ?: return false
        return view.requestFocus()
    }

    private fun targetId(views: Map<FocusKey, View>, key: FocusKey, self: View): Int =
        views[key]?.id ?: self.id
}
