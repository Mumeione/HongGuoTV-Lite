package com.hongguotv.lite.uikit.page

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.lite.uikit.focus.FocusSession
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.setTextIfChanged
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.RailItem
import com.hongguotv.lite.uikit.ui.RailNav
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.UiKit

/** 内页页头：返回（可无）+ 标题 + 右侧动作。 */
@SuppressLint("ViewConstructor")
class PageHeader internal constructor(ui: UiKit, title: String, onBack: (() -> Unit)?) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    val back: TvButton
    val titleView: TextView
    val actions: LinearLayout

    /**
     * 标题右侧、推到页面右边之前的一块区域（「检查更新」这类与标题同属一组的动作）。
     * 与 [actions] 的区别：这里贴着标题，不会被弹性空隙推到右边缘。
     */
    private val leadRow: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(tokens.metrics.pageTitleRowHeight)

        back = ui.button("‹ 返回", variant = ButtonVariant.SMALL, onTap = { onBack?.invoke() })
        // 收藏/历史/设置是顶层页，没有"返回上一页"的语义：不给按钮，而不是给一个按了没反应的
        back.visibility = if (onBack == null) View.GONE else View.VISIBLE
        addView(back)

        titleView = TextView(context).apply {
            text = title
            textSize = tokens.type.heading
            setTextColor(tokens.colors.text)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, Typeface.BOLD)
        }
        // 标题不再吃满剩余宽度：后面紧跟一块「贴身动作」区，再用弹性空隙把右侧动作推边。
        // 有返回键时与键拉开一档；顶层页（无返回）直接从页边距起排（mockup 画面4）。
        addView(titleView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(tokens.metrics.pageTitleRowHeight)).apply {
            if (onBack != null) leftMargin = dp(tokens.space.m)
        })

        leadRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 与标题拉开一档：贴太紧会让动作看着像标题的一部分
            setPadding(0, 0, 0, 0)
        }
        addView(leadRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(View(context), LayoutParams(0, 1, 1f))

        actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(actions, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 标题旁边的动作区：顺序就是视觉顺序（例如「检查更新」→ 说明文字）。 */
    fun lead(): LinearLayout = leadRow

    /** 标题旁边的小字说明（例如检查更新的结果）。 */
    fun leadNote(value: String = ""): TextView = TextView(context).apply {
        text = value
        textSize = tokens.type.caption
        setTextColor(tokens.colors.muted)
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        // 说明文字与按钮之间的留白比按钮与标题之间大一档：三件事（标题 / 动作 / 结果）
        // 要在一条线上读出层次，不能挤成一句
        setPadding(dp(tokens.space.l), 0, 0, 0)
        leadRow.addView(this)
    }

    fun title(value: String) {
        titleView.setTextIfChanged(value)
    }

    /** 顶层页的大标题（B 方案：26sp，与详情片名同档）。带返回的页头用 20sp。 */
    fun titleSize(size: Float) {
        titleView.textSize = size
    }

    /** 页头右侧的小字说明（例如"最近 30 条 · 收藏不受影响"）。 */
    fun note(value: String): TextView = TextView(context).apply {
        text = value
        textSize = tokens.type.caption
        setTextColor(tokens.colors.muted)
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, 0, dp(tokens.space.l), 0)
        actions.addView(this)
    }
}

/**
 * 页面骨架（B 方案）：左侧栏 / 页头 / 内容槽 / 页脚 / 底部信息带，**一次搭好后不再重建**。
 *
 * 旧实现每次刷新都 `root.removeAllViews()` 重建整棵树，焦点随之丢失；
 * 稳定树 + 内容增量更新之后，"返回后光标还在原位"才有立足点。
 *
 * 结构：
 * ```
 * root (FrameLayout)
 * ├── railSlot        左侧 80dp 图标栏（showRail 显示；显示时主区整体右移）
 * └── mainColumn
 *     ├── contentColumn（padding：上 24 / 左右 40 / 下 0）
 *     │   ├── headerSlot   页头
 *     │   ├── bodySlot     内容（weight 1）
 *     │   └── footerSlot   页脚
 *     └── bandSlot         底部信息带（46dp，band() 显示）
 * ```
 *
 * 用法：
 * - 目录类页面：`showRail(...)` + `band()` + 自建 `ContentPane`（滚动列表 + 居中空态）；
 * - 内页：`showHeader(...)` + `scrollBody()`。
 *
 * `scrollBody()` 只给"一定有内容"的内页用；需要空态的地方一律用 `ContentPane`，
 * 否则空态文字又会变成每页各写一遍的内联 padding（S9 之前就是这样，而且都不居中）。
 */
@SuppressLint("ViewConstructor")
class PageScaffold internal constructor(private val ui: UiKit) : FrameLayout(ui.context) {

    private val tokens = ui.tokens
    private val railSlot: FrameLayout
    private val mainColumn: LinearLayout
    private val contentColumn: LinearLayout
    private val headerSlot: FrameLayout
    private val bodySlot: FrameLayout
    private val footerSlot: LinearLayout
    private val bandSlot: FrameLayout
    private val bodyContent: LinearLayout
    private var _rail: RailNav? = null
    private var _header: PageHeader? = null
    private var _band: InfoBand? = null
    private var session: FocusSession? = null
    private var bodyMode = BodyMode.UNSET

