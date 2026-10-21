package com.hongguotv.lite.uikit.page

import android.annotation.SuppressLint
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 内容区：**一块可滚动的列表 + 一层居中的空态**，两者互斥显示。
 *
 * 为什么把这两件事绑在一起：分页各自实现时，"空态文字放哪"就成了每页各写一遍的内联 padding
 * （S9 之前三页写得都不一样，而且都不居中）。这里把"内容 / 空态"做成一个二态区域，
 * 页面只调用 `showContent()` / `showEmpty(文案)`。
 *
 * 高度由调用方给（一般 `MATCH_PARENT` + `weight = 1`）：它吃掉页头以下的全部空间，
 * 所以空态自然是"页面内容区的中心"，也随窗口大小走。
 */
@SuppressLint("ViewConstructor")
class ContentPane internal constructor(private val ui: UiKit) : FrameLayout(ui.context) {

    /** 滚动内容。页面往这里塞标题、网格、页脚说明。 */
    val content: LinearLayout = ui.column()

    /** 空态层（居中）。它的 [EmptyState.actions] 可以放动作按钮。 */
    val empty: EmptyState = EmptyState(ui)

    private val scroll = ui.scroll(content)

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 光标移出视口时滚到可见处（滚动补偿跟着这块区域走，不归页面管）
        ui.keepFocusVisible(scroll)
    }

    fun showContent() {
        scroll.visibility = View.VISIBLE
        empty.hide()
    }

    fun showEmpty(message: String) {
        // 空态时把滚动区整个收起：留着它只会让"没有内容"这件事看起来像"内容还没加载出来"
        scroll.visibility = View.GONE
        empty.show(message)
    }

    /** 内容区当前的高度（未布局时为 0）。页面用它夹取卡片高度。 */
    val viewportHeight: Int get() = height
}
