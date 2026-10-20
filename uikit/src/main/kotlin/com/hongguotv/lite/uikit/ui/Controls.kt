package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import com.hongguotv.lite.uikit.internal.FocusControl
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.stateShape
import com.hongguotv.lite.uikit.internal.tinted
import com.hongguotv.lite.uikit.token.Tokens

/** 按钮形态。焦点与选中的画法在三种形态里共用，只有尺寸与对齐不同。 */
enum class ButtonVariant {
    /** 常规动作按钮：与卡片同一层，聚焦加白边、亮一档。 */
    STANDARD,

    /** 主操作（播放 / 继续播放）：常驻亮一档底色 + 加粗。 */
    PRIMARY,

    /** 整行左对齐的设置项：占满一行，字靠左。 */
    ROW,

    /**
     * 紧凑格（分集格）：一行排 20 个时每格只有 40dp 左右，
     * 常规按钮的左右内边距会把"100"挤到看不见。画法与 [STANDARD] 完全一致，只改尺寸。
     */
    CELL,
}

/**
 * 按钮。三种状态各只有一种画法：
 * - 选中 = 白底深字（当前集 / 当前项）；
 * - 已看过 = 略亮一档的底；
 * - 聚焦 = 白描边；白底上聚焦改用深描边（白描边画在白底上看不见）。
 *
 * 选中态走 Android 自己的 `isSelected`（[setSelected]）：这是视图状态的既有语义，
 * 没必要再发明一个 `checked` 之类的同义词。
 */
@SuppressLint("ViewConstructor")
class TvButton internal constructor(
    context: Context,
    tokens: Tokens,
    private val variant: ButtonVariant,
    label: String,
    initialSelected: Boolean,
    private val watched: Boolean,
    private val onTap: () -> Unit,
) : FocusControl(context, tokens) {

    init {
        text = label
        // 形态只有两种尺寸口径（紧凑格 / 其余），先摊成两个布尔，别在四处重复比同一个枚举
        val cell = variant == ButtonVariant.CELL
        val row = variant == ButtonVariant.ROW
        textSize = if (cell) tokens.type.compact else tokens.type.body
        gravity = if (row) Gravity.CENTER_VERTICAL else Gravity.CENTER
        val horizontal = dp(if (cell) tokens.metrics.episodeCellPaddingH else tokens.metrics.controlPaddingH)
        val vertical = dp(if (cell) 0 else tokens.metrics.controlPaddingV)
        setPadding(horizontal, vertical, horizontal, vertical)
        minHeight = dp(
            when {
                row -> tokens.metrics.navHeight
                cell -> tokens.metrics.episodeCellHeight
                else -> tokens.metrics.controlMinHeight
            },
        )
        isSelected = initialSelected
        setOnClickListener { onTap() }
        repaint()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        paintState(isFocused)
    }

    fun label(value: String) {
        text = value
    }

    override fun paintState(focused: Boolean) {
        val colors = tokens.colors
        val chosen = isSelected
        val fill = when {
            chosen -> colors.focus
            watched -> colors.watched
            variant == ButtonVariant.PRIMARY -> colors.raised
            focused -> colors.raised
            else -> colors.surface
        }
        background = stateShape(tokens, fill, tokens.shapes.card, selected = chosen, focused = focused)
        setTextColor(if (chosen) colors.onFocus else colors.text)
        setTypeface(null, if (chosen || variant == ButtonVariant.PRIMARY) Typeface.BOLD else Typeface.NORMAL)
    }
}

