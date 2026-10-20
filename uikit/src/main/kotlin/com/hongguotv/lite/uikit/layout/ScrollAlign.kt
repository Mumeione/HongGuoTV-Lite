package com.hongguotv.lite.uikit.layout

/**
 * 滚动补偿：算出"要不要把滚动条挪一下、挪到哪"。纯算术，执行由调用方负责。
 *
 * 旧实现把这段算术写死在焦点回调里，外面套两层 `post` 做时序补偿——
 * 焦点错位被"掩盖"而不是被修好，所以既不能测也不能复用。
 */
object ScrollAlign {

    /**
     * @param itemTop 目标项在滚动内容坐标系里的上沿
     * @param itemBottom 目标项的下沿
     * @param viewportTop 当前滚动位置（= 视口上沿在内容坐标系的位置）
     * @param viewportHeight 视口高度
     * @param margin 项与视口边缘之间保留的余量
     * @return 需要滚动到的 y；已经在视口内返回 `null`（不要无谓地打断用户的视线）
     */
    fun target(itemTop: Int, itemBottom: Int, viewportTop: Int, viewportHeight: Int, margin: Int = 0): Int? {
        if (viewportHeight <= 0) return null
        val safeMargin = margin.coerceAtLeast(0)
        val top = itemTop - safeMargin
        val bottom = itemBottom + safeMargin

        return when {
            top < viewportTop -> top.coerceAtLeast(0)
            bottom > viewportTop + viewportHeight -> (bottom - viewportHeight).coerceAtLeast(0)
            else -> null
        }
    }
}
