package com.hongguotv.lite.feature.player

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.UiKit

/** 提示卡上能按的动作。卡片只决定"什么时候显示哪一个"，做什么由页面执行。 */
internal enum class NoticeAction { RETRY, LOWER_QUALITY, STOP_RETRY, REPLAY, NEXT, RESUME, BACK }

/**
 * 播放页的提示层（与抽屉同构）：
 *
 * ```
 * ┌──────────────────────────────────────────┐
 * │                                          │
 * │        错误大字（26sp，画面中央）            │
 * │                                          │
 * │  ╭────────────────────────────────────╮  │
 * │  │ [立即重试] [尝试更低] [返回选集]        │  │ ← 胶囊动作排
 * │  │ ────────────────────────────────── │  │ ← 1px 结构线
 * │  │ 剧名 · 第 3 集          失败原因     │  │ ← 46dp 信息栏行
 * │  ╰────────────────────────────────────╯  │
 * └──────────────────────────────────────────┘
 * ```
 *
 * 旧实现是"深色小卡片 + 一排无底色按钮"：按钮压在同色 tile 上只剩文字，四行内容
 * 挤在左下角，与 B 方案的语言完全不是一套。现在错误本身成为主视觉，动作与说明
 * 收进贴底的抽屉式面板——键帽、胶囊、信息栏都是既有控件的复用。
 *
 * 单独成类的原因不变：它有一套自己的状态映射（[PlaybackState] + [RetryState] → 文案 + 按钮集合）、
 * 自己的焦点结构，和"按键路由 / 取流 / 写进度"没有共同的修改理由。
 */
