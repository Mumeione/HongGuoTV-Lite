package com.hongguotv.lite.uikit.page

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 分组侧翼长条（2026-10-24 口径，取代顶行的「上一组/下一组」小胶囊）：
 *
 * ```
 * ╭─╮ ┌──────────────────────────┐ ╭─╮
 * │‹│ │   分集格 / 季线卡（内容）    │ │›│
 * ╰─╯ └──────────────────────────┘ ╰─╯
 * ```
 *
 * 两条**竖向长条**贴在内容两侧，高随内容撑满：比顶行小胶囊更直观——按左/右出格
 * 就撞到条上，按一下翻一组。不可用的那侧禁用压暗（保留占位），单页时整对隐藏。
 *
 * 焦点上它们是 [com.hongguotv.lite.uikit.focus.FocusPlan] 的左右侧翼段：
 * 只有页面声明为 `flanked` 的内容段与它们互通，动作列等其他段不受影响。
 */
class SideStrips(
    private val ui: UiKit,
    private val prevKey: FocusKey,
    private val nextKey: FocusKey,
    onPage: (Int) -> Unit,
) {

    private val tokens: Tokens = ui.tokens
    val prev: TvButton = ui.button("‹", variant = ButtonVariant.STANDARD) { onPage(-1) }
    val next: TvButton = ui.button("›", variant = ButtonVariant.STANDARD) { onPage(1) }

    /** 把 [content] 夹进两条长条之间；条宽 [STRIP_WIDTH_DP]，高随内容撑满。 */
    fun wrap(content: View): LinearLayout = LinearLayout(ui.context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            prev,
            LinearLayout.LayoutParams(ui.dp(tokens.metrics.sideStripWidth), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                rightMargin = ui.dp(tokens.space.s)
            },
        )
        addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            next,
            LinearLayout.LayoutParams(ui.dp(tokens.metrics.sideStripWidth), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = ui.dp(tokens.space.s)
            },
        )
    }

    /**
     * @param visible false = 单页，整对隐藏（内容占满整行）
     * @param hasPrev/hasNext 决定哪一侧可用；不可用侧禁用压暗但保留占位
     */
    fun show(visible: Boolean, hasPrev: Boolean, hasNext: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        prev.visibility = visibility
        next.visibility = visibility
        enable(prev, hasPrev)
        enable(next, hasNext)
    }

    /** 左条在焦点图里的侧翼段；不可用（禁用/隐藏）时整段省略，内容行缘夹住自己。 */
    fun leftSection(): FocusSection? =
        FocusSections.slots(listOf(prevKey.takeIf { usable(prev) }))

    /** 右条在焦点图里的侧翼段。 */
    fun rightSection(): FocusSection? =
        FocusSections.slots(listOf(nextKey.takeIf { usable(next) }))

    private fun usable(button: TvButton): Boolean =
        button.visibility == View.VISIBLE && button.isEnabled

    private fun enable(button: TvButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }
}
