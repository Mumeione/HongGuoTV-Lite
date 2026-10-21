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
 * 封面卡也是"焦点白底"：封面图在卡片上层，白底只露在封面下面那条信息区，
 * 视觉上就是"封面下层的背景变白、文字变黑"（见 `PosterCard.paintCard`）。
 */
internal fun View.stateShape(tokens: Tokens, fill: Int, radiusDp: Int, selected: Boolean, focused: Boolean): GradientDrawable {
    val stroke = if (selected && !focused) Stroke(tokens.colors.focus, tokens.shapes.stroke) else null
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

    /**
     * 聚焦时是否放大。
     *
     * 密集格子（分集格一行 20 个）必须关掉：格间距只有几 dp，1.03 倍就压到邻居身上，
     * 白框的圆角被后绘制的邻居吃掉一条边——看起来就成了"长方形"（维护者实测）。
     */
    protected open val scalesOnFocus: Boolean get() = true

    init {
        includeFontPadding = false
        bindFocus(
            tokens = tokens,
            paint = { paintState(it) },
            scale = { scalesOnFocus },
            onChange = { onFocused?.invoke(it) },
        )
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
    scale: () -> Boolean = { true },
    onChange: ((focused: Boolean) -> Unit)? = null,
) {
    id = View.generateViewId()
    isFocusable = true
    isFocusableInTouchMode = true
    setOnFocusChangeListener { view, focused ->
        paint(focused)
        view.animateFocus(tokens, focused, scale())
        view.raiseOnFocus(focused)
        onChange?.invoke(focused)
    }
    paint(false)
}

/** 动效只改绘制，不参与几何：焦点图与滚动补偿一律用未缩放的边界。 */
internal fun View.animateFocus(tokens: Tokens, focused: Boolean, scale: Boolean = true) {
    if (!tokens.motion.enabled) return
    val target = if (focused && scale) tokens.motion.focusScale else 1f
    animate().scaleX(target).scaleY(target).setDuration(tokens.motion.durationMs).start()
}

/**
 * 聚焦项画在兄弟之上。
 *
 * 焦点缩放会让相邻控件**几何重叠**，而 Android 按添加顺序绘制子视图：后加的邻居
 * 会把先加的焦点项盖掉一条边（分集格最明显）。`translationZ` 让这一项参与 Z 排序
 * （API 21+），白框与圆角才能完整。
 *
 * 同时清掉 outline：电视界面不需要投影，"浮起"只表达层级，不表达光。
 */
internal fun View.raiseOnFocus(focused: Boolean) {
    outlineProvider = null
    translationZ = if (focused) context.dp(4).toFloat() else 0f
}
