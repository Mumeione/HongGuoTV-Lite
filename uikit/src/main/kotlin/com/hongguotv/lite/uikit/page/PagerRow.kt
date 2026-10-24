package com.hongguotv.lite.uikit.page

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 分组翻页行：`‹ 上一组 | 第 1—100 集 | 下一组 ›`。分集、季线、播放侧栏都用它，只是单位不同。
 *
 * B 方案：两个翻组键是**小号胶囊**（32dp），范围文字 13sp muted。
 *
 * 不可用的翻组键**保留位置但禁用**：隐藏会让中间的范围文字左右跳动；
 * 禁用的视图本来就拿不到焦点，而调用方只把可用的那个放进焦点图——不会出现"按进去出不来"。
 *
 * 从 `feature-detail` 上提到 uikit 的原因：S6 的播放侧栏是第二个消费者，
 * 两份实现必然在"到底保留还是隐藏禁用键"上分家。
 */
class PagerRow(
    ui: UiKit,
    prevLabel: String = "‹ 上一组",
    nextLabel: String = "下一组 ›",
    /**
     * 是否撑满一行。`false` 用于"翻组与标题同行"的场合（季线页空间紧张）：
     * 这时范围文字不能吃 weight，否则父行按 wrap 测量时它会算成 0 宽。
     */
    fillWidth: Boolean = true,
) {

    private val tokens: Tokens = ui.tokens
    private val rangeView: TextView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
        gravity = Gravity.CENTER
        maxLines = 1
    }

    val prev: TvButton = ui.button(prevLabel, variant = ButtonVariant.SMALL) { onPage?.invoke(-1) }
    val next: TvButton = ui.button(nextLabel, variant = ButtonVariant.SMALL) { onPage?.invoke(1) }

    /** 翻组回调：-1 = 上一组，1 = 下一组。 */
    var onPage: ((Int) -> Unit)? = null

    val view: LinearLayout = ui.row().apply {
        visibility = View.GONE
        // 上下留白只要一档：翻组是辅助控件，不能比它所翻的内容更占地方
        setPadding(0, ui.dp(tokens.space.xs), 0, ui.dp(tokens.space.xs))
        addView(prev)
        if (fillWidth) {
            addView(rangeView, ui.params(width = 0, weight = 1f))
        } else {
            // 固定最小宽度：可用/不可用两种状态之间文字长度不同，不留宽就会左右抖
            rangeView.minWidth = ui.dp(RANGE_MIN_WIDTH)
            addView(rangeView)
        }
        addView(next)
    }

    /** @param hasPrev/hasNext 决定哪个键可用；范围文字由调用方给（集 / 季 的单位不同）。 */
    fun show(rangeText: String, hasPrev: Boolean, hasNext: Boolean) {
        view.visibility = View.VISIBLE
        rangeView.text = rangeText
        enable(prev, hasPrev)
        enable(next, hasNext)
    }

    fun hide() {
        view.visibility = View.GONE
    }

    /**
     * 本行在焦点图里的那一段：**禁用的那一侧保留空槽**。
     *
     * 三处调用（搜索翻页、分集分组、季线分组）原本各抄一遍"启用才放进 keys"，
     * 结果少一项就让整行左移，往下映射到卡片时正好错位一张——统一在这里，
     * 空槽的存在由 [FocusSections.slots] 表达。
     *
     * @param views 页面的 key → View 表，会把可用的那两个键填进去。
     */
    fun focusSection(views: MutableMap<FocusKey, View>, prevKey: FocusKey, nextKey: FocusKey): FocusSection? {
        if (view.visibility != View.VISIBLE) return null
        if (prev.isEnabled) views[prevKey] = prev
        if (next.isEnabled) views[nextKey] = next
        return FocusSections.slots(listOf(prevKey.takeIf { prev.isEnabled }, nextKey.takeIf { next.isEnabled }))
    }

    private fun enable(button: TvButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
        const val RANGE_MIN_WIDTH = 104
    }
}