    private enum class BodyMode { UNSET, PLAIN, SCROLL }

    init {
        setBackgroundColor(tokens.colors.background)

        railSlot = FrameLayout(context).apply { visibility = View.GONE }
        addView(railSlot, LayoutParams(dp(tokens.metrics.railWidth), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))

        mainColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(mainColumn, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(
                dp(tokens.metrics.pagePaddingH),
                dp(tokens.metrics.pagePaddingTop),
                dp(tokens.metrics.pagePaddingH),
                0,
            )
        }
        mainColumn.addView(contentColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        headerSlot = FrameLayout(context).apply { visibility = View.GONE }
        contentColumn.addView(headerSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bodySlot = FrameLayout(context)
        contentColumn.addView(bodySlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footerSlot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER or Gravity.END
            visibility = View.GONE
        }
        contentColumn.addView(footerSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bandSlot = FrameLayout(context).apply { visibility = View.GONE }
        mainColumn.addView(bandSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bodyContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 焦点缩放允许轻微出界：不让容器把白框/圆角切掉，裁切交给滚动视口
            clipChildren = false
        }
    }

    /**
     * 左侧栏（B 方案）。与旧顶栏同一原则：建过就只切选中态，不重建。
     * 显示时主内容区整体右移 [com.hongguotv.lite.uikit.token.Metrics.railWidth]。
     */
    fun showRail(items: List<RailItem>, selected: Int): RailNav {
        val existing = _rail
        if (existing != null) {
            existing.select(selected)
            return existing
        }
        val rail = RailNav(ui, items)
        rail.select(selected)
        railSlot.addView(rail, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        railSlot.visibility = View.VISIBLE
        (mainColumn.layoutParams as? LayoutParams)?.leftMargin = dp(tokens.metrics.railWidth)
        // 网格首帧按"屏幕宽 - 侧栏 - 页边距"估可用宽度，列数才不会算错
        ui.leftChromeDp = tokens.metrics.railWidth
        _rail = rail
        return rail
    }

    /** 底部信息带。建过就复用同一个实例（视图复用见 [InfoBand]）。 */
    fun band(): InfoBand {
        val existing = _band
        if (existing != null) return existing
        val band = InfoBand(ui)
        bandSlot.addView(band, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(tokens.metrics.infoBandHeight)))
        bandSlot.visibility = View.VISIBLE
        _band = band
        return band
    }

    /**
     * 收起信息带（没有可说的内容时空着 46dp 只会把内容区顶矮——mockup 里它
     * 总是伴着卡片出现，没有卡片就不占位）。
     */
    fun hideBand() {
        bandSlot.visibility = View.GONE
    }

    /**
     * 页头。与侧栏同一原则：建过就只更新标题，`onBack` 以第一次传入的为准。
     *
     * [onBack] 为 null 时隐藏返回键——目录类顶层页（收藏 / 历史 / 设置）没有"上一页"可回。
     */
    fun showHeader(title: String, onBack: (() -> Unit)? = null): PageHeader {
        val existing = _header
        if (existing != null) {
            existing.title(title)
            headerSlot.visibility = View.VISIBLE
            return existing
        }
        val header = PageHeader(ui, title, onBack)
        headerSlot.addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        headerSlot.visibility = View.VISIBLE
        _header = header
        return header
    }

    fun hideHeader() {
        headerSlot.visibility = View.GONE
    }

    /** 内容容器（不滚动）。与 [scrollBody] 二选一，先调用者生效。 */
    fun body(): LinearLayout {
        if (bodyMode == BodyMode.UNSET) {
            bodySlot.addView(bodyContent, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            bodyMode = BodyMode.PLAIN
        }
        return bodyContent
    }

    /** 内容容器（可滚动），并自动装上焦点补偿：光标移出视口时滚到可见处。 */
    fun scrollBody(): LinearLayout {
        if (bodyMode == BodyMode.SCROLL) return bodyContent
        if (bodyMode == BodyMode.PLAIN) bodySlot.removeView(bodyContent)
        val scroll = ui.scroll(bodyContent)
        bodySlot.addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ui.keepFocusVisible(scroll)
        bodyMode = BodyMode.SCROLL
        return bodyContent
    }

    /** 页脚动作区（分页"上一页/下一页"这类）。 */
    fun footer(): LinearLayout {
        footerSlot.visibility = View.VISIBLE
        footerSlot.setPadding(0, dp(tokens.space.l), 0, 0)
        return footerSlot
    }

    /** 清空内容：只在**换页**时调用，数据刷新不允许走这里。 */
    fun clearBody() {
        bodyContent.removeAllViews()
    }

    /**
     * 本页的焦点会话。第一次调用时创建，并把"焦点变化 → 记住光标"接到本页视图树上，
     * feature 只需要在每次重建视图后 `bind(plan, views)`、需要时 `request()`。
     *
     * [page] 是记忆的隔离键，约定用"页面 + 查询条件"（例如 `"catalog:收藏"`）。
     */
    fun focusSession(page: String): FocusSession {
        val existing = session
        if (existing != null) return existing
        return FocusSession(page).also { created ->
            session = created
            viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus -> session?.onFocusChanged(oldFocus, newFocus) }
        }
    }
}