internal class PlayerNotice(
    private val ui: UiKit,
    private val onAction: (NoticeAction) -> Unit,
) {

    private val tokens: Tokens = ui.tokens

    /** 画面中央的大字（错误 / 结束 / 定时停止）。一律正文色——红字在深色画面上像系统报错。 */
    private val headlineView: TextView = ui.text("", tokens.type.display).apply {
        gravity = Gravity.CENTER
        maxLines = 3
        // 大字不贴边：与画面左右各留一页边距
        setPadding(ui.dp(tokens.metrics.pagePaddingH), 0, ui.dp(tokens.metrics.pagePaddingH), 0)
    }

    private val titleView: TextView = ui.text("", tokens.type.specValue).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTypeface(null, android.graphics.Typeface.BOLD)
    }

    /** 失败原因（muted 小字，贴右）：解码解释、"渠道没下发地址"这类与主句不同的话。 */
    private val reasonView: TextView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private val actionRow: LinearLayout = ui.row().apply {
        gravity = Gravity.START
    }
    private val buttons = LinkedHashMap<NoticeAction, TvButton>()

    /** 贴底面板：胶囊动作 + 分隔线 + 信息栏行。画法与播放抽屉一致（surface 底、圆角 16）。 */
    val view: LinearLayout = ui.tile(radiusDp = tokens.shapes.drawer, paddingDp = tokens.space.m).apply {
        visibility = View.GONE
        addView(actionRow)
        addView(
            View(context).apply { setBackgroundColor(tokens.colors.line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(tokens.space.hairline)).apply {
                topMargin = ui.dp(tokens.space.m)
                bottomMargin = ui.dp(tokens.space.xs)
            },
        )
        addView(
            infoRow(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(tokens.metrics.infoBandHeight)),
        )
    }

    /** 信息栏行：片名（左，白粗）+ 失败原因（右，muted）。 */
    private fun infoRow(): LinearLayout = ui.row().apply {
        addView(titleView, ui.params(width = 0, weight = 1f, rightGap = tokens.space.l))
        addView(reasonView, ui.params(width = 0, weight = 1f))
    }

    /** 有可见按钮时，方向键要交回焦点系统——否则按钮永远按不到。 */
    val actionable: Boolean
        get() = view.visibility == View.VISIBLE && buttons.values.any { it.visibility == View.VISIBLE }

    init {
        // 顺序即视觉顺序：先"继续看下去"的动作，再"离开"
        action(NoticeAction.RETRY, "立即重试")
        action(NoticeAction.LOWER_QUALITY, "尝试更低")
        action(NoticeAction.STOP_RETRY, "停止自动重试")
        action(NoticeAction.REPLAY, "重播本集")
        action(NoticeAction.NEXT, "下一集")
        action(NoticeAction.RESUME, "继续播放")
        action(NoticeAction.BACK, "返回选集")
    }

    private fun action(action: NoticeAction, label: String) {
        val button = ui.button(label) { onAction(action) }
        buttons[action] = button
        actionRow.addView(button, ui.params(rightGap = tokens.space.s))
    }

    /** 挂到播放页上：大字居中、面板贴底（左右与底部留白与抽屉同款）。 */
    fun attachTo(root: FrameLayout) {
        root.addView(
            headlineView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        val marginH = ui.dp(tokens.metrics.drawerPaddingH)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                leftMargin = marginH
                rightMargin = marginH
                bottomMargin = ui.dp(tokens.metrics.overlayBottomGap)
            },
        )
    }

    /**
     * 按当前状态重画。
     * @param title 信息栏左侧的"这部剧"（`剧名 · 第 x 集`）；详情还没取回时是空串
     * @param loadFailure 剧集信息没取回来时的失败（与播放失败是两条路，文案也不同）
     * @param sidebarOpen 侧栏开着时提示层整体让位：两层浮块叠在一起，用户不知道焦点在谁身上
     * @param lowerQuality 比当前低一档的清晰度；null = 已经最低，不显示"尝试更低"
     */
    fun render(
        state: PlaybackState,
        loadFailure: Throwable?,
        sidebarOpen: Boolean,
        lowerQuality: Int?,
        title: String,
    ) {
        val headline = loadFailure?.let { TvText.error(it) } ?: PlayerText.notice(state)
        if (headline == null || sidebarOpen) {
            view.visibility = View.GONE
            headlineView.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        headlineView.visibility = View.VISIBLE
        headlineView.text = headline
        // 正文色，不用红：电视上红字在深色画面里既刺眼又像"系统报错"（维护者口径：改白字）
        headlineView.setTextColor(tokens.colors.text)
        titleView.text = title
        // 原因只在"主句没说清"时给：解码失败解释为什么换清晰度有用，渠道问题说清不是用户的网
        val reason = if (loadFailure == null) (state as? PlaybackState.Failed)?.let { PlayerText.failureHint(it.failure) } else null
        reasonView.text = reason.orEmpty()
        reasonView.visibility = if (reason == null) View.INVISIBLE else View.VISIBLE

        val wanted = wantedActions(state, loadFailure, lowerQuality)
        buttons.forEach { (action, button) -> button.visibility = if (action in wanted) View.VISIBLE else View.GONE }
        if (lowerQuality != null) buttons[NoticeAction.LOWER_QUALITY]?.label("尝试 ${TvText.quality(lowerQuality)}")
    }

    private fun wantedActions(state: PlaybackState, loadFailure: Throwable?, lowerQuality: Int?): List<NoticeAction> = when {
        loadFailure != null -> listOf(NoticeAction.RETRY, NoticeAction.BACK)
        state is PlaybackState.Failed -> buildList {
            add(NoticeAction.RETRY)
            if (lowerQuality != null) add(NoticeAction.LOWER_QUALITY)
            if (state.retry is RetryState.Scheduled) add(NoticeAction.STOP_RETRY)
            add(NoticeAction.BACK)
        }
        state is PlaybackState.Ended -> buildList {
            add(NoticeAction.REPLAY)
            if (!state.lastOfSeason) add(NoticeAction.NEXT)
            add(NoticeAction.BACK)
        }
        state is PlaybackState.Playing && state.pausedBy == PauseReason.SLEEP -> listOf(NoticeAction.RESUME)
        else -> emptyList()
    }

    /** 焦点结构的指纹：可见按钮集合变了才重提焦点图。 */
    fun signature(): String = buttons.filterValues { it.visibility == View.VISIBLE }.keys.joinToString(",")

    fun focusBinding(preferred: FocusKey? = null): FocusBinding? {
        val visible = buttons.filterValues { it.visibility == View.VISIBLE }
        if (visible.isEmpty()) return null
        val views = LinkedHashMap<FocusKey, View>()
        val keys = visible.keys.map { action ->
            val key = focusKey(action)
            views[key] = buttons.getValue(action)
            key
        }
        val section = FocusSections.section(keys, keys.size) ?: return null
        val plan = FocusSections.plan(listOf(section)) ?: return null
        return FocusBinding(plan, views, preferred ?: keys.first())
    }

    private fun focusKey(action: NoticeAction): FocusKey = FocusKey("notice:${action.name}")
}
