package com.hongguotv.lite.feature.detail

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.TvCover
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 详情类两页的头部信息块：封面 + 标题 + 元信息 + 状态行 + 简介 + 动作行。
 *
 * 为什么单独成一个块：剧集详情与收藏系列页的头部**是同一个东西**，
 * 各写一遍的第一天就会开始漂（一边改间距、一边忘）。动作按钮仍由页面自己塞进 [actions]，
 * 因为两页的动作不一样。
 *
 * 这里的字都是调用方给的字符串——不认"季""季线"这些概念（词汇在 feature 与 `CONTEXT.md`）。
 */
internal class HeroBlock(private val ui: UiKit) {

    private val tokens: Tokens = ui.tokens
    private val cover: TvCover = ui.cover(tokens.metrics.heroCoverWidth, tokens.metrics.heroCoverHeight)
    private val titleView: TextView = ui.text("", tokens.type.display, tokens.colors.text).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTypeface(null, Typeface.BOLD)
    }
    private val metaView: TextView = ui.text("", tokens.type.label, tokens.colors.muted).apply {
        setPadding(0, ui.dp(tokens.space.s), 0, 0)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val statusView: TextView = ui.text("", tokens.type.label, tokens.colors.text).apply {
        setPadding(0, ui.dp(tokens.space.xs), 0, 0)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private val introText: TextView = ui.text("", tokens.type.body, tokens.colors.muted).apply {
        val pad = ui.dp(tokens.space.s)
        setPadding(pad, pad, pad, pad)
        setLineSpacing(0f, tokens.type.lineSpacing)
    }

    /**
     * 简介是一个**可聚焦的滚动框**：光标落上来之后按上下键就能读完整简介，
     * 不依赖"完整简介"按钮把行数撑大（遥控器上一格格翻按钮太绕，维护者口径）。
     *
     * 用 `ScrollView` 而不是 TextView + `ScrollingMovementMethod`：实测后者不消费
     * 遥控器的方向键（按一下焦点就飞走），而 ScrollView 的方向键滚动是原生能力，
     * 滚到顶/底再把键交回焦点系统。焦点画在框上（提亮一档底色，不是白底深字——
     * 这里是大段正文，白底太刺眼），只表达"简介现在归你滚"。
     *
     * **框底常驻 + `clipToOutline`**：嵌套在外层滚动区里时，`clipChildren` 挡不住
     * 超出高度的正文——实测 92dp 的框把 289dp 的文字整段画到了分集格上（截图比对过）。
     * 靠轮廓裁切（background + clipToOutline）才真的截住，顺带让"这里可以滚"看得出来。
     */
    private val introView: ScrollView = ScrollView(ui.context).apply {
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        isFocusable = true
        isFocusableInTouchMode = true
        background = boxBackground(tokens.colors.surface)
        clipToOutline = true
        addView(introText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnFocusChangeListener { _, focused ->
            background = boxBackground(if (focused) tokens.colors.raised else tokens.colors.surface)
            introText.setTextColor(if (focused) tokens.colors.text else tokens.colors.muted)
        }
    }

    private fun boxBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ui.dp(tokens.shapes.small).toFloat()
    }

    /** 动作按钮的容器，由页面往里塞自己的按钮。 */
    val actions: LinearLayout = ui.row().apply { setPadding(0, ui.dp(tokens.space.m), 0, 0) }

    /**
     * 简介滚动框本体。页面把它加进焦点图（动作行的下一列）：光标从「完整简介」右移一格
     * 就能落进来，上下键滚动，滚到顶/底框架自动把键交回焦点图（离开简介）。
     */
    val introBox: View get() = introView

    /** 右栏（简介）。空简介时整栏 GONE，左栏拿回全部宽度。 */
    private val introColumn: LinearLayout = ui.column()

    val view: LinearLayout = ui.rowTop().apply {
        visibility = View.GONE
        setPadding(0, ui.dp(tokens.space.m), 0, ui.dp(tokens.space.m))
        addView(
            cover,
            ui.params(
                width = ui.dp(tokens.metrics.heroCoverWidth),
                height = ui.dp(tokens.metrics.heroCoverHeight),
                rightGap = tokens.metrics.heroCoverGap,
            ),
        )
        // 左栏：片名 + 元信息 + 上次观看 + 动作。这一栏决定头部高度。
        val info = ui.column()
        addView(info, ui.params(width = 0, weight = INFO_WEIGHT))
        info.addView(titleView)
        info.addView(metaView)
        info.addView(statusView)
        info.addView(actions)

        // 右栏：简介。放在右侧的空白里（片名通常只占半行），简介就不会再把动作行往下推——
        // 一屏就多出一行分集格/季卡的高度。
        addView(
            introColumn,
            ui.params(width = 0, weight = INTRO_WEIGHT).apply { leftMargin = ui.dp(tokens.metrics.heroIntroGap) },
        )
        introColumn.addView(introView, ui.params(height = ui.dp(tokens.metrics.introMaxHeight)))
    }

    /** 状态行与简介为空时整行隐藏：留着空行只会把下面的内容推远。 */
    fun show(coverUrl: String, title: String, meta: String, status: String, intro: String, introExpanded: Boolean) {
        cover.show(coverUrl)
        titleView.text = title
        metaView.text = meta
        statusView.text = status
        statusView.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
        introText.text = intro
        introColumn.visibility = if (intro.isBlank()) View.GONE else View.VISIBLE
        expandIntro(introExpanded)
    }

    /** 收起/展开只改高度：文字始终完整在布局里，滚动才有得滚。 */
    fun expandIntro(expanded: Boolean) {
        introView.layoutParams = (introView.layoutParams as LinearLayout.LayoutParams).apply {
            height = ui.dp(if (expanded) tokens.metrics.introExpandedHeight else tokens.metrics.introMaxHeight)
        }
    }

    private companion object {
        /** 左栏（信息）与右栏（简介）的宽度比：简介要读得进去，但不能挤到片名。 */
        const val INFO_WEIGHT = 1.6f
        const val INTRO_WEIGHT = 1f
    }
}
