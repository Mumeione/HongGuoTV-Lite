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
import com.hongguotv.lite.uikit.internal.Stroke
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
    }

    /** 顶部对齐的行：卡片行用它，避免高度不齐时卡片被垂直居中。 */
    fun rowTop(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
    }

    /** 底部对齐的行：详情 hero 的动作列贴「记录」行底部用（mockup `align-self:end`）。 */
    fun rowBottom(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
    }

    fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
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
        setPadding(
            dp(tokens.metrics.setPanelPaddingH),
            dp(tokens.metrics.setPanelPaddingV),
            dp(tokens.metrics.setPanelPaddingH),
            dp(tokens.metrics.setPanelPaddingV),
        )
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

    fun link(label: String, onTap: () -> Unit): TvLink = TvLink(context, tokens, label, onTap)

    /** 设置面板的一行：左标签 + 右值 + 箭头（B 方案 mockup 画面7）。 */
    fun settingRow(label: String, value: String, onTap: () -> Unit): SettingRow =
        SettingRow(this, label, value, onTap)

    /**
     * 键帽（信息栏右侧的「OK / ◀▶」、播放抽屉顶行的「MENU」）。
     *
     * 两种画法：信息栏是 raised 底（mockup `.b-key`），抽屉是透明底 + 1px 结构线
     * （`.b-navkey`）。宽度随文字走、minWidth 保住方块观感——"MENU" 四个字母装不进 22dp。
     */
    fun keyCap(label: String, outlined: Boolean = false): TextView = TextView(context).apply {
        text = label
        textSize = tokens.type.railLabel
        setTextColor(tokens.colors.text)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(tokens.metrics.keyCapSize)).apply {
            minWidth = dp(tokens.metrics.keyCapSize)
            rightMargin = dp(tokens.space.s)
        }
        background = if (outlined) {
            shape(android.graphics.Color.TRANSPARENT, tokens.metrics.keyCapRadius, Stroke(tokens.colors.outline, 1))
        } else {
            shape(tokens.colors.raised, tokens.metrics.keyCapRadius, null)
        }
        val pad = dp(if (outlined) tokens.metrics.keyCapPaddingH else tokens.space.xxs)
        setPadding(pad, 0, pad, 0)
    }

    fun input(label: String, maxLength: Int = 80): TvInput = TvInput(context, tokens, label, maxLength)

    /** 单张封面（详情页大图）。走封面端口取图，尺寸由调用方给。 */
    fun cover(widthDp: Int, heightDp: Int): TvCover = TvCover(this, dp(widthDp), dp(heightDp))

    /** 两列规格表（B 方案详情 hero 的信息列）。 */
    fun specTable(): SpecTable = SpecTable(this)

    // ---------- 页面与网格 ----------

    fun page(): PageScaffold = PageScaffold(this)

    /**
     * 内容区：可滚动列表 + 居中空态，二态互斥。
     * 页面把它塞进页体并给 `height = 0, weight = 1`，它就会吃掉页头以下的全部空间。
     */
    fun contentPane(): ContentPane = ContentPane(this)

    fun grid(section: String = "card"): PosterGrid = PosterGrid(this, section)

    /**
     * 单张海报卡，宽度由调用方给（像素）；单行信息条的高度可选（0 = 目录卡默认 34dp）。
     *
     * 给"列数由产品口径决定、不由宽度算出来"的场景用——季线一页 8 张、历史页两行 6 列
     * 都是这种：用 [grid] 的话列数会按卡宽反推，40 季的剧会被 `pageCardLimit` 截断。
     * 外观与网格里的卡完全一致，不为一个区块另造一种卡。
     */
    fun poster(
        widthPx: Int,
        stripHeightDp: Int = 0,
        coverHeightDp: Int = 0,
    ): PosterCard = PosterCard(
        ui = this,
        widthPx = widthPx,
        stripHeightDp = stripHeightDp,
        coverHeightDp = coverHeightDp,
    )

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

    /** 左侧固定占位（侧栏宽），由 [PageScaffold.showRail] 更新；估算可用宽度时扣除。 */
    internal var leftChromeDp: Int = 0

    /** 页边距：B 方案固定 40dp（不再按屏幕比例缩放）。 */
    internal fun pageInsetHorizontal(): Int = dp(tokens.metrics.pagePaddingH)

    internal fun pageInsetVertical(): Int = dp(tokens.metrics.pagePaddingTop)

    /**
     * 内容区可用宽度：已量过就用量过的，没量过按"屏幕 − 左侧栏 − 左右页边距"估。
     *
     * 给"列数由产品口径决定"的行用（季线一页 8 张），它没法像网格那样按卡宽反推列数。
     * 第一次渲染时视图还没量过，传 0 即可。
     *
     * 估算必须扣**左右两侧**的页边距：只扣一侧会多算 40dp，首帧按偏宽的宽度算出偏大的卡宽，
     * 布局量到真实宽度后又要重建一次卡片（焦点得靠会话找回来，白做一轮）。
     */
    fun availableWidth(measured: Int): Int =
        if (measured > 0) {
            measured
        } else {
            context.resources.displayMetrics.widthPixels - dp(tokens.metrics.pagePaddingH) * 2 - dp(leftChromeDp)
        }
}
