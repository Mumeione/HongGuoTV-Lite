package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.internal.bindFocus
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.stateShape
import com.hongguotv.lite.uikit.internal.tinted
import com.hongguotv.lite.uikit.token.Tokens

/** 侧栏的一项：标签 + 图标 + 选中后的动作。 */
data class RailItem(val label: String, val iconRes: Int, val onSelect: () -> Unit)

/**
 * 左侧图标栏（B 方案）：80dp 宽，替代旧顶栏。
 *
 * 结构：品牌图标（52dp，与导航项视觉等大）+ 四个导航项（56×54，图标 17dp 在上、11sp 文字在下）。
 * 状态与全应用同一套语义：**光标 = 白底深字；当前页 = 2dp 白框白字**，其余弱化色。
 *
 * 焦点行为是"竖排的侧段"：上下在项间移动，按右进内容区（见 `FocusPlan.side`）。
 */
@SuppressLint("ViewConstructor")
class RailNav internal constructor(ui: UiKit, items: List<RailItem>) : FrameLayout(ui.context) {

    private val tokens = ui.tokens
    val navItems: List<RailNavItem>

    init {
        setBackgroundColor(tokens.colors.chrome)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(tokens.metrics.railPaddingTop), dp(1), dp(tokens.metrics.railPaddingBottom))
        }
        column.addView(
            ImageView(context).apply { setImageResource(Icons.BRAND) },
            LinearLayout.LayoutParams(dp(tokens.metrics.railBrandIcon), dp(tokens.metrics.railBrandIcon)).apply {
                bottomMargin = dp(RAIL_BRAND_GAP)
            },
        )
        navItems = items.mapIndexed { index, item ->
            RailNavItem(context, tokens, item.label, item.iconRes, item.onSelect).also { nav ->
                column.addView(
                    nav,
                    LinearLayout.LayoutParams(dp(tokens.metrics.railItemWidth), dp(tokens.metrics.railItemHeight)).apply {
                        if (index < items.lastIndex) bottomMargin = dp(tokens.metrics.railItemGap)
                    },
                )
            }
        }
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 右缘 1px 结构线：侧栏与内容之间唯一的分界（B 方案不用别的分隔线）
        addView(
            View(context).apply { setBackgroundColor(tokens.colors.line) },
            LayoutParams(dp(tokens.metrics.dividerHeight.coerceAtLeast(1)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
        )
    }

    /** 切换当前页：只有"当前页"那一个亮起白框。 */
    fun select(index: Int) {
        navItems.forEachIndexed { i, item -> item.isSelected = i == index }
    }

    /** 侧栏这一段的焦点 key（`nav:0 … nav:n-1`），由页面拼进 `FocusPlan.side`。 */
    fun keys(): List<FocusKey> = navItems.indices.map { FocusKey("nav:$it") }

    companion object {
        /**
         * 全应用唯一的左侧栏项集（搜索 / 收藏 / 历史 / 设置）。
         *
         * 这四个词与四个图标是**导航词汇**，归设计系统所有——目录 feature 与详情 feature
         * 都从这取，不能各自写一份"收藏"（两个名字会漂）。[onSelect] 收到选中的下标。
         */
        fun standardItems(onSelect: (Int) -> Unit): List<RailItem> = listOf(
            RailItem("搜索", Icons.SEARCH) { onSelect(0) },
            RailItem("收藏", Icons.FAVORITE) { onSelect(1) },
            RailItem("历史", Icons.HISTORY) { onSelect(2) },
            RailItem("设置", Icons.SETTINGS) { onSelect(3) },
        )

        /** 品牌图标与第一个导航项之间多留的一档（mockup `.b-logo-svg{margin-bottom:14px}`）。 */
        private const val RAIL_BRAND_GAP = 14
    }
}

/**
 * 侧栏导航项：图标在上、文字在下。
 *
 * 焦点 = 白底深字（图标一起翻深色）；当前页 = 2dp 白框 + 白字，不填充。
 * 不放大：56×54 的格子在一列里，放大 6% 会压到邻居的白框。
 */
@SuppressLint("ViewConstructor")
class RailNavItem internal constructor(
    context: Context,
    private val tokens: Tokens,
    label: String,
    iconRes: Int,
    private val onTap: () -> Unit,
) : TextView(context) {

    /** 焦点变化回调：页面用它更新信息栏。 */
    var onFocused: ((Boolean) -> Unit)? = null

    init {
        text = label
        textSize = tokens.type.railLabel
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        includeFontPadding = false
        val drawable = resources.getDrawable(iconRes, context.theme).tinted(tokens.colors.muted)
        val size = context.dp(tokens.metrics.railIcon)
        drawable.setBounds(0, 0, size, size)
        setCompoundDrawables(null, drawable, null, null)
        compoundDrawablePadding = context.dp(ICON_TEXT_GAP)
        setOnClickListener { onTap() }
        bindFocus(
            tokens = tokens,
            paint = { paintState(it) },
            scale = { false },
            onChange = { onFocused?.invoke(it) },
        )
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        paintState(isFocused)
    }

    private fun paintState(focused: Boolean) {
        val colors = tokens.colors
        background = stateShape(
            tokens = tokens,
            fill = if (focused) colors.focus else Color.TRANSPARENT,
            radiusDp = tokens.shapes.card,
            selected = isSelected,
            focused = focused,
        )
        val color = when {
            focused -> colors.onFocus
            isSelected -> colors.focus
            else -> colors.muted
        }
        setTextColor(color)
        for (drawable in compoundDrawables) drawable?.tinted(color)
    }

    private companion object {
        const val ICON_TEXT_GAP = 2
    }
}
