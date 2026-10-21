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
     * 按「显式目标 → 本页记忆 → 页面阶梯 → 焦点图首项」挑一个**确实存在视图**的 key 并请求焦点。
     *
     * 之所以不直接用 [FocusGraph.resolve] 的结果：resolve 只知道 key 在不在图里，
     * 不知道视图在不在。视图可能因为单页卡片上限被截断而不存在——那时应当落到阶梯里的下一项，
     * 而不是什么都不做（旧实现在这里的表现是"光标凭空消失"）。
     *
     * [ladder] 是页面声明"这一页该落在哪"（见 [FocusStart]）。不给也行，但那样就退回
     * "焦点图首项"——顶栏，正是 S9 要消掉的行为。
     *
     * @return 是否真的把焦点给了某个视图（视图还没挂到窗口上时是 false，调用方用 post 重试）
     */
    fun request(
        graph: FocusGraph,
        views: Map<FocusKey, View>,
        preferred: FocusKey? = null,
        remembered: FocusKey? = null,
        ladder: List<FocusKey> = emptyList(),
    ): Boolean {
        val key = FocusStart.pick(
            preferred = preferred,
            remembered = remembered,
            ladder = ladder,
            available = views.keys,
            order = graph.order,
        ) ?: return false
        return views.getValue(key).requestFocus()
    }

    private fun targetId(views: Map<FocusKey, View>, key: FocusKey, self: View): Int =
        views[key]?.id ?: self.id
}
