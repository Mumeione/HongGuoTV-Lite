package com.hongguotv.lite.uikit.focus

import android.view.View

/** 视觉方向绑定用的视图矩形（相对页面根，像素）。 */
private data class Placed(val rect: FocusRect, val view: View)

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
     * 按**真实视图位置**绑定四个方向：视觉上的左右就是焦点移动的左右。
     *
     * 与 [bind] 的分工：焦点图仍然负责"程序性落焦"（恢复光标、首焦阶梯）与顺序，
     * 这里只改写物理按键的落点。调用时机必须在布局完成之后（`View.post`），
     * 否则量到的全是 0 尺寸。
     *
     * [exits] 是页面声明的显式出口（例如详情页动作列按左回页头返回）。出口的目标必须在
     * 屏上，否则仍按几何算——不把光标指向一个被收起/移除的视图。
     *
     * 不在 [views] 里、被 `GONE` 收起或已禁用的项不参与：它们本来就不该抢焦点。
     * 某个方向没有候选时显式指向自己——把落点交回系统去猜，就会出现"按一下跳到屏幕另一头"。
     */
    fun bindVisual(root: View, views: Map<FocusKey, View>, exits: FocusExits = FocusExits.NONE) {
        val origin = IntArray(2)
        root.getLocationInWindow(origin)
        val placed = ArrayList<Placed>(views.size)
        views.forEach { (key, view) ->
            if (!view.isShown || !view.isEnabled || view.width <= 0 || view.height <= 0) return@forEach
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val left = location[0] - origin[0]
            val top = location[1] - origin[1]
            placed += Placed(FocusRect(key, left, top, left + view.width, top + view.height), view)
        }
        val rects = placed.map { it.rect }
        placed.forEach { (self, view) ->
            fun idOf(direction: FocusDirection): Int =
                VisualFocus.neighbor(self, rects, direction, exits)
                    ?.let { key -> placed.first { it.rect.key == key }.view.id }
                    ?: view.id
            view.nextFocusLeftId = idOf(FocusDirection.LEFT)
            view.nextFocusRightId = idOf(FocusDirection.RIGHT)
            view.nextFocusUpId = idOf(FocusDirection.UP)
            view.nextFocusDownId = idOf(FocusDirection.DOWN)
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
