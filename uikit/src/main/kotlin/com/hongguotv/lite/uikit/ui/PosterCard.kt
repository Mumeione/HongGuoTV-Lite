package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.port.ArtworkSpec
import com.hongguotv.lite.uikit.internal.bindFocus
import com.hongguotv.lite.uikit.internal.Stroke
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.shape
import com.hongguotv.lite.uikit.layout.CardFit

/**
 * 一张海报卡要显示的全部内容。**只有字符串，没有领域模型**——
 * "第几季""已更新""整剧已看完"这些派生文案属于 feature，不属于设计系统。
 *
 * [id] 是业务标识（剧集 id、集号…），uikit 内部据此生成焦点 key；
 * 调用方不需要知道 key 的形态。
 */
data class CardItem(
    val id: String,
    val title: String,
    val coverUrl: String,
    /** 信息条的一行状态（「第九季 第20集」「全 352 集」…）。 */
    val badge: String = "",
    /** 需要用户注意的角标（例如"有新季"）：白色 + 加粗，其余状态用弱化色。 */
    val badgeAttention: Boolean = false,
    /**
     * 不可用项（例如未上线季）整体暗一档。
     *
     * 只表达"看起来不可用"。**是否拦点击由 feature 决定**：旧版的行为是让用户点得进去，
     * 然后提示"《xx》尚未上线"——那是产品文案，不是设计系统的规则。
     */
    val dim: Boolean = false,
    /**
     * 封面左上角的小角标（例如季线里的"当前"）。空串 = 不画。
     *
     * 与 [badge] 分工：角标说"它是什么"（当前季 / 已看完），[badge] 说"它的状态"。
     */
    val corner: String = "",
    /**
     * 封面取不到时**在封面位置**显示的名字。
     *
     * 信息条里不再写片名（片名在信息栏/详情页），于是一张取不到图的卡就成了无名黑块——
     * 上游确实存在给不出图的情况（2026-10-21 实测有一条 hash 只有 21 位的坏地址）。
     * 这段文字画在图片**下层**：图正常时被完全盖住，取不到时才露出来当占位。
     */
    val artworkTitle: String = "",
)

/**
 * 海报卡（B 方案）：封面（贴边圆角）+ 一条信息。
 *
 * 两种形态由 [CardItem.title] 是否为空决定：
 * - **目录卡**（title 空）：封面 + 一条 34dp 信息条（[CardItem.badge]，历史卡可用更矮的信息条）；
 * - **季卡**（title 有值）：封面 + 24dp 季名行 + 20dp 状态行。
 *
 * **聚光焦点**：聚焦 = 放大 1.06 + 3dp 白框 + 白底信息条（白只露在封面下层的信息区，
 * 正是"焦点=白底深字"的本意）；同一屏的其余卡由网格压到 62%（[dimmed]，
 * 见 `PosterGrid.spotlight`）。缩放只影响绘制，不参与几何——焦点图与滚动补偿
 * 一律用未缩放的边界。
 *
 * [maxHeightPx] 是**整张卡的高度上限**（0 = 不限制）。首屏只放得下一行卡时，
 * 由调用方给出视口高度，封面按 [CardFit] 夹短，保证"封面 + 信息条"都在屏内。
 */
