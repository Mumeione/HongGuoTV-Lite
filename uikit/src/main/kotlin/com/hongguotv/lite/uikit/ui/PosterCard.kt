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
import com.hongguotv.lite.uikit.internal.cardShape
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
)

/**
 * 海报卡：封面 + 两行标题 + 一行角标。
 *
 * 标题固定两行（`minLines = 2`），光标在行内移动时下面的内容不会被撑开——
 * 这是旧版验证过的取舍，不是疏忽。
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
        val pad = dp(tokens.metrics.cardPadding)
        setPadding(pad, pad, pad, dp(tokens.metrics.cardGap - tokens.metrics.cardPadding))

        artworkWidth = widthPx - dp(tokens.metrics.cardArtworkInset)
        artworkHeight = 1
        artworkView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, artworkHeight)
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
        titleView.includeFontPadding = false
        titleView.maxLines = TITLE_LINES
        titleView.minLines = TITLE_LINES
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.setPadding(dp(tokens.space.xxs), dp(tokens.metrics.cardTitlePaddingTop), dp(tokens.space.xxs), 0)

        badgeView.textSize = tokens.type.caption
        badgeView.includeFontPadding = false
        badgeView.maxLines = 1
        badgeView.minLines = 1
        badgeView.minHeight = dp(tokens.metrics.badgeHeight)
        badgeView.ellipsize = TextUtils.TruncateAt.END
        badgeView.setPadding(dp(tokens.space.xxs), 0, 0, 0)
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
        alpha = if (item.dim) tokens.metrics.dimAlpha else 1f
        cornerView.text = item.corner
        cornerView.visibility = if (item.corner.isEmpty()) View.GONE else View.VISIBLE
        badgeView.text = item.badge
        badgeView.setTextColor(if (item.badgeAttention) tokens.colors.focus else tokens.colors.muted)
        badgeView.setTypeface(null, if (item.badgeAttention) Typeface.BOLD else Typeface.NORMAL)
        coverUrl = item.coverUrl
        if (coverUrl.isEmpty()) image.setImageDrawable(null) else syncCover()
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
        val next = artworkHeightFor(maxHeightPx)
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

    /** 焦点是白描边（卡面是图，白底会把图盖掉）；选中态卡片不用，见 `cardShape` 的注释。 */
    private fun paintCard(isFocused: Boolean) {
        val fill = if (isFocused) tokens.colors.raised else tokens.colors.surface
        background = cardShape(tokens, fill, tokens.shapes.card, focused = isFocused)
    }

    /**
     * 封面以下那块文字在竖直方向占多少：夹取封面高度时要把它先扣掉，
     * 否则"标题两行 + 角标"又会被推到视口外。
     */
    private fun textBlockHeight(): Int {
        val lineHeight = (titleView.paint.fontMetrics.descent - titleView.paint.fontMetrics.ascent).toInt()
        val padTop = dp(tokens.metrics.cardPadding)
        val padBottom = dp(tokens.metrics.cardGap - tokens.metrics.cardPadding)
        return padTop + dp(tokens.metrics.cardTitlePaddingTop) + lineHeight * TITLE_LINES +
            dp(tokens.metrics.badgeHeight) + padBottom
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
        const val TITLE_LINES = 2
    }
}
