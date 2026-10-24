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
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 详情类两页的头部信息块（B 方案网格，2026-10-24 验收后收紧）。
 * 两页共用同一套结构：片名 / 副行 / （规格表 + 动作列）/ 说明。
 *
 * ```
 * ┌────────┬────────────────────────────┬──────────┐
 * │        │ 片名（26，一行省略）        │          │
 * │  封面  │ 共 10 季 · 9 季已上线       │ 继续播放 │ ← 顶部在副行之下，不和片名同行
 * │  150   │ 更新 / 季数 / 分类 / 记录   │ 选集     │
 * │  ×214  │ （行距 12）                 │ 取消收藏 │
 * │        │ 说明（一行，贴到与封面底平） │          │
 * └────────┴────────────────────────────┴──────────┘
 * ```
 *
 * 与上一版的三处不同（都是维护者逐条验收出来的）：
 * - **动作列下移**：它和规格表同起一行，顶部不再与片名平齐——片名是"这是什么"，
 *   动作是"我能做什么"，两者挤在一行读起来像一组；
 * - **动作列右侧留白**：三个按钮整体左移一档，不再贴内容区右缘；
 * - **说明行贴着封面底**：动作列高 3×40 + 2×8 = 136，加上片名/副行/说明约 213，
 *   与 214 的封面自然平齐；收藏页的说明句本身就是一行（没有换行），不需要额外截断。
 *
 * 这里的字都是调用方给的字符串——不认"季""季线"这些概念（词汇在 feature 与 `CONTEXT.md`）。
 */
internal class HeroBlock(private val ui: UiKit) {

    private val tokens: Tokens = ui.tokens
    private val cover: TvCover = ui.cover(tokens.metrics.heroCoverWidth, tokens.metrics.heroCoverHeight)

    /** 封面当前高度（像素）：与右侧信息列同步后记下来，避免重复改布局。 */
    private var coverHeightPx: Int = 0

    /** 封面的布局参数：高度会随右侧信息列改写（简介底边要贴封面底边）。 */
    private val coverParams: LinearLayout.LayoutParams = ui.params(
        width = ui.dp(tokens.metrics.heroCoverWidth),
        height = ui.dp(tokens.metrics.heroCoverHeight),
        rightGap = tokens.metrics.heroColumnGap,
    )
    private val titleView: TextView = ui.text("", tokens.type.display, tokens.colors.text).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTypeface(null, Typeface.BOLD)
    }
    private val seasonView: TextView = ui.text("", tokens.type.seasonLine, tokens.colors.soft).apply {
        // 副行到规格表只留一档：动作列顶部要落在副行之下，入口的那点留白越紧越像一组表
        setPadding(0, ui.dp(tokens.space.xxs), 0, ui.dp(tokens.space.xs))
    }
    private val specTable: SpecTable = ui.specTable()
    private val introText: TextView = ui.text("", tokens.type.cardStatus, tokens.colors.muted).apply {
        // 简介是"要不要看"的判断信息，不是阅读区；两行封顶，超出省略号
        maxLines = tokens.metrics.introMaxLines
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, tokens.type.lineSpacing)
        includeFontPadding = false
        setPadding(0, ui.dp(tokens.space.s), 0, 0)
    }

    /** 动作列（竖排胶囊），由页面往里塞自己的按钮。 */
    val actions: LinearLayout = ui.column().apply { gravity = Gravity.START }

    /**
     * 往动作列加一个胶囊。高度在这里统一收口（[Tokens.metrics.heroActionHeight]）：
     * 三个键竖排，40dp 的常规胶囊会把整列拉到 136dp，比右边的封面还高（2026-10-24 验收）。
     *
     * [last] 的那一只不留底距——列底就是页面的留白，再垫一档会让简介没法贴封面底。
     */
    fun addAction(button: TvButton, last: Boolean = false) {
        button.minHeight = ui.dp(tokens.metrics.heroActionHeight)
        actions.addView(
            button,
            ui.params(
                width = ViewGroup.LayoutParams.MATCH_PARENT,
                bottomGap = if (last) 0 else tokens.metrics.heroActionGap,
            ),
        )
    }

    val view: LinearLayout = ui.rowTop().apply {
        visibility = View.GONE
        setPadding(0, ui.dp(tokens.space.m), 0, 0)
        addView(cover, coverParams)
        // 封面高度**跟随右侧信息列**（2026-10-24 验收：简介底边要与封面底边平齐）。
        // 下限仍是 214（0.7 比例）：单季剧规格行少时封面不会被拉成瘦长条；
        // 信息列更高时封面跟着长，底下不再空一截。
        val right = ui.column()
        right.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> syncCoverHeight(bottom - top) }
        addView(right, ui.params(width = 0, weight = 1f))
        // 宽度给满、单行省略：超长片名不能把动作列顶出屏幕
        right.addView(titleView, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
        right.addView(seasonView, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 规格表与动作列同起一行：动作列顶部因此在副行之下（2026-10-24 口径）。
        // 动作列比规格表高，说明行自然落到封面底部附近，不再往上挤。
        val content = ui.rowTop()
        right.addView(content, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
        val info = ui.column()
        content.addView(info, ui.params(width = 0, weight = 1f))
        info.addView(specTable, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(
            actions,
            ui.params(
                width = ui.dp(tokens.metrics.heroActionsWidth),
                rightGap = tokens.metrics.heroActionRightInset,
            ).apply {
                // 信息列与按钮列之间留一档：长分类值不贴着胶囊（按钮的左右位置由右侧留白决定）
                leftMargin = ui.dp(tokens.metrics.heroColumnGap)
            },
        )
        // 简介收尾：它的底边就是右列的底边，封面按这个高度同步（见 syncCoverHeight）
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

    /** 右侧信息列多高，封面就多高（下限 214）。宽度不变，图按 CENTER_CROP 裁。 */
    private fun syncCoverHeight(rightHeight: Int) {
        if (rightHeight <= 0) return
        val target = maxOf(ui.dp(tokens.metrics.heroCoverHeight), rightHeight)
        if (coverHeightPx == target) return
        coverHeightPx = target
        cover.heightPx(target)
    }
}
