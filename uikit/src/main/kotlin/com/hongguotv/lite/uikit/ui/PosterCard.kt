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
     * 卡片下方已经不写片名了（维护者口径），于是一张取不到图的卡就成了无名黑块——
     * 上游确实存在给不出图的情况（2026-10-21 实测有一条 hash 只有 21 位的坏地址）。
     * 这段文字画在图片**下层**：图正常时被完全盖住，取不到时才露出来当占位。
     */
    val artworkTitle: String = "",
)

/**
 * 海报卡：封面（贴边）+ 可选一行标题 + 一行角标。
 *
 * 版式是 2026-10-21 第三轮定的：**封面铺满卡片上半部分**，文字区在下面。
 * 上一版四周各留 4dp，聚焦时整张卡变成白底、封面像拍立得一样浮在白框里，
 * 一屏五张卡就有五块白框；现在白色只露在封面下方的信息条上（正是"焦点=白底深字"的本意），
 * 封面与卡片同宽、圆角由卡片轮廓裁切。
 *
 * [maxHeightPx] 是**整张卡的高度上限**（0 = 不限制）。首屏只放得下一行卡时，
 * 由调用方给出视口高度，封面按 [CardFit] 夹短，保证"封面 + 标题 + 角标"都在屏内。
 */
@SuppressLint("ViewConstructor")
class PosterCard internal constructor(
    private val ui: UiKit,
    private val widthPx: Int,
    maxHeightPx: Int = 0,
) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    private val artworkView = FrameLayout(ui.context)
    /** 封面取不到时的占位（画在图片下层，见 [CardItem.artworkTitle]）。 */
    private val artworkTitleView = TextView(ui.context)
    private val image = ImageView(ui.context)
    private val cornerView = TextView(ui.context)
    private val titleView = TextView(ui.context)
    private val badgeView = TextView(ui.context)

    private val artworkWidth: Int
    private var artworkHeight: Int
    private var heightLimit: Int = maxHeightPx
    private var coverUrl = ""
    private var coverPersist = false
    private var focused = false

    var item: CardItem? = null
        private set

    /** 聚焦回调：目录页用它更新预览区。 */
    var onFocused: ((CardItem) -> Unit)? = null

    /** 点击回调：feature 决定点进去干什么。 */
    var onActivated: ((CardItem) -> Unit)? = null

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
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        artworkView.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 封面上角的小角标（"当前"）：**深底 + 白框 + 白字**。
        // S9 之前它是白底深字，与"焦点那块白底"抢唯一的白色；现在只有框，且不能是透明底——
        // 白字直接压在浅色封面上读不出来。
        cornerView.textSize = tokens.type.caption
        cornerView.includeFontPadding = false
        cornerView.maxLines = 1
        cornerView.minLines = 1
        cornerView.gravity = Gravity.CENTER
        cornerView.setTypeface(null, Typeface.BOLD)
        cornerView.setTextColor(tokens.colors.focus)
        cornerView.setBackground(shape(tokens.colors.surface, tokens.shapes.small, null).apply {
            setStroke(dp(tokens.shapes.stroke).coerceAtLeast(1), tokens.colors.focus)
        })
        cornerView.setPadding(dp(tokens.space.s), dp(tokens.space.xxs), dp(tokens.space.s), dp(tokens.space.xxs))
        cornerView.visibility = View.GONE
        artworkView.addView(
            cornerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                val gap = dp(tokens.space.xs)
                leftMargin = gap
                topMargin = gap
            },
        )

        titleView.textSize = tokens.type.compact
        titleView.setTextColor(tokens.colors.text)
        // 中等字重：卡面是图，标题再用常规字重在电视观看距离下会显得发虚
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        titleView.includeFontPadding = false
        // 只占实际行数（季卡写「第九季」一行就够）：`minLines = 2` 会在标题与角标之间
        // 留出一整行空白，季卡看着像"上下两块被拉开了"
        titleView.maxLines = TITLE_LINES
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.setPadding(
            dp(tokens.metrics.cardPadding),
            dp(tokens.metrics.cardTitlePaddingTop),
            dp(tokens.metrics.cardPadding),
            0,
        )

        badgeView.textSize = tokens.type.caption
        badgeView.includeFontPadding = false
        badgeView.maxLines = 1
        badgeView.minLines = 1
        badgeView.minHeight = dp(tokens.metrics.badgeHeight)
        badgeView.ellipsize = TextUtils.TruncateAt.END
        badgeView.setPadding(
            dp(tokens.metrics.cardPadding),
            0,
            dp(tokens.metrics.cardPadding),
            dp(tokens.metrics.cardPaddingBottom),
        )
        // 集数/时间用等宽数字，数字变化时不会左右抖。
        badgeView.fontFeatureSettings = "tnum"

        addView(artworkView)
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(badgeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 字号与行数都就位之后才能算"文字块占多高"，否则夹取用的是默认字号的字体度量
        artworkHeight = artworkHeightFor(maxHeightPx)
        artworkView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, artworkHeight)

        bindFocus(
            tokens = tokens,
            paint = { paintCard(it) },
            // 卡片不缩放：滚动视口会在边界裁切，放大的卡在首行/末行会被切掉圆角，
            // 看起来就成了"圆角矩形变长方形"（维护者实测）。白底信息区本身已是焦点信号。
            scale = { false },
            onChange = { isFocused ->
                focused = isFocused
                val current = item ?: return@bindFocus
                // 聚焦项优先解码：用户马上要看到这张图。
                syncCover()
                if (isFocused) onFocused?.invoke(current)
            },
        )
        setOnClickListener { item?.let { onActivated?.invoke(it) } }
    }

    fun bind(item: CardItem) {
        this.item = item
        titleView.text = item.title
        contentDescription = item.title
        // 卡片下方不重复显示片名：片名由上方的大字预览区说，卡上只留一行状态
        //（维护者口径：卡片太小，片名太长不美观）。空标题整行收起。
        val hasTitle = item.title.isNotEmpty()
        titleView.visibility = if (hasTitle) View.VISIBLE else View.GONE
        // 标题行收起后角标直接贴着封面：把原本标题与封面之间的留白挪给角标，别挤在一起
        badgeView.setPadding(
            dp(tokens.metrics.cardPadding),
            if (hasTitle) 0 else dp(tokens.metrics.cardTitlePaddingTop),
            dp(tokens.metrics.cardPadding),
            dp(tokens.metrics.cardPaddingBottom),
        )
        alpha = if (item.dim) tokens.metrics.dimAlpha else 1f
        cornerView.text = item.corner
        cornerView.visibility = if (item.corner.isEmpty()) View.GONE else View.VISIBLE
        badgeView.text = item.badge
        artworkTitleView.text = item.artworkTitle
        artworkTitleView.visibility = if (item.artworkTitle.isEmpty()) View.GONE else View.VISIBLE
        // 文字块高度随"有没有标题"变了：夹取过的封面要按新口径重算一次
        applyHeightLimit()
        coverUrl = item.coverUrl
        if (coverUrl.isEmpty()) image.setImageDrawable(null) else syncCover()
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
    )

    /**
     * 卡片底色、白框与文字色。
     *
     * - **焦点 = 白底深字**：封面贴边，白色底只露在封面下面那条信息区——
     *   视觉上就是"封面下层的背景变白、文字变黑"。全屏唯一的白色填充就是这里。
     * - **白框画在前景**：封面铺满上半部分，画在背景上的描边会被图整条盖住。
     * - **选中（当前季）= 白框**，不填充：白色填充只留给光标，否则一页会有两块白。
     */
    private fun paintCard(isFocused: Boolean) {
        val chosen = isSelected
        val fill = if (isFocused) tokens.colors.focus else tokens.colors.surface
        // 背景只负责底色（会被封面盖住大半），框交给前景
        background = shape(fill, tokens.shapes.card, null)
        foreground = if (isFocused || chosen) {
            shape(android.graphics.Color.TRANSPARENT, tokens.shapes.card, Stroke(tokens.colors.focus, tokens.shapes.stroke))
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
        badgeView.setTypeface(null, if (attention || chosen) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * 封面以下那块文字在竖直方向占多少：夹取封面高度时要把它先扣掉，
     * 否则"标题 + 角标"又会被推到视口外。标题行收起时只算角标。
     */
    private fun textBlockHeight(): Int {
        val lineHeight = (titleView.paint.fontMetrics.descent - titleView.paint.fontMetrics.ascent).toInt()
        val hasTitle = item?.title?.isNotEmpty() == true
        // 两种情况都要先让开封面与文字之间那一档：有标题时是标题的上内边距，没有时是角标的
        val gap = dp(tokens.metrics.cardTitlePaddingTop)
        val titlePart = if (hasTitle) lineHeight else 0
        return gap + titlePart + dp(tokens.metrics.badgeHeight) + dp(tokens.metrics.cardPaddingBottom)
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
        /**
         * 标题只占一行。
         *
         * 目录类页面压根不传标题（片名由预览区说）；传标题的只有季卡，内容是「第九季」这种
         * 一眼读完的短词。占两行会在标题与角标之间留一整行空白（维护者实测"上下两块被拉开了"）。
         */
        const val TITLE_LINES = 1
    }
}
