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
 * 播放页底部那张提示卡：出错 / 本集结束 / 定时停止时出现，其余时候**必须消失**
 * （画面底下常驻一块无关的板子，比没有提示更糟）。
 *
 * 单独成类的原因：它有一套自己的状态映射（[PlaybackState] + [RetryState] → 文案 + 按钮集合）、
 * 自己的焦点结构，和"按键路由 / 取流 / 写进度"没有共同的修改理由。留在 `PlayerPage` 里，
 * 那个文件就会因为四件互不相干的事被改（S6 review 指出的 Divergent Change）。
 */
internal class PlayerNotice(
    private val ui: UiKit,
    private val onAction: (NoticeAction) -> Unit,
) {

    private val tokens: Tokens = ui.tokens
    private val textView: TextView = ui.text("", tokens.type.body).apply { maxLines = 3 }
    private val hintView: TextView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
        setPadding(0, ui.dp(tokens.space.xs), 0, 0)
    }
    private val actionRow: LinearLayout = ui.row().apply { setPadding(0, ui.dp(tokens.space.m), 0, 0) }
    private val buttons = LinkedHashMap<NoticeAction, TvButton>()

    val view: LinearLayout = ui.tile(radiusDp = tokens.shapes.card, paddingDp = tokens.space.l).apply {
        visibility = View.GONE
        addView(textView)
        addView(hintView)
        addView(actionRow)
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

    /** 挂到播放页上。宽度按屏宽比例，贴底居中。 */
    fun attachTo(root: FrameLayout) {
        val width = (ui.context.resources.displayMetrics.widthPixels * tokens.metrics.noticeWidthRatio).toInt()
        root.addView(
            view,
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = ui.dp(tokens.metrics.noticeBottomMargin)
            },
        )
    }

    /**
     * 按当前状态重画。
     * @param loadFailure 剧集信息没取回来时的失败（与播放失败是两条路，文案也不同）
     * @param sidebarOpen 侧栏开着时提示卡让位：两层浮块叠在一起，用户不知道焦点在谁身上
     * @param lowerQuality 比当前低一档的清晰度；null = 已经最低，不显示"尝试更低"
     */
    fun render(state: PlaybackState, loadFailure: Throwable?, sidebarOpen: Boolean, lowerQuality: Int?) {
        val text = loadFailure?.let { TvText.error(it) } ?: PlayerText.notice(state)
        if (text == null || sidebarOpen) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        textView.text = text
        val hint = if (loadFailure == null) (state as? PlaybackState.Failed)?.let { PlayerText.failureHint(it.failure) } else null
        hintView.visibility = if (hint == null) View.GONE else View.VISIBLE
        hintView.text = hint.orEmpty()

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
