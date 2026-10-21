package com.hongguotv.lite.uikit.internal

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.hongguotv.lite.uikit.token.Tokens

/** dp → 像素的唯一换算点。控件的其它地方不再自己乘密度。 */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** 控件里写 `dp(12)` 而不是 `context.dp(12)`：View 不是 Context，需要这层转发。 */
internal fun View.dp(value: Int): Int = context.dp(value)

/** 描边规格。用类型表达"没有描边"，不用透明色当哨兵。 */
internal data class Stroke(val color: Int, val widthDp: Int)

/** 圆角矩形。 */
internal fun Context.shape(fill: Int, radiusDp: Int, stroke: Stroke?): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(stroke.widthDp).coerceAtLeast(1), stroke.color)
    }

internal fun View.shape(fill: Int, radiusDp: Int, stroke: Stroke? = null): GradientDrawable =
    context.shape(fill, radiusDp, stroke)

/**
 * 选中 / 焦点的**描边规则**：全应用只有这一份。
 *
 * - **焦点不描边**：焦点项的填充本身就是白色（`colors.focus`），再描一圈白边是浪费是其次，
 *   主要问题是"白底 + 白边"看起来像两个状态叠在一起（S9 之前就是这样）；
 * - **选中描白边**：当前 tab / 当前筛选 / 当前集 / 当前季只有框、没有底色，
 *   这样一页里只会有一块真正的白色填充——光标所在的那块。
 *
 * 底色由各控件自己给（按钮有"已看过/主操作"两档，导航项未选中时透明）。
 * 封面卡是唯一例外，它走 [cardShape]：卡面是图，白底会把图盖掉。
 */
internal fun View.stateShape(tokens: Tokens, fill: Int, radiusDp: Int, selected: Boolean, focused: Boolean): GradientDrawable {
    val stroke = if (selected && !focused) Stroke(tokens.colors.focus, tokens.shapes.stroke) else null
    return shape(fill, radiusDp, stroke)
}

/**
 * 封面卡的边框：**焦点用白描边**，因为卡面是封面图，把整张卡刷白等于把图盖掉。
 *
 * 这是"焦点 = 白底"的唯一例外，所以单独成一个函数而不是在 [stateShape] 里加分支——
 * 加分支会让"要不要白底"变成一个隐式参数，下一个人不知道该传什么。
 */
internal fun View.cardShape(tokens: Tokens, fill: Int, radiusDp: Int, focused: Boolean): GradientDrawable {
    val stroke = if (focused) Stroke(tokens.colors.focus, tokens.shapes.stroke) else null
    return shape(fill, radiusDp, stroke)
}

/** 给矢量图着色。setTint 是 API 21 的接口，API 23 基线内可用。 */
internal fun Drawable.tinted(color: Int): Drawable = apply { setTint(color) }

/** 只在文本真的变了才重设：避免每帧无谓的布局失效。 */
internal fun TextView.setTextIfChanged(value: String) {
    if (text?.toString() != value) text = value
}

/**
 * 遥控器控件的共同底板：**焦点监听归控件所有**，调用方只拿 [onFocused] 回调。
 *
 * 旧实现里每个控件各写一遍 `setOnFocusChangeListener` 和画法，改一次视觉要改五处；
 * 这里把"可聚焦、有 id、聚焦时重画、低内存档不做动画"一次定好，子类只描述外观。
 */
abstract class FocusControl(
    context: Context,
    protected val tokens: Tokens,
) : TextView(context) {

    /** 焦点变化回调。控件自己已经画完了，调用方只做副作用（例如更新预览区）。 */
    var onFocused: ((Boolean) -> Unit)? = null

    init {
        includeFontPadding = false
        bindFocus(tokens, paint = { paintState(it) }, onChange = { onFocused?.invoke(it) })
        // 不在这里画首帧：子类的状态属性（选中、图标）在自己的构造函数里才就位，
        // 子类 init 末尾调用 repaint() 完成首帧。
    }

    /** 按当前聚焦态重画。选中态等可变外观由子类的属性触发。 */
    protected abstract fun paintState(focused: Boolean)

    /** 外部状态变化后重画（例如选中态切换）。 */
    fun repaint() {
        paintState(isFocused)
    }
}

/**
 * 非文字类控件（卡片）共用同一套焦点行为：生成 id、可聚焦、聚焦时重画、低内存档不做动画。
 */
internal fun View.bindFocus(
    tokens: Tokens,
    paint: (focused: Boolean) -> Unit,
    onChange: ((focused: Boolean) -> Unit)? = null,
) {
    id = View.generateViewId()
    isFocusable = true
    isFocusableInTouchMode = true
    setOnFocusChangeListener { view, focused ->
        paint(focused)
        view.animateFocus(tokens, focused)
        onChange?.invoke(focused)
    }
    paint(false)
}

/** 动效只改绘制，不参与几何：焦点图与滚动补偿一律用未缩放的边界。 */
internal fun View.animateFocus(tokens: Tokens, focused: Boolean) {
    if (!tokens.motion.enabled) return
    val scale = if (focused) tokens.motion.focusScale else 1f
    animate().scaleX(scale).scaleY(scale).setDuration(tokens.motion.durationMs).start()
}
