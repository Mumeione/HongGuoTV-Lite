package com.hongguotv.lite.uikit.page

import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import com.hongguotv.lite.uikit.layout.ScrollAlign
import com.hongguotv.lite.uikit.token.Tokens

/**
 * 滚动区里的焦点补偿：光标移出视口时把滚动条挪到能看见它的位置。
 *
 * 只做一件事——监听焦点变化，算（[ScrollAlign]）并执行。旧实现把这段算术内联在
 * 卡片焦点回调里，外加两层 `post` 掩盖时序问题，既不能测也没法复用。
 *
 * 用瞬时滚动而不是平滑滚动：老设备上平滑滚动本身就是掉帧来源，而且用户按方向键时
 * 要的是"立刻看到下一行"，不是"看我滑过去"。
 */
class ScrollFocusKeeper internal constructor(
    private val tokens: Tokens,
    private val scroll: ScrollView,
) : ViewTreeObserver.OnGlobalFocusChangeListener {

    fun attach() {
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener(this)
    }

    fun detach() {
        val observer = scroll.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(this)
    }

    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
        val target = newFocus ?: return
        if (scroll.height <= 0) return
        val content = scroll.getChildAt(0) ?: return
        val top = offsetIn(content, target)
        if (top < 0) return
        val position = ScrollAlign.target(
            itemTop = top,
            itemBottom = top + target.height,
            viewportTop = scroll.scrollY,
            viewportHeight = scroll.height,
            margin = tokens.metrics.scrollMargin,
        ) ?: return
        scroll.scrollTo(0, position)
    }

    /** 目标视图在滚动内容坐标系里的上沿；不是内容的后代时返回 -1。 */
    private fun offsetIn(content: View, target: View): Int {
        var offset = 0
        var view: View? = target
        while (view != null && view !== content) {
            offset += view.top
            view = view.parent as? View
        }
        return if (view === content) offset else -1
    }
}