/** 胶囊筛选 / 次级切换：比导航和主操作轻一档，靠描边与底色区分选中与聚焦。 */
@SuppressLint("ViewConstructor")
class TvChip internal constructor(
    context: Context,
    tokens: Tokens,
    label: String,
    initialSelected: Boolean,
    iconRes: Int,
    private val onTap: () -> Unit,
) : FocusControl(context, tokens) {

    init {
        text = label
        textSize = tokens.type.compact
        gravity = Gravity.CENTER
        setPadding(dp(tokens.metrics.chipPaddingH), dp(tokens.metrics.chipPaddingV), dp(tokens.metrics.chipPaddingH), dp(tokens.metrics.chipPaddingV))
        minHeight = dp(tokens.metrics.chipMinHeight)
        if (iconRes != 0) setIcon(iconRes, tokens.metrics.iconChip, tokens.metrics.iconGap)
        isSelected = initialSelected
        setOnClickListener { onTap() }
        repaint()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        paintState(isFocused)
    }

    fun label(value: String) {
        text = value
    }

    override fun paintState(focused: Boolean) {
        val colors = tokens.colors
        val chosen = isSelected
        val fill = when {
            chosen -> colors.focus
            focused -> colors.raised
            else -> colors.surface
        }
        background = stateShape(tokens, fill, tokens.shapes.chip, selected = chosen, focused = focused)
        val color = when {
            chosen -> colors.onFocus
            focused -> colors.focus
            else -> colors.muted
        }
        setTextColor(color)
        setTypeface(null, if (chosen || focused) Typeface.BOLD else Typeface.NORMAL)
        tintIcon(color)
    }
}

/**
 * 顶栏导航项：图标 + 文字。当前页 = 白底深字，聚焦 = 白框，其余弱化色。
 * 顶栏只有"当前页"与"光标所在"两块会亮，未选中未聚焦时底色透明。
 */
@SuppressLint("ViewConstructor")
class TvNavItem internal constructor(
    context: Context,
    tokens: Tokens,
    label: String,
    iconRes: Int,
    private val onTap: () -> Unit,
) : FocusControl(context, tokens) {

    init {
        text = label
        textSize = tokens.type.title
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(tokens.metrics.navPaddingH), 0, dp(tokens.metrics.navPaddingH), 0)
        minHeight = dp(tokens.metrics.navHeight)
        if (iconRes != 0) setIcon(iconRes, tokens.metrics.iconNav, tokens.metrics.iconGap)
        setOnClickListener { onTap() }
        repaint()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        paintState(isFocused)
    }

    override fun paintState(focused: Boolean) {
        val colors = tokens.colors
        val chosen = isSelected
        val fill = when {
            chosen -> colors.focus
            focused -> colors.raised
            else -> android.graphics.Color.TRANSPARENT
        }
        background = stateShape(tokens, fill, tokens.shapes.card, selected = chosen, focused = focused)
        val color = when {
            chosen -> colors.onFocus
            focused -> colors.focus
            else -> colors.muted
        }
        setTextColor(color)
        tintIcon(color)
    }
}

/** 小号文字切换键（正序/倒序这类）：不加胶囊，避免和旁边的标题抢大小。 */
@SuppressLint("ViewConstructor")
class TvLink internal constructor(
    context: Context,
    tokens: Tokens,
    label: String,
    private val onTap: () -> Unit,
) : FocusControl(context, tokens) {

    init {
        text = label
        textSize = tokens.type.label
        gravity = Gravity.CENTER
        minHeight = dp(tokens.metrics.linkMinHeight)
        setPadding(dp(tokens.space.m), dp(tokens.space.xs), dp(tokens.space.m), dp(tokens.space.xs))
        setOnClickListener { onTap() }
        repaint()
    }

    fun label(value: String) {
        text = value
    }

    override fun paintState(focused: Boolean) {
        val colors = tokens.colors
        background = stateShape(
            tokens = tokens,
            fill = if (focused) colors.raised else android.graphics.Color.TRANSPARENT,
            radiusDp = tokens.shapes.small,
            selected = false,
            focused = focused,
        )
        setTextColor(if (focused) colors.focus else colors.muted)
        setTypeface(null, if (focused) Typeface.BOLD else Typeface.NORMAL)
    }
}

/** 把图标缩到指定尺寸挂到文字左边。 */
internal fun TextView.setIcon(iconRes: Int, sizeDp: Int, gapDp: Int) {
    val drawable = resources.getDrawable(iconRes, context.theme)
    val size = (sizeDp * resources.displayMetrics.density).toInt()
    drawable.setBounds(0, 0, size, size)
    setCompoundDrawables(drawable, null, null, null)
    compoundDrawablePadding = (gapDp * resources.displayMetrics.density).toInt()
}

/** 按当前文字色给复合图标着色，保证"选中白底时图标是深色"。 */
internal fun TextView.tintIcon(color: Int) {
    for (drawable in compoundDrawables) drawable?.tinted(color)
}
