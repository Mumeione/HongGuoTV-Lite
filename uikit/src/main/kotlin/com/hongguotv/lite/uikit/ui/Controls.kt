package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import com.hongguotv.lite.uikit.internal.FocusControl
import com.hongguotv.lite.uikit.internal.Stroke
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.shape
import com.hongguotv.lite.uikit.internal.stateShape
import com.hongguotv.lite.uikit.internal.tinted
import com.hongguotv.lite.uikit.token.Tokens

/** 按钮形态。焦点与选中的画法共用，只有尺寸与底色不同——B 方案里动作键全是胶囊。 */
enum class ButtonVariant {
    /** 常规动作胶囊：40dp 高、左右 20dp。 */
    STANDARD,

    /** 小号动作胶囊：32dp 高、左右 14dp（翻组、返回、倒序这类）。 */
    SMALL,

    /**
     * 主操作（播放 / 继续播放）。B 方案 mockup 里它与常规胶囊同底（surface）——
     * "主"只靠加粗与位置表达；保留枚举名是为了调用点语义可读。
     */
    PRIMARY,

    /** 整行左对齐的设置项：46dp 高、圆角 10，底色比面板亮一档（`colors.row`）。 */
    ROW,

    /** 抽屉里的设置行：34dp 高、圆角 8（B 方案播放抽屉的右列）。 */
    SMALL_ROW,

    /**
     * 紧凑格（分集格）：26dp 高的胶囊，一行排 20 个。
     * 画法与胶囊一致，只改尺寸与圆角。
     */
    CELL,

    /** 抽屉里的分集格：26dp 高、圆角 6（一行排 10 个，与详情页的胶囊格区分）。 */
    GRID_CELL,
}

/**
 * 按钮。三种状态各只有一种画法（S9 语义，B 方案延续）：
 * - **焦点 = 白底深字**（当前按确认会执行的那个）；
 * - **选中 = 白框白字**（当前集 / 当前项，没有填充）；
 * - **已看过 = 略亮一档的底**（只在没焦点也没选中时显出来）。
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
        val cell = variant == ButtonVariant.CELL || variant == ButtonVariant.GRID_CELL
        val row = variant == ButtonVariant.ROW
        val smallRow = variant == ButtonVariant.SMALL_ROW
        val small = variant == ButtonVariant.SMALL
        textSize = when {
            cell -> tokens.type.footnote
            row -> tokens.type.specValue
            smallRow -> tokens.type.cardStatus
            small -> tokens.type.cardStatus
            else -> tokens.type.compact
        }
        gravity = if (row || smallRow) Gravity.CENTER_VERTICAL else Gravity.CENTER
        val horizontal = dp(
            when {
                cell -> tokens.metrics.episodeCellPaddingH
                row -> tokens.space.l
                smallRow -> tokens.space.m
                small -> tokens.metrics.pillSmallPaddingH
                else -> tokens.metrics.pillPaddingH
            },
        )
        setPadding(horizontal, 0, horizontal, 0)
        minHeight = dp(
            when {
                row -> tokens.metrics.setRowHeight
                smallRow -> tokens.metrics.drawerRowHeight
                cell -> tokens.metrics.episodeCellHeight
                small -> tokens.metrics.pillSmallHeight
                else -> tokens.metrics.pillHeight
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
        // 全屏只有一处白色填充，就是光标所在的那一项（维护者口径）。
        // 焦点 = 白底深字；选中 = 白框白字（不填充）。
        val fill = when {
            focused -> colors.focus
            watched -> colors.watched
            variant == ButtonVariant.ROW || variant == ButtonVariant.SMALL_ROW -> colors.row
            else -> colors.surface
        }
        background = stateShape(
            tokens = tokens,
            fill = fill,
            radiusDp = when {
                variant == ButtonVariant.ROW -> tokens.shapes.row
                variant == ButtonVariant.SMALL_ROW -> tokens.shapes.small
                variant == ButtonVariant.GRID_CELL -> tokens.metrics.drawerCellRadius
                variant == ButtonVariant.CELL -> tokens.metrics.episodeCellRadius
                else -> tokens.shapes.pill
            },
            selected = chosen,
            focused = focused,
        )
        val color = when {
            focused -> colors.onFocus
            chosen -> colors.focus
            else -> colors.text
        }
        setTextColor(color)
        // B 方案 mockup：胶囊与格子的文字全部加粗（.b-pill / .b-cell / .setrow 都是 600–700）
        setTypeface(null, Typeface.BOLD)
    }
}

/** 胶囊筛选 / 次级切换（片库、倒序）：32dp 小号胶囊。焦点白底深字，选中白框白字，其余暗底弱化字。 */
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
        textSize = tokens.type.cardStatus
        gravity = Gravity.CENTER
        setPadding(dp(tokens.metrics.pillSmallPaddingH), 0, dp(tokens.metrics.pillSmallPaddingH), 0)
        minHeight = dp(tokens.metrics.pillSmallHeight)
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
        // 焦点 = 白底深字（与 TvButton 同一条规则）：底色必须跟着焦点走，
        // 漏了这一步就是"深底 + 深字"，字直接看不见（本轮实测踩到）。
        // 选中框用 60% 白（Colors.selectionStroke）：片库胶囊与焦点白底常驻同一行，
        // 全亮白框会让远看的人认错光标（审计 2026-10-22 #2-d）。焦点仍是唯一的全白。
        background = when {
            chosen && !focused -> shape(
                colors.surface,
                tokens.shapes.pill,
                Stroke(colors.selectionStroke, tokens.shapes.stroke),
            )
            focused -> stateShape(tokens, colors.focus, tokens.shapes.pill, selected = chosen, focused = true)
            else -> stateShape(tokens, colors.surface, tokens.shapes.pill, selected = false, focused = false)
        }
        val color = when {
            focused -> colors.onFocus
            chosen -> colors.focus
            else -> colors.muted
        }
        setTextColor(color)
        setTypeface(null, Typeface.BOLD)
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
        textSize = tokens.type.caption
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
            fill = if (focused) colors.focus else android.graphics.Color.TRANSPARENT,
            radiusDp = tokens.shapes.small,
            selected = false,
            focused = focused,
        )
        setTextColor(if (focused) colors.onFocus else colors.muted)
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