@SuppressLint("ViewConstructor")
class PosterCard internal constructor(
    private val ui: UiKit,
    private val widthPx: Int,
    maxHeightPx: Int = 0,
    stripHeightDp: Int = 0,
    /**
     * 显式封面高（dp，0 = 按 [com.hongguotv.lite.uikit.token.Metrics.posterAspect] 派生）。
     * 历史卡是产品口径的 152：B 方案把它压扁了一档，图按 `cover` 裁掉上下而不是留黑边。
     */
    coverHeightDp: Int = 0,
) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    private val artworkView = FrameLayout(ui.context)
    /** 封面取不到时的占位（画在图片下层，见 [CardItem.artworkTitle]）。 */
    private val artworkTitleView = TextView(ui.context)
    private val image = ImageView(ui.context)
    private val cornerView = TextView(ui.context)
    private val titleView = TextView(ui.context)
    private val badgeView = TextView(ui.context)

    /** 单行信息条的高度（目录卡 34 / 历史卡 26）；0 = 默认。 */
    private val stripHeight: Int = if (stripHeightDp > 0) stripHeightDp else tokens.metrics.catalogStripHeight

    /** 显式封面高（像素，0 = 按比例）。 */
    private val explicitCoverHeight: Int = if (coverHeightDp > 0) ui.dp(coverHeightDp) else 0

    private val artworkWidth: Int
    private var artworkHeight: Int
    private var heightLimit: Int = maxHeightPx
    private var coverUrl = ""
    private var coverPersist = false
    private var focused = false

    var item: CardItem? = null
        private set

    /** 聚焦回调：页面用它更新信息栏。 */
    var onFocused: ((CardItem) -> Unit)? = null

    /** 点击回调：feature 决定点进去干什么。 */
    var onActivated: ((CardItem) -> Unit)? = null

    /**
     * 聚光静默态：同一屏的非焦点卡压到 [com.hongguotv.lite.uikit.token.Metrics.focusDimAlpha]。
     * 由网格统一开关（"只有那一张卡是亮的"），单张卡不该自己决定。
     */
    var dimmed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyAlpha()
        }

    init {
        orientation = VERTICAL
        // 外框不留白：封面贴边，留白交给信息区（见类注释）
        setPadding(0, 0, 0, 0)
        // 卡片背景是圆角矩形，把封面按同一个轮廓裁掉四角——API 21+ 的 outline 裁切
        clipToOutline = true

        artworkWidth = widthPx
        artworkHeight = 1
        artworkView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, artworkHeight)
        // 先加占位文本、再加图片：图片在上层，正常时把文字整块盖住
        artworkTitleView.apply {
            textSize = tokens.type.compact
            setTextColor(tokens.colors.muted)
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            val pad = dp(tokens.space.m)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        artworkView.addView(artworkTitleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        image.scaleType = if (explicitCoverHeight > 0) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        artworkView.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 封面上角的小角标（"当前"）：**深底 + 白框 + 白字**。
        // 白字直接压在浅色封面上读不出来，底必须是深色半透明（B 方案 mockup 的 rgba(11,15,21,.9)）。
        cornerView.textSize = tokens.type.corner
        cornerView.includeFontPadding = false
        cornerView.maxLines = 1
        cornerView.minLines = 1
        cornerView.gravity = Gravity.CENTER
        cornerView.setTypeface(null, Typeface.BOLD)
        cornerView.setTextColor(tokens.colors.focus)
        cornerView.background = shape(
            tokens.colors.cornerScrim,
            tokens.shapes.corner,
            Stroke(tokens.colors.focus, 1),
        )
        cornerView.setPadding(dp(tokens.space.xs), 0, dp(tokens.space.xs), 0)
        cornerView.visibility = View.GONE
        artworkView.addView(
            cornerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                val gap = dp(CORNER_GAP)
                leftMargin = gap
                topMargin = gap
            },
        )

        // 季名行（title 有值时才出现）：24dp、加粗。目录卡整行收起。
        titleView.textSize = tokens.type.cardStatus
        titleView.setTextColor(tokens.colors.text)
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.includeFontPadding = false
        titleView.gravity = Gravity.CENTER_VERTICAL
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.setPadding(dp(tokens.metrics.cardPadding), 0, dp(tokens.metrics.cardPadding), 0)

        // 信息条：目录卡 34dp / 季卡状态行 20dp。集数用等宽数字，数字变化时不会左右抖。
        badgeView.textSize = tokens.type.cardStatus
        badgeView.includeFontPadding = false
        badgeView.gravity = Gravity.CENTER_VERTICAL
        badgeView.maxLines = 1
        badgeView.minLines = 1
        badgeView.ellipsize = TextUtils.TruncateAt.END
        badgeView.setPadding(dp(tokens.metrics.cardPadding), 0, dp(tokens.metrics.cardPadding), 0)
        badgeView.fontFeatureSettings = "tnum"

        addView(artworkView)
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, dp(tokens.metrics.seasonTitleHeight)))
        addView(badgeView, LayoutParams(LayoutParams.MATCH_PARENT, dp(stripHeight)))

        // 字号与行数都就位之后才能算"文字块占多高"，否则夹取用的是默认字号的字体度量
        artworkHeight = artworkHeightFor(maxHeightPx)
        artworkView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, artworkHeight)

        bindFocus(
            tokens = tokens,
            paint = { paintCard(it) },
            onChange = { isFocused ->
                focused = isFocused
                val current = item ?: return@bindFocus
                // 聚焦项优先解码：用户马上要看到这张图。
                syncCover()
                applyAlpha()
                if (isFocused) onFocused?.invoke(current)
            },
        )
        setOnClickListener { item?.let { onActivated?.invoke(it) } }
    }

    fun bind(item: CardItem) {
        this.item = item
        titleView.text = item.title
        contentDescription = item.title
        // 信息条里不重复写片名：片名在信息栏/详情页说（维护者口径：卡片太小，片名太长不美观）。
        // 有 title 的是季卡（季名 + 状态两行），没有的是目录卡（一条信息）。
        val hasTitle = item.title.isNotEmpty()
        titleView.visibility = if (hasTitle) View.VISIBLE else View.GONE
        badgeView.textSize = if (hasTitle) tokens.type.micro else tokens.type.cardStatus
        badgeView.layoutParams = (badgeView.layoutParams as LayoutParams).apply {
            height = dp(if (hasTitle) tokens.metrics.seasonStatusHeight else stripHeight)
        }
        cornerView.text = item.corner
        cornerView.visibility = if (item.corner.isEmpty()) View.GONE else View.VISIBLE
        badgeView.text = item.badge
        artworkTitleView.text = item.artworkTitle
        artworkTitleView.visibility = if (item.artworkTitle.isEmpty()) View.GONE else View.VISIBLE
        // 文字块高度随形态变了：夹取过的封面要按新口径重算一次
        applyHeightLimit()
        coverUrl = item.coverUrl
        if (coverUrl.isEmpty()) image.setImageDrawable(null) else syncCover()
        applyAlpha()
        paintCard(focused)
    }

    /** 选中态变化（"当前季"）要换底色与文字色，不能等下一次焦点变化才重画。 */
    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        paintCard(focused)
    }

    /** 是否允许封面落盘：只有收藏/历史的封面值得占电视存储。由网格在渲染前统一设定。 */
    fun persistCover(persist: Boolean) {
        if (coverPersist == persist) return
        coverPersist = persist
        syncCover()
    }

    /**
     * 更新"整张卡的高度上限"（0 = 不限制）。
     *
     * 就地改封面高度，**不重建卡片**：重建会换掉 View 实例，焦点随之丢掉，
     * 页面为了把光标找回来又得额外收缩一次——首屏量到真实视口后正好会发生这件事。
     */
    fun heightLimit(maxHeightPx: Int) {
        if (heightLimit == maxHeightPx) return
        heightLimit = maxHeightPx
        applyHeightLimit()
    }

    /** 按"当前上限 + 当前内容"重算封面高度；高度没变就不动视图（避免无谓的重新取图）。 */
    private fun applyHeightLimit() {
        val next = artworkHeightFor(heightLimit)
        if (next == artworkHeight) return
        artworkHeight = next
        artworkView.layoutParams = (artworkView.layoutParams as LayoutParams).apply { height = next }
        syncCover()
    }

    private fun artworkHeightFor(limitPx: Int): Int = CardFit.artworkHeight(
        cardWidthPx = artworkWidth,
        aspect = tokens.metrics.posterAspect,
        cardHeightLimitPx = limitPx,
        textBlockHeightPx = textBlockHeight(),
        minArtworkHeightPx = dp(tokens.metrics.artworkMinHeight),
        explicitHeightPx = explicitCoverHeight,
    )

    /** 整体不透明度：焦点永远全亮；未上线最暗；聚光静默档居中。 */
    private fun applyAlpha() {
        alpha = when {
            focused -> 1f
            item?.dim == true -> tokens.metrics.dimAlpha
            dimmed -> tokens.metrics.focusDimAlpha
            else -> 1f
        }
    }

    /**
     * 卡片底色、白框与文字色（B 方案聚光焦点）。
     *
     * - **焦点 = 放大 + 3dp 白框 + 白底信息条**：封面贴边，白色底只露在封面下面那条信息区——
     *   视觉上就是"封面下层的背景变白、文字变黑"。全屏唯一的白色填充就是这里。
     *   白框画在前景（封面铺满上半部分，画在背景上的描边会被图整条盖住）。
     * - **选中（当前季）= 2dp 白框**，不填充：白色填充只留给光标，否则一页会有两块白。
     */
    private fun paintCard(isFocused: Boolean) {
        val chosen = isSelected
        val fill = if (isFocused) tokens.colors.focus else tokens.colors.surface
        // 背景只负责底色（会被封面盖住大半），框交给前景
        background = shape(fill, tokens.shapes.card, null)
        foreground = if (isFocused || chosen) {
            val stroke = if (isFocused) tokens.shapes.focusStroke else tokens.shapes.stroke
            shape(android.graphics.Color.TRANSPARENT, tokens.shapes.card, Stroke(tokens.colors.focus, stroke))
        } else {
            null
        }

        titleView.setTextColor(if (isFocused) tokens.colors.onFocus else tokens.colors.text)
        val attention = item?.badgeAttention == true
        badgeView.setTextColor(
            when {
                isFocused -> tokens.colors.onFocus
                attention || chosen -> tokens.colors.focus
                else -> tokens.colors.muted
            },
        )
        badgeView.setTypeface(null, if (isFocused || attention || chosen) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * 封面以下那块文字在竖直方向占多少：夹取封面高度时要把它先扣掉，
     * 否则信息条会被推到视口外。季卡是"季名 + 状态"两行，目录卡是一条信息。
     */
    private fun textBlockHeight(): Int {
        val hasTitle = item?.title?.isNotEmpty() == true
        return if (hasTitle) {
            dp(tokens.metrics.seasonTitleHeight + tokens.metrics.seasonStatusHeight)
        } else {
            dp(stripHeight)
        }
    }

    private fun syncCover() {
        val port = ui.artwork ?: return
        if (coverUrl.isEmpty()) return
        port.load(
            host = image,
            url = coverUrl,
            spec = ArtworkSpec(
                widthPx = artworkWidth,
                heightPx = artworkHeight,
                persist = coverPersist,
                priority = if (focused) ArtworkSpec.PRIORITY_FOCUSED else ArtworkSpec.PRIORITY_VISIBLE,
            ),
        )
    }

    private companion object {
        /** 封面角标与卡片边缘的距离（mockup `.b-corner{left:5px;top:5px}`）。 */
        const val CORNER_GAP = 5
    }
}
