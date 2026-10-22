package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.internal.bindFocus
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.shape

/**
 * 设置面板里的一行（B 方案 mockup 画面7 的 `.setrow`）：
 *
 * ```
 * 默认清晰度                                    1080P  ›
 * └ 左：14.5sp 半粗           右：13.5sp muted，值后跟一个小箭头 ┘
 * ```
 *
 * 与 [TvButton] 的 ROW 形态是两件事：那个是"整行一个标签"，值要写进标签里
 * （`默认清晰度  ·  1080P`）——值会随着标签长短左右漂，读起来像一句话而不是一张表。
 * 这里把值单独右对齐，箭头表达"按一下会变"。
 *
 * 焦点仍是整行翻白（B 方案唯一焦点画法）；值在焦点态转成 #5B6674 的深灰，
 * 与白底对比够读，又不和主标签抢。
 */
@SuppressLint("ViewConstructor")
class SettingRow internal constructor(
    private val ui: UiKit,
    label: String,
    value: String,
    private val onTap: () -> Unit,
) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    private val labelView: TextView
    private val valueView: TextView
    private val arrowView: TextView

    /** 焦点变化回调：页面用它撤销"再按一次确认"这类待确认状态。 */
    var onFocused: ((Boolean) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(tokens.metrics.setRowHeight)
        val pad = ui.dp(VALUE_GAP)
        setPadding(pad, 0, pad, 0)

        labelView = TextView(context).apply {
            text = label
            textSize = tokens.type.specValue
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        valueView = TextView(context).apply {
            text = value
            textSize = tokens.type.cardStatus
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
        }
        arrowView = TextView(context).apply {
            text = "›"
            textSize = tokens.type.corner
            includeFontPadding = false
            maxLines = 1
        }

        addView(labelView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(valueView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ui.dp(VALUE_GAP)
        })
        addView(arrowView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ui.dp(ARROW_GAP)
        })

        bindFocus(tokens = tokens, paint = { paintState(it) }, scale = { false }, onChange = { onFocused?.invoke(it) })
        setOnClickListener { onTap() }
    }

    fun label(value: String) {
        labelView.text = value
    }

    fun value(value: String) {
        valueView.text = value
    }

    private fun paintState(focused: Boolean) {
        val colors = tokens.colors
        // 焦点只有"白底填充"一种表达（stateShape 的描边规则：白底不再描边）
        background = shape(if (focused) colors.focus else colors.row, tokens.shapes.row, null)
        labelView.setTextColor(if (focused) colors.onFocus else colors.text)
        // mockup `.setrow.focus .val{color:#5B6674}`：白底上的值要退到 dim 一档才不抢主标签
        valueView.setTextColor(if (focused) colors.dim else colors.muted)
        arrowView.setTextColor(colors.dim)
    }

    private companion object {
        /** 行内左右留白（mockup `.setrow{padding:0 14px}`）。 */
        const val VALUE_GAP = 14
        const val ARROW_GAP = 10
    }
}
