package com.hongguotv.lite.uikit.page

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 分组翻页行：`‹ 上一组 | 第 1—100 集 | 下一组 ›`。分集、季线、播放侧栏都用它，只是单位不同。
 *
 * 不可用的翻组键**保留位置但禁用**：隐藏会让中间的范围文字左右跳动；
 * 禁用的视图本来就拿不到焦点，而调用方只把可用的那个放进焦点图——不会出现"按进去出不来"。
 *
 * 从 `feature-detail` 上提到 uikit 的原因：S6 的播放侧栏是第二个消费者，
 * 两份实现必然在"到底保留还是隐藏禁用键"上分家。
 */
class PagerRow(
    ui: UiKit,
    prevLabel: String = "‹ 上一组",
    nextLabel: String = "下一组 ›",
) {

    private val tokens: Tokens = ui.tokens
    private val rangeView: TextView = ui.text("", tokens.type.subtitle, tokens.colors.muted).apply {
        gravity = Gravity.CENTER
        maxLines = 1
    }

    val prev: TvButton = ui.button(prevLabel) { onPage?.invoke(-1) }
    val next: TvButton = ui.button(nextLabel) { onPage?.invoke(1) }

    /** 翻组回调：-1 = 上一组，1 = 下一组。 */
    var onPage: ((Int) -> Unit)? = null

    val view: LinearLayout = ui.row().apply {
        visibility = View.GONE
        setPadding(0, ui.dp(tokens.space.s), 0, ui.dp(tokens.space.s))
        addView(prev)
        addView(rangeView, ui.params(width = 0, weight = 1f))
        addView(next)
    }

    /** @param hasPrev/hasNext 决定哪个键可用；范围文字由调用方给（集 / 季 的单位不同）。 */
    fun show(rangeText: String, hasPrev: Boolean, hasNext: Boolean) {
        view.visibility = View.VISIBLE
        rangeView.text = rangeText
        enable(prev, hasPrev)
        enable(next, hasNext)
    }

    fun hide() {
        view.visibility = View.GONE
    }

    private fun enable(button: TvButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }
}
