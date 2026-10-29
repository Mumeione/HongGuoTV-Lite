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
 * 这里的字都是调用方给的字符串——不认"季""季线"这些概念（词汇属于 feature）。
 */
internal class HeroBlock(private val ui: UiKit) {

    private val tokens: Tokens = ui.tokens
    private val cover: TvCover = ui.cover(tokens.metrics.heroCoverWidth, tokens.metrics.heroCoverHeight)

    /** 封面当前高度（像素）：与右侧信息列同步后记下来，避免重复改布局。 */
    private var coverHeightPx: Int = 0

    /** 右列（片名 / 副行 / 规格表 / 说明）：高度与封面互相约束，见 [syncCoverHeight]。 */
    private val rightColumn: LinearLayout = ui.column()
    private val rightParams: LinearLayout.LayoutParams = ui.params(width = 0, weight = 1f)

    /** 右列被固定到封面高之后才显形的弹性空格（见 [syncCoverHeight]）。 */
    private val flexGap: View = View(ui.context).apply { visibility = View.GONE }

    /** 右列被固定到的像素高（0 = 仍然 WRAP_CONTENT）。 */
    private var rightFixed: Int = 0

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
        // 片名 → 副行 → 规格表三段之间都要有缝（2026-10-24 验收第二次）：
        // 副行贴着标题、规格表又贴着副行时，三块读成一段；表格内部行距反而要收（见 specRowGap）。
        setPadding(0, ui.dp(tokens.space.xs), 0, ui.dp(tokens.space.m))
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
        // 封面高度与右列底边对齐（2026-10-24 验收两次）：
        // 内容比封面高 → 封面跟着长；内容比封面矮 → 右列固定到封面高、弹性空格把说明推到底，
        // 这样"各季进度分别保存…"那句的底边永远贴着封面底边。
        rightColumn.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> syncCoverHeight(bottom - top) }
        addView(rightColumn, rightParams)
        // 宽度给满、单行省略：超长片名不能把动作列顶出屏幕
        rightColumn.addView(titleView, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
        rightColumn.addView(seasonView, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 规格表与动作列同起一行：动作列顶部因此在副行之下（2026-10-24 口径）。
        // 动作列比规格表高，说明行自然落到封面底部附近，不再往上挤。
        val content = ui.rowTop()
        rightColumn.addView(content, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
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
        // 弹性空格：**默认 GONE**。它在右列被固定高度后才会显形——那时它的 weight
        // 吃掉"固定高 − 内容高"的差额，把说明推到最底部。
        // 一开始就让它可见的话，LinearLayout 在 AT_MOST 下会把整页剩余高度都分给它，
        // 右列被拉成一整屏、封面跟着变成一根长条（2026-10-25 维护者截图）。
        rightColumn.addView(flexGap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 说明收尾：它的底边就是右列的底边，封面按这个高度同步（见 syncCoverHeight）
        rightColumn.addView(introText, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 上一次交给 [show] 的内容指纹：完全一样就不再重量一遍（见 [show]）。 */
    private data class Shown(
        val coverUrl: String,
        val title: String,
        val seasonNote: String,
        val specs: List<SpecRow>,
        val intro: String,
    )

    private var shown: Shown? = null

    /**
     * 装配头部。规格表整表重建（行数少），文字走增量更新。
     *
     * **内容指纹没变就整段跳过**：每次 show 都会 `releaseRightHeight()` 让右列重新量高，
     * 量出来的高度再驱动封面换尺寸——反复 show 同一份内容就会反复下载封面（表现为"封面
     * 一直在闪"）。异步刷新（季线、分类）常常给的还是同一份内容，这一层短路把它们挡掉。
     */
    fun show(coverUrl: String, title: String, seasonNote: String, specs: List<SpecRow>, intro: String) {
        val next = Shown(coverUrl, title, seasonNote, specs, intro)
        if (next == shown) return
        shown = next
        cover.show(coverUrl)
        titleView.text = title
        seasonView.text = seasonNote
        seasonView.visibility = if (seasonNote.isBlank()) View.GONE else View.VISIBLE
        specTable.show(specs)
        specTable.visibility = if (specs.isEmpty()) View.GONE else View.VISIBLE
        introText.text = intro
        introText.visibility = if (intro.isBlank()) View.GONE else View.VISIBLE
        // 内容高变了：放开上一次的固定高，等布局回调重新量
        releaseRightHeight()
    }

    /**
     * 右列多高，封面就多高。两种情形：
     * - 内容比封面矮 → 把右列**固定**到封面高，并放出弹性空格（说明贴底）；
     * - 内容比封面高 → 封面跟着内容长，弹性空格保持隐藏。
     *
     * 空格必须先隐藏再量：可见的空格带 `weight=1`，会把父容器的可用高度吃满，
     * 量出来的"内容高"其实是一整屏（封面跟着变长条的 bug 就这么来的）。
     */
    private fun syncCoverHeight(rightHeight: Int) {
        if (rightHeight <= 0 || rightFixed > 0) return
        val minCover = ui.dp(tokens.metrics.heroCoverHeight)
        val target = maxOf(minCover, rightHeight)
        if (target > rightHeight) {
            rightFixed = target
            rightParams.height = target
            flexGap.visibility = View.VISIBLE
            rightColumn.requestLayout()
        }
        if (coverHeightPx == target) return
        coverHeightPx = target
        cover.heightPx(target)
    }

    /** 放开固定高（下一次 show 之后重新量）。 */
    private fun releaseRightHeight() {
        if (rightFixed == 0) return
        rightFixed = 0
        rightParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        flexGap.visibility = View.GONE
        rightColumn.requestLayout()
    }
}
