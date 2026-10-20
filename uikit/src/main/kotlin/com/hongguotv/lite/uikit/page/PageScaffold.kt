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
import com.hongguotv.lite.uikit.ui.Icons
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.TvNavItem
import com.hongguotv.lite.uikit.ui.UiKit

/** 顶栏的一项：标签 + 图标 + 选中后的动作。 */
data class TopBarItem(val label: String, val iconRes: Int, val onSelect: () -> Unit)

/**
 * 顶栏：品牌靠左，「搜索 / 收藏 / 历史 / 设置」居中。
 *
 * 导航项比页面内动作按钮大一档并常驻加粗，与内容区形成层级——
 * 旧版曾经"每个按钮都长一样"，用户分不清哪儿是导航。
 */
@SuppressLint("ViewConstructor")
class TopBar internal constructor(ui: UiKit, brand: String, items: List<TopBarItem>) : FrameLayout(ui.context) {

    private val tokens = ui.tokens
    val navItems: List<TvNavItem>

    init {
        val brandRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(
            ImageView(context).apply { setImageResource(Icons.BRAND) },
            LinearLayout.LayoutParams(dp(tokens.metrics.brandIcon), dp(tokens.metrics.brandIcon)).apply { rightMargin = dp(tokens.space.m) },
        )
        brandRow.addView(TextView(context).apply {
            text = brand
            textSize = tokens.type.brand
            setTextColor(tokens.colors.text)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(brandRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(tokens.metrics.topBarHeight), Gravity.START or Gravity.CENTER_VERTICAL))

        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navItems = items.mapIndexed { index, item ->
            ui.navItem(item.label, item.iconRes, item.onSelect).also { nav ->
                navRow.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index < items.lastIndex) rightMargin = dp(tokens.metrics.navGap)
                })
            }
        }
        addView(navRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** 切换当前页：只有"当前页"那一个亮起来。 */
    fun select(index: Int) {
        navItems.forEachIndexed { i, item -> item.isSelected = i == index }
    }
}

/** 内页页头：返回（可无）+ 标题 + 右侧动作。 */
@SuppressLint("ViewConstructor")
class PageHeader internal constructor(ui: UiKit, title: String, onBack: (() -> Unit)?) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    val back: TvButton
    val titleView: TextView
    val actions: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(tokens.metrics.headerMinHeight)

        back = ui.button("‹ 返回", variant = ButtonVariant.STANDARD, onTap = { onBack?.invoke() })
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
            setPadding(dp(tokens.space.m), 0, dp(tokens.space.m), 0)
        }
        addView(titleView, LayoutParams(0, dp(tokens.metrics.headerMinHeight), 1f))

        actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(actions, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun title(value: String) {
        titleView.setTextIfChanged(value)
    }

    /** 页头右侧的小字说明（例如"最近 30 条 · 收藏不受影响"）。 */
    fun note(value: String): TextView = TextView(context).apply {
        text = value
        textSize = tokens.type.label
        setTextColor(tokens.colors.muted)
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, 0, dp(tokens.space.l), 0)
        actions.addView(this)
    }
}

/**
 * 页面骨架：顶栏 / 分隔线 / 页头 / 内容槽 / 页脚，**一次搭好后不再重建**。
 *
 * 旧实现每次刷新都 `root.removeAllViews()` 重建整棵树，焦点随之丢失；
 * 稳定树 + 内容增量更新之后，"返回后光标还在原位"才有立足点。
 *
 * 用法（顺序无关，槽位是预先建好的）：
 * - 目录类页面：`showTopBar(...)` + `scrollBody()` + `grid(...)`
 * - 内页：`showHeader(...)` + `scrollBody()`
 */
@SuppressLint("ViewConstructor")
class PageScaffold internal constructor(private val ui: UiKit) : FrameLayout(ui.context) {

    private val tokens = ui.tokens
    private val container: LinearLayout
    private val topBarSlot: FrameLayout
    private val dividerSlot: LinearLayout
    private val headerSlot: FrameLayout
    private val bodySlot: FrameLayout
    private val footerSlot: LinearLayout
    private val bodyContent: LinearLayout
    private var _topBar: TopBar? = null
    private var _header: PageHeader? = null
    private var session: FocusSession? = null
    private var bodyMode = BodyMode.UNSET

    private enum class BodyMode { UNSET, PLAIN, SCROLL }

    init {
        setBackgroundColor(tokens.colors.background)
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                ui.pageInsetHorizontal(),
                ui.pageInsetVertical(),
                ui.pageInsetHorizontal(),
                ui.pageInsetVertical(),
            )
        }
        addView(container, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBarSlot = FrameLayout(context).apply { visibility = View.GONE }
        container.addView(topBarSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dividerSlot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(ui.divider())
            addView(ui.spacer(tokens.metrics.dividerGap))
        }
        container.addView(dividerSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        headerSlot = FrameLayout(context).apply { visibility = View.GONE }
        container.addView(headerSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bodySlot = FrameLayout(context)
        container.addView(bodySlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footerSlot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER or Gravity.END
            visibility = View.GONE
        }
        container.addView(footerSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bodyContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    }

    fun showTopBar(brand: String, items: List<TopBarItem>, selected: Int): TopBar {
        // 顶栏是稳定树的一部分：已经建过就只切选中态，避免重建把焦点一起丢掉。
        val existing = _topBar
        if (existing != null) {
            existing.select(selected)
            return existing
        }
        val bar = TopBar(ui, brand, items)
        bar.select(selected)
        topBarSlot.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(tokens.metrics.topBarHeight)))
        topBarSlot.visibility = View.VISIBLE
        dividerSlot.visibility = View.VISIBLE
        _topBar = bar
        return bar
    }

    /**
     * 页头。与顶栏同一原则：建过就只更新标题，`onBack` 以第一次传入的为准。
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
            viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus -> session?.onFocusChanged(newFocus) }
        }
    }
}
