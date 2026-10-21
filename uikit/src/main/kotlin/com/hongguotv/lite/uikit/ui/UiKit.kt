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
import com.hongguotv.lite.uikit.internal.shape
import com.hongguotv.lite.uikit.page.ContentPane
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
        allowFocusOverflow()
    }

    /** 顶部对齐的行：卡片行用它，避免高度不齐时卡片被垂直居中。 */
    fun rowTop(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        allowFocusOverflow()
    }

    fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        allowFocusOverflow()
    }

    /**
     * 焦点缩放会轻微超出控件边界（1.03 倍）：父容器默认 `clipChildren = true`，
     * 会把白框/圆角切掉一条边。行与列是排布容器，不负责遮挡，统一放开。
     * 真正的裁切交给滚动视口负责。
     */
    private fun ViewGroup.allowFocusOverflow() {
        clipChildren = false
        clipToPadding = false
    }

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

    /** 面板：设置页两栏的卡片底。圆角与留白走令牌，页面不再自己拼背景。 */
    fun panel(): LinearLayout = column().apply {
        background = shape(tokens.colors.surface, tokens.shapes.panel, null)
        setPadding(dp(tokens.space.xl), dp(tokens.space.xl), dp(tokens.space.xl), dp(tokens.space.l))
    }

    /**
     * 悬浮块的底（播放侧栏、提示卡）：与 [panel] 同色，圆角与内边距由调用方给。
     *
     * 与 [panel] 的区别只有"谁来定留白"——侧栏要贴边、提示卡要塞在画面底部，
     * 都不能用页面级的固定内边距。
     */
    fun tile(radiusDp: Int = tokens.shapes.panel, paddingDp: Int = tokens.space.l): LinearLayout = column().apply {
        background = shape(tokens.colors.surface, radiusDp, null)
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
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

    fun input(label: String, maxLength: Int = 80): TvInput = TvInput(context, tokens, label, maxLength)

    /** 单张封面（详情页大图）。走封面端口取图，尺寸由调用方给。 */
    fun cover(widthDp: Int, heightDp: Int): TvCover = TvCover(this, dp(widthDp), dp(heightDp))

    fun preview(): PreviewRail = PreviewRail(this)

    // ---------- 页面与网格 ----------

    fun page(): PageScaffold = PageScaffold(this)

    /**
     * 内容区：可滚动列表 + 居中空态，二态互斥。
     * 页面把它塞进页体并给 `height = 0, weight = 1`，它就会吃掉页头以下的全部空间。
     */
    fun contentPane(): ContentPane = ContentPane(this)

    fun grid(section: String = "card"): PosterGrid = PosterGrid(this, section)

    /**
     * 单张海报卡，宽度由调用方给（像素）。
     *
     * 给"列数由产品口径决定、不由宽度算出来"的场景用——季线一页 8 张就是这种：
     * 用 [grid] 的话列数会按卡宽反推，40 季的剧会被 `pageCardLimit` 截断。
     * 外观与网格里的卡完全一致，不为一个区块另造一种卡。
     */
    fun poster(widthPx: Int): PosterCard = PosterCard(this, widthPx)

    // ---------- 布局参数 ----------

    /**
     * dp → 像素。feature 侧写尺寸（输入框宽度、页脚高度…）一律走这里，
     * 不在调用点自己乘密度——换算只有一处，换屏幕密度时才不会漏改。
     */
    fun dp(value: Int): Int = context.dp(value)

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

    /**
     * 内容区可用宽度：已量过就用量过的，没量过按屏幕减页边距估。
     *
     * 给"列数由产品口径决定"的行用（季线一页 8 张），它没法像网格那样按卡宽反推列数。
     * 第一次渲染时视图还没量过，传 0 即可。
     */
    fun availableWidth(measured: Int): Int =
        if (measured > 0) measured else (context.resources.displayMetrics.widthPixels - pageInsetHorizontal() * 2)
}
