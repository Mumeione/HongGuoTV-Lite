package com.hongguotv.lite.uikit.page

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.setTextIfChanged
import com.hongguotv.lite.uikit.internal.shape
import com.hongguotv.lite.uikit.ui.UiKit

/**
 * 底部信息带（B 方案）：46dp 高，贴在页面最下方，替代旧"预览区"。
 *
 * 结构：左侧片名（14.5sp 白粗）+ 若干说明段（13sp muted，逐段拼接），
 * 右侧按键提示（键帽 + 文案）。信息栏只写"这部剧"的信息（片名、收藏状态、更新、分类）——
 * **我的状态**（看到第几集）只在卡片与详情页出现（维护者口径）。
 *
 * 说明段与按键提示都做**视图复用**：光标每动一次就刷新一次内容，
 * 重建 TextView 会让整条信息带反复 measure，没必要。
 */
@SuppressLint("ViewConstructor")
class InfoBand internal constructor(private val ui: UiKit) : FrameLayout(ui.context) {

    private val tokens = ui.tokens
    private val titleView: TextView
    private val segmentsRow: LinearLayout
    private val segmentViews = mutableListOf<TextView>()
    private val keysRow: LinearLayout
    private val keySpans = mutableListOf<Pair<TextView, TextView>>()

    init {
        val height = ui.dp(tokens.metrics.infoBandHeight)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(tokens.metrics.pagePaddingH), 0, ui.dp(tokens.metrics.pagePaddingH), 0)
        }

        titleView = TextView(context).apply {
            textSize = tokens.type.specValue
            setTextColor(tokens.colors.focus)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(null, Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
        row.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = ui.dp(tokens.space.xl)
        })

        segmentsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(segmentsRow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = ui.dp(tokens.space.l)
        })

        keysRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(keysRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 上缘 1px 结构线 + 底色：信息带是"结构色"的一块，与内容之间只隔一条线
        addView(
            View(context).apply { setBackgroundColor(tokens.colors.chrome) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM),
        )
        addView(
            View(context).apply { setBackgroundColor(tokens.colors.line) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(tokens.space.hairline), Gravity.TOP),
        )
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    /**
     * @param title 片名（白粗）
     * @param segments 说明段（收藏状态、分类、更新…），逐段 muted；空列表 = 只有片名
     * @param keys 按键提示 `(键帽, 文案)`，贴右侧；空列表 = 不显示
     */
    fun show(title: String, segments: List<String>, keys: List<Pair<String, String>>) {
        titleView.setTextIfChanged(title)
        syncSegments(segments)
        syncKeys(keys)
    }

    private fun syncSegments(segments: List<String>) {
        while (segmentViews.size < segments.size) {
            val view = TextView(context).apply {
                textSize = tokens.type.caption
                setTextColor(tokens.colors.muted)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                fontFeatureSettings = "tnum"
            }
            segmentViews += view
            segmentsRow.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = ui.dp(tokens.space.xl)
            })
        }
        segmentViews.forEachIndexed { index, view ->
            view.visibility = if (index < segments.size) View.VISIBLE else View.GONE
            if (index < segments.size) view.setTextIfChanged(segments[index])
        }
    }

    private fun syncKeys(keys: List<Pair<String, String>>) {
        while (keySpans.size < keys.size) {
            val cap = TextView(context).apply {
                textSize = tokens.type.railLabel
                setTextColor(tokens.colors.text)
                includeFontPadding = false
                gravity = Gravity.CENTER
                background = shape(tokens.colors.raised, tokens.metrics.keyCapRadius, null)
            }
            val label = TextView(context).apply {
                textSize = tokens.type.caption
                setTextColor(tokens.colors.muted)
                includeFontPadding = false
                maxLines = 1
            }
            val span = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(cap, LinearLayout.LayoutParams(ui.dp(tokens.metrics.keyCapSize), ui.dp(tokens.metrics.keyCapSize)).apply {
                    rightMargin = ui.dp(tokens.space.s)
                })
                addView(label)
            }
            keySpans += cap to label
            keysRow.addView(span, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = ui.dp(KEY_GAP)
            })
        }
        keySpans.forEachIndexed { index, (_, label) ->
            val span = label.parent.parent as View
            span.visibility = if (index < keys.size) View.VISIBLE else View.GONE
            if (index < keys.size) {
                keySpans[index].first.setTextIfChanged(keys[index].first)
                label.setTextIfChanged(keys[index].second)
            }
        }
    }

    private companion object {
        /** 按键提示之间的间距（mockup `.b-band .keys{gap:18px}` + `span+span{margin-left:6px}`）。 */
        const val KEY_GAP = 18
    }
}
