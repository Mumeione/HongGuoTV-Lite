package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.port.ArtworkSpec
import com.hongguotv.lite.uikit.internal.bindFocus
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.stateShape

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
)

/**
 * 海报卡：封面 + 两行标题 + 一行角标。
 *
 * 标题固定两行（`minLines = 2`），光标在行内移动时下面的内容不会被撑开——
 * 这是旧版验证过的取舍，不是疏忽。
 */
@SuppressLint("ViewConstructor")
class PosterCard internal constructor(
    private val ui: UiKit,
    private val widthPx: Int,
) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    private val artworkView = FrameLayout(ui.context)
    private val image = ImageView(ui.context)
    private val titleView = TextView(ui.context)
    private val badgeView = TextView(ui.context)

    private val artworkHeight: Int
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

        artworkHeight = ((widthPx - dp(tokens.metrics.cardArtworkInset)) / tokens.metrics.posterAspect).toInt()
        artworkView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, artworkHeight)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        artworkView.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        titleView.textSize = tokens.type.compact
        titleView.setTextColor(tokens.colors.text)
        titleView.includeFontPadding = false
        titleView.maxLines = 2
        titleView.minLines = 2
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

    private fun paintCard(isFocused: Boolean) {
        val fill = if (isFocused) tokens.colors.raised else tokens.colors.surface
        background = stateShape(tokens, fill, tokens.shapes.card, selected = false, focused = isFocused)
    }

    private fun syncCover() {
        val port = ui.artwork ?: return
        if (coverUrl.isEmpty()) return
        port.load(
            host = image,
            url = coverUrl,
            spec = ArtworkSpec(
                widthPx = widthPx - dp(tokens.metrics.cardArtworkInset),
                heightPx = artworkHeight,
                persist = coverPersist,
                priority = if (focused) ArtworkSpec.PRIORITY_FOCUSED else ArtworkSpec.PRIORITY_VISIBLE,
            ),
        )
    }
}
