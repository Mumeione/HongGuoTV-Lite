package com.hongguotv.lite.uikit.page

import android.annotation.SuppressLint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.internal.setTextIfChanged
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 空态：一句话 + 一排动作，**在内容区正中**。
 *
 * S9 之前每个页面都是「往滚动内容里塞一个带 24dp 上边距的 TextView」，于是提示被上方的
 * 固定留白（联想行、预览区）推到屏幕中下部，上面留一大块黑。空态的语义是"这里现在没有东西"，
 * 那它就该在中间。
 *
 * 文字与按钮都由页面给：uikit 不知道"检查更新"是什么。
 */
@SuppressLint("ViewConstructor")
class EmptyState internal constructor(private val ui: UiKit) : FrameLayout(ui.context) {

    private val tokens = ui.tokens
    private val messageView: TextView
    private val block: LinearLayout

    /** 动作按钮的容器（重试这类）。没有动作时它高度为 0，不影响居中。 */
    val actions: LinearLayout

    init {
        visibility = View.GONE
        block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        messageView = TextView(context).apply {
            textSize = tokens.type.subtitle
            setTextColor(tokens.colors.muted)
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(tokens.space.l), 0, 0)
        }
        block.addView(messageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        block.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            block,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    fun show(message: String) {
        messageView.setTextIfChanged(message)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }
}
