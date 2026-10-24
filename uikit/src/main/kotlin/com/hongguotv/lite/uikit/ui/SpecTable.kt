package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.token.Tokens

/**
 * 规格表的一行：`label（13sp dim，56dp 列宽） + value（14.5sp）`。
 *
 * [strong] 的行（"记录"）用白粗——那是用户回到这一页的理由（B 方案口径），
 * 其余行是"这部剧本身"的信息，弱一档。哪一行 strong 由 feature 的文案派生函数决定，
 * uikit 只负责画；单测钉的是派生函数（"哪一行是 strong"），不测像素。
 */
data class SpecRow(val label: String, val value: String, val strong: Boolean = false)

/**
 * 两列规格表（B 方案详情 hero 的信息列）。
 *
 * 行距 9dp；行数由调用方给（详情页四行：更新 / 集数 / 分类 / 记录）。不做滚动、不做折叠。
 */
@SuppressLint("ViewConstructor")
class SpecTable internal constructor(private val ui: UiKit) : LinearLayout(ui.context) {

    private val tokens = ui.tokens

    init {
        orientation = VERTICAL
    }

    /** 重建行。行数很少（4 行上下），整表重建比复用简单，页面装配时才会调用。 */
    fun show(rows: List<SpecRow>) {
        removeAllViews()
        rows.forEachIndexed { index, row ->
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(context).apply {
                text = row.label
                textSize = tokens.type.specLabel
                setTextColor(tokens.colors.dim)
                includeFontPadding = false
                maxLines = 1
            }
            line.addView(label, LayoutParams(ui.dp(tokens.metrics.specLabelWidth), ViewGroup.LayoutParams.WRAP_CONTENT))

            val value = TextView(context).apply {
                text = row.value
                textSize = tokens.type.specValueDetail
                setTextColor(if (row.strong) tokens.colors.focus else tokens.colors.soft)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                fontFeatureSettings = "tnum"
                setTypeface(null, if (row.strong) Typeface.BOLD else Typeface.NORMAL)
            }
            line.addView(value, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(line, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index < rows.lastIndex) bottomMargin = ui.dp(tokens.metrics.specRowGap)
            })
        }
    }
}
