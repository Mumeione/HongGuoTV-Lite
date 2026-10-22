package com.hongguotv.lite.feature.detail

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.SpecRow
import com.hongguotv.lite.uikit.ui.SpecTable
import com.hongguotv.lite.uikit.ui.TvCover
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 详情类两页的头部信息块（B 方案两行网格，mockup 画面 3/5 的 `.b-hero2`）：
 *
 * ```
 * ┌────────┬──────────────────────────┬──────────┐
 * │        │ 片名（26，一行省略）      │ 继续播放 │
 * │  封面  │ 共 10 季                 │ 选集     │
 * │  150   │ 更新 / 集数·季数 / 分类 / │ 已收藏   │ ← 动作列贴「记录」行底部
 * │  ×214  │ 记录（strong 白粗）      │          │
 * ├────────┴──────────────────────────┴──────────┤
 * │ 简介：最多两行，超出省略号（跨信息列与动作列）│
 * └─────────────────────────────────────────────┘
 * ```
 *
 * 与 S9 版的三处不同：
 * - **简介不再可滚**：它是"够不够判断要不要看"的信息，不是阅读区——两行封顶 + 省略号，
 *   「完整简介」按钮与"焦点进简介框上下滚"的交互随之取消；
 * - **动作列贴「记录」行底部**（`align-self:end`）：按钮不再是孤零零挂在最右的一列；
 * - 四行规格表回答"更新到哪、共几集、什么分类、我看到哪"，[SpecRow.strong] 高亮"记录"。
 *
 * 这里的字都是调用方给的字符串——不认"季""季线"这些概念（词汇在 feature 与 `CONTEXT.md`）。
 */
internal class HeroBlock(private val ui: UiKit) {

    private val tokens: Tokens = ui.tokens
    private val cover: TvCover = ui.cover(tokens.metrics.heroCoverWidth, tokens.metrics.heroCoverHeight)
    private val titleView: TextView = ui.text("", tokens.type.display, tokens.colors.text).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTypeface(null, Typeface.BOLD)
    }
    private val seasonView: TextView = ui.text("", tokens.type.seasonLine, tokens.colors.soft).apply {
        setPadding(0, ui.dp(tokens.space.xxs), 0, ui.dp(tokens.space.m))
    }
    private val specTable: SpecTable = ui.specTable()
    private val introText: TextView = ui.text("", tokens.type.cardStatus, tokens.colors.muted).apply {
        // 两行封顶：简介是"要不要看"的判断信息，不是阅读区（B 方案口径）
        maxLines = tokens.metrics.introMaxLines
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, tokens.type.lineSpacing)
        includeFontPadding = false
        setPadding(0, ui.dp(tokens.space.m), 0, 0)
    }

    /** 动作列（竖排胶囊，宽 172），由页面往里塞自己的按钮。 */
    val actions: LinearLayout = ui.column().apply { gravity = Gravity.START }

    val view: LinearLayout = ui.rowTop().apply {
        visibility = View.GONE
        setPadding(0, ui.dp(tokens.space.m), 0, 0)
        addView(
            cover,
            ui.params(
                width = ui.dp(tokens.metrics.heroCoverWidth),
                height = ui.dp(tokens.metrics.heroCoverHeight),
                rightGap = tokens.metrics.heroColumnGap,
            ),
        )
        // 封面右侧：上段（信息列 + 动作列）+ 简介。整列的高度由封面决定（214 > 文字块），
        // 简介正好落在封面下段的旁边——"封面与简介底部平齐"由这个高度关系近似实现。
        val right = ui.column()
        addView(right, ui.params(width = 0, weight = 1f))

        val topRow = ui.rowBottom()
        right.addView(topRow, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
        val info = ui.column()
        topRow.addView(info, ui.params(width = 0, weight = 1f))
        info.addView(titleView)
        info.addView(seasonView)
        info.addView(specTable, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
        topRow.addView(
            actions,
            ui.params(width = ui.dp(tokens.metrics.heroActionsWidth)).apply { leftMargin = ui.dp(tokens.metrics.heroColumnGap) },
        )
        right.addView(introText, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 装配头部。规格表整表重建（行数少），文字走增量更新。 */
    fun show(coverUrl: String, title: String, seasonNote: String, specs: List<SpecRow>, intro: String) {
        cover.show(coverUrl)
        titleView.text = title
        seasonView.text = seasonNote
        seasonView.visibility = if (seasonNote.isBlank()) View.GONE else View.VISIBLE
        specTable.show(specs)
        specTable.visibility = if (specs.isEmpty()) View.GONE else View.VISIBLE
        introText.text = intro
        introText.visibility = if (intro.isBlank()) View.GONE else View.VISIBLE
    }
}
