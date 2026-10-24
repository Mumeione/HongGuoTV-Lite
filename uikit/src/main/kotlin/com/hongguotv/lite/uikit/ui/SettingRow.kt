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

/** 设置行的两种场合：设置页的面板行，或播放抽屉里的紧凑行。 */
enum class SettingRowStyle {
    /** 设置页：46dp 高、14.5sp 半粗标签 + 13.5sp muted 值。 */
    PANEL,

    /**
     * 播放抽屉：34dp 高、名称与值分列。
     * 2026-10-24 验收：`倍速 · 1×` 写在一行里，名称与当前值分不出来——
     * 名称退一档（soft、常规）、值亮一档（text、粗），一眼能看出"这一行现在是什么档位"。
     */
    DRAWER,
}

/**
 * 设置面板里的一行（B 方案 mockup 画面7 的 `.setrow`）：
 *
 * ```
 * 默认清晰度                                    1080P
 * └ 左：14.5sp 半粗           右：13.5sp muted，只显示当前档位 ┘
 * ```
 *
 * 与 [TvButton] 的 ROW 形态是两件事：那个是"整行一个标签"，值要写进标签里
 * （`默认清晰度  ·  1080P`）——值会随着标签长短左右漂，读起来像一句话而不是一张表。
 * 这里把值单独右对齐。
 *
 * **不带右箭头**（2026-10-24 口径）：箭头读作"点开有下一级"，而我们的操作逻辑是
 * "点一下循环切到下一档"——只显示当前档位，用户才会凭直觉知道是点击切换。
 *
 * 焦点仍是整行翻白（B 方案唯一焦点画法）；值在焦点态转成 #5B6674 的深灰，
 * 与白底对比够读，又不和主标签抢。
 */
@SuppressLint("ViewConstructor")
class SettingRow internal constructor(
    private val ui: UiKit,
    label: String,
    value: String,
    private val style: SettingRowStyle = SettingRowStyle.PANEL,
    private val onTap: () -> Unit,
) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    private val labelView: TextView
    private val valueView: TextView

    /** 焦点变化回调：页面用它撤销"再按一次确认"这类待确认状态。 */
    var onFocused: ((Boolean) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val drawer = style == SettingRowStyle.DRAWER
        minimumHeight = if (drawer) ui.dp(tokens.metrics.drawerRowHeight) else ui.dp(tokens.metrics.setRowHeight)
        // 行内左右留白（mockup `.setrow{padding:0 14px}`；抽屉更紧一档）
        val pad = ui.dp(if (drawer) tokens.space.m else tokens.space.l)
        setPadding(pad, 0, pad, 0)

        labelView = TextView(context).apply {
            text = label
            textSize = if (drawer) tokens.type.cardStatus else tokens.type.specValue
            setTypeface(null, if (drawer) Typeface.NORMAL else Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        valueView = TextView(context).apply {
            text = value
            // 抽屉里"值"是这一行唯一要读的东西，比名称大一档、加粗
            textSize = if (drawer) tokens.type.specValue else tokens.type.cardStatus
            setTypeface(null, if (drawer) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
        }

        addView(labelView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(valueView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ui.dp(tokens.space.m)
        })

        bindFocus(paint = { paintState(it) }, onChange = { onFocused?.invoke(it) })
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
        val drawer = style == SettingRowStyle.DRAWER
        // 焦点只有"白底填充"一种表达（stateShape 的描边规则：白底不再描边）
        background = shape(
            fill = if (focused) colors.focus else colors.row,
            radiusDp = if (drawer) tokens.shapes.small else tokens.shapes.row,
            stroke = null,
        )
        if (drawer) {
            // 名称退、值进：抽屉打开时用户扫的是"现在是什么档"，不是"这行叫什么"
            labelView.setTextColor(if (focused) colors.dim else colors.soft)
            valueView.setTextColor(if (focused) colors.onFocus else colors.focus)
        } else {
            labelView.setTextColor(if (focused) colors.onFocus else colors.text)
            // mockup `.setrow.focus .val{color:#5B6674}`：白底上的值要退到 dim 一档才不抢主标签
            valueView.setTextColor(if (focused) colors.dim else colors.muted)
        }
    }
}
