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
 * │<│ │   分集格 / 季线卡（内容）    │ │>│
 * ╰─╯ └──────────────────────────┘ ╰─╯
 * ```
 *
 * 两条**竖向长条**贴在内容两侧，高随内容撑满：比顶行小胶囊更直观——按左/右出格
 * 就撞到条上，按一下翻一组。不可用的那侧禁用压暗（保留占位），单页时整对隐藏。
 *
 * 文案用**竖排汉字「上一组 / 下一组」**（每字一行，共三行）：长条只有
 * [com.hongguotv.lite.uikit.token.Metrics.sideStripWidth] 宽，横排三字放不下；
 * `‹`/`›` 细得像一条缝，`◀`/`▶` 在模拟器字体里缺字形，ASCII `<`/`>` 又会被
 * TvButton STANDARD 默认左右各 [com.hongguotv.lite.uikit.token.Metrics.pillPaddingH] 的
 * 内边距挤成 0 宽。汉字不依赖符号字形，[com.hongguotv.lite.uikit.token.Type.compact]
 * 加粗后每行一个字，换设备也不会变成一条缝。
 *
 * 焦点上它们是 [com.hongguotv.lite.uikit.focus.FocusPlan] 的左右侧翼段：
 * 只有页面声明为 `flanked` 的内容段与它们互通，动作列等其他段不受影响。
 */
class SideStrips(
    private val ui: UiKit,
    private val prevKey: FocusKey,
    private val nextKey: FocusKey,
    private val onPage: (Int) -> Unit,
) {

    private val tokens: Tokens = ui.tokens

    private fun stripButton(text: String, delta: Int): TvButton =
        ui.button(text, variant = ButtonVariant.STANDARD) { onPage(delta) }.apply {
            textSize = tokens.type.compact
            minHeight = ui.dp(tokens.metrics.sideStripMinHeight)
            // STANDARD 默认左右各 pillPaddingH：长条只有 sideStripWidth 宽，会被内边距吃光，
            // 符号/文字都看不见。收到 space.xxs 后，一行一个汉字放得下。
            setPadding(ui.dp(tokens.space.xxs), 0, ui.dp(tokens.space.xxs), 0)
        }

    val prev: TvButton = stripButton("上\n一\n组", -1)
    val next: TvButton = stripButton("下\n一\n组", 1)

    /** 把 [content] 夹进两条长条之间；条宽取 `Tokens.metrics.sideStripWidth`，高随内容撑满。 */
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

    /**
     * 把可用的长条放进页面的 key → View 表。
     *
     * 焦点段只描述"谁和谁相邻"，真正落成 `nextFocus*Id` 必须有 View：漏掉这两项时
     * 长条虽然声明在侧翼段里，光标却永远进不去（维护者实测"长条选不中、按不动"）。
     */
    fun bindInto(views: MutableMap<FocusKey, View>) {
        if (usable(prev)) views[prevKey] = prev
        if (usable(next)) views[nextKey] = next
    }

    /**
     * 翻组之后光标该留在哪：**按下的那条还在就留在条上**（连翻两组不用先把光标挪回来）；
     * 已经到了尽头（那条被禁用）就返回 null，由页面改成落到第一张卡。
     */
    fun preferredAfterPage(delta: Int): FocusKey? = when {
        delta > 0 && usable(next) -> nextKey
        delta < 0 && usable(prev) -> prevKey
        else -> null
    }

    private fun usable(button: TvButton): Boolean =
        button.visibility == View.VISIBLE && button.isEnabled

    private fun enable(button: TvButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else tokens.metrics.disabledAlpha
    }
}
