package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.setTextIfChanged

/**
 * 聚焦项的说明区，固定三行：标题 / 状态 / 分类与集数。
 *
 * 三行都占固定高度（单行 + 省略号），D-pad 移动时不会把下面的内容撑开。
 * **文案由调用方给**：这里不知道"季""系列"是什么，那套词汇在 feature 与 CONTEXT.md 里。
 */
@SuppressLint("ViewConstructor")
class PreviewRail internal constructor(private val ui: UiKit) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    private val titleView = label(tokens.type.preview, tokens.colors.text).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val statusView = label(tokens.type.caption, tokens.colors.text)
    private val metaView = label(tokens.type.caption, tokens.colors.muted)

    init {
        orientation = VERTICAL
        // 它常驻在网格上方：每 1dp 都是从首屏卡片那里拿走的，所以留白比卡片标题还紧一档
        setPadding(
            0,
            dp(tokens.metrics.previewRailPaddingTop),
            0,
            dp(tokens.metrics.previewRailPaddingBottom),
        )
        addView(titleView)
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(tokens.space.xs) })
        addView(metaView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(tokens.space.s) })
    }

    fun show(title: String, status: String, meta: String) {
        titleView.setTextIfChanged(title)
        statusView.setTextIfChanged(status)
        metaView.setTextIfChanged(meta)
    }

    private fun label(size: Float, color: Int): TextView = TextView(ui.context).apply {
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        minLines = 1
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // 数字用等宽字形：集数/时间变化时行宽不抖。
        fontFeatureSettings = "tnum"
    }
}
