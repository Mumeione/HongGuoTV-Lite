package com.hongguotv.lite.feature.detail

import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
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
    private val introView: TextView = ui.text("", tokens.type.body, tokens.colors.muted).apply {
        setPadding(0, ui.dp(tokens.space.s), 0, 0)
        maxLines = INTRO_COLLAPSED_LINES
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, tokens.type.lineSpacing)
    }

    /** 动作按钮的容器，由页面往里塞自己的按钮。 */
    val actions: LinearLayout = ui.row().apply { setPadding(0, ui.dp(tokens.space.m), 0, 0) }

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
        introColumn.addView(introView)
    }

    /** 状态行与简介为空时整行隐藏：留着空行只会把下面的内容推远。 */
    fun show(coverUrl: String, title: String, meta: String, status: String, intro: String, introExpanded: Boolean) {
        cover.show(coverUrl)
        titleView.text = title
        metaView.text = meta
        statusView.text = status
        statusView.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
        introView.text = intro
        introColumn.visibility = if (intro.isBlank()) View.GONE else View.VISIBLE
        expandIntro(introExpanded)
    }

    fun expandIntro(expanded: Boolean) {
        introView.maxLines = if (expanded) INTRO_EXPANDED_LINES else INTRO_COLLAPSED_LINES
    }

    private companion object {
        /** 左栏（信息）与右栏（简介）的宽度比：简介要读得进去，但不能挤到片名。 */
        const val INFO_WEIGHT = 1.6f
        const val INTRO_WEIGHT = 1f

        const val INTRO_COLLAPSED_LINES = 4
        const val INTRO_EXPANDED_LINES = 20
    }
}
