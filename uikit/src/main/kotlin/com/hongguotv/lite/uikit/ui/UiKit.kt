package com.hongguotv.lite.uikit.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.port.ArtworkPort
import com.hongguotv.lite.uikit.R
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.page.PageScaffold
import com.hongguotv.lite.uikit.page.PosterGrid
import com.hongguotv.lite.uikit.page.ScrollFocusKeeper
import com.hongguotv.lite.uikit.token.DeviceProfile
import com.hongguotv.lite.uikit.token.Tokens

/** uikit 自带的矢量图标。着色由控件按当前状态负责。 */
object Icons {
    val SEARCH = R.drawable.ic_nav_search
    val FAVORITE = R.drawable.ic_nav_favorite
    val HISTORY = R.drawable.ic_nav_history
    val SETTINGS = R.drawable.ic_nav_settings
    val SORT = R.drawable.ic_sort
    val BRAND = R.drawable.ic_brand
}

/**
 * 设计系统的唯一入口：一个实例拿着令牌、封面端口与全部控件工厂。
 *
 * 由 `app` 组装一次、注入给各 feature——不用单例、不用全局 `Context`。
 * 这样"换主题/换档位"是构造参数，而不是散在各处的静态状态。
 *
 * 注意：**所有控件只接字符串**。领域概念（季、季键、系列）留在 feature，
 * 词汇的唯一口径是 `docs/CONTEXT.md`。
 */
class UiKit(
    val context: Context,
    val tokens: Tokens = Tokens.of(DeviceProfile.tierOf(context)),
    /** 封面端口；为 null 时不加载封面（预览/单测场景）。 */
    val artwork: ArtworkPort? = null,
) {

    // ---------- 基础容器 ----------

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 顶部对齐的行：卡片行用它，避免高度不齐时卡片被垂直居中。 */
    fun rowTop(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
    }

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun text(value: String = "", size: Float = tokens.type.body, color: Int = tokens.colors.text): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    /** 1px 分隔线：顶栏与内容之间的那条。 */
    fun divider(): View = View(context).apply {
        setBackgroundColor(tokens.colors.outline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(tokens.metrics.dividerHeight))
    }

    fun scroll(content: View): ScrollView = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * 给滚动区装上焦点补偿：光标移出视口时自动滚到可见处。
     * 替代旧实现里"两层 post + 内联算术"的手工补偿。
     */
    fun keepFocusVisible(scroll: ScrollView): ScrollFocusKeeper =
        ScrollFocusKeeper(tokens, scroll).also { it.attach() }

    // ---------- 控件 ----------

    fun button(
        label: String,
        selected: Boolean = false,
        watched: Boolean = false,
        variant: ButtonVariant = ButtonVariant.STANDARD,
        onTap: () -> Unit,
    ): TvButton = TvButton(context, tokens, variant, label, selected, watched, onTap)

    fun chip(label: String, selected: Boolean = false, iconRes: Int = 0, onTap: () -> Unit): TvChip =
        TvChip(context, tokens, label, selected, iconRes, onTap)

    fun navItem(label: String, iconRes: Int, onTap: () -> Unit): TvNavItem =
        TvNavItem(context, tokens, label, iconRes, onTap)

    fun link(label: String, onTap: () -> Unit): TvLink = TvLink(context, tokens, label, onTap)

    fun preview(): PreviewRail = PreviewRail(this)

    // ---------- 页面与网格 ----------

    fun page(): PageScaffold = PageScaffold(this)

    fun grid(section: String = "card"): PosterGrid = PosterGrid(this, section)

    // ---------- 布局参数 ----------

    fun params(
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        rightGap: Int = 0,
        bottomGap: Int = 0,
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
        this.weight = weight
        rightMargin = context.dp(rightGap)
        bottomMargin = context.dp(bottomGap)
    }

    /** 页边距：屏幕宽/高的固定比例（旧实现 5%），随窗口走而不是随内容走。 */
    internal fun pageInsetHorizontal(): Int {
        val metrics = context.resources.displayMetrics
        return (metrics.widthPixels * tokens.metrics.pageInsetRatio).toInt()
    }

    internal fun pageInsetVertical(): Int {
        val metrics = context.resources.displayMetrics
        return (metrics.heightPixels * tokens.metrics.pageInsetRatio).toInt()
    }

    /** 内容区可用宽度：已量过就用量过的，没量过按屏幕减页边距估。 */
    internal fun availableWidth(measured: Int): Int =
        if (measured > 0) measured else (context.resources.displayMetrics.widthPixels - pageInsetHorizontal() * 2)
}
