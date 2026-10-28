package com.hongguotv.lite.feature.catalog

import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.usecase.FavoriteLookup
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.focus.navKey
import com.hongguotv.lite.uikit.page.ContentPane
import com.hongguotv.lite.uikit.page.GridRendered
import com.hongguotv.lite.uikit.page.PosterGrid
import com.hongguotv.lite.uikit.ui.CardItem
import com.hongguotv.lite.uikit.ui.Icons
import kotlinx.coroutines.launch

/**
 * 历史页（B 方案，mockup 画面4）。
 *
 * 卡片与搜索/收藏同一份默认积木（149dp 目标卡宽、一行 5 张，一屏两行 10 部，更多往下滚），
 * 状态条只写「第九季 第20集」——详情（收藏状态、分类）在底部信息栏里，
 * **不写观看位置**（我的状态在卡片上已经有了）。
 *
 * 管理入口是 **MENU 键**：进入后聚焦卡右上角浮出删除垃圾桶，光标锁在垃圾桶上，
 * 四向按网格几何换卡（越界夹住）、确认直接删除（单条删除没有两步确认；一键全清
 * 在设置页，不在这里）。提示与退出都走**同一条信息栏**——管理不是另一种 UI，
 * 只是信息栏换了一句说明。退出靠 MENU 或返回键，回到被管理的那张卡。
 */
internal class HistoryPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.HISTORY) {

    private lateinit var pane: ContentPane
    private lateinit var grid: PosterGrid

    /** 管理层：网格与垃圾桶同层，垃圾桶随滚动自然跟随卡片。 */
    private lateinit var gridLayer: FrameLayout
    private lateinit var deleteBadge: ImageView

    private var entries: List<HistoryEntry> = emptyList()
    private var rendered: GridRendered? = null
    private var managing: Boolean = false
    private var targetId: String? = null

    override fun onBuild() {
        // 信息栏只在"焦点落定且落在卡片上"时出现：离开卡片区（侧栏、垃圾桶）就收起，
        // 中间态不更新内容（回调机制见 FocusSession.onSettled）
        focus.onSettled = { key -> onCardSettled(key) }

        val header = scaffold.showHeader("观看历史")
        header.titleSize(tokens.type.display)
        header.leadNote("只存在本机，最多 30 条；清空不影响收藏")

        val body = scaffold.body()
        pane = ui.contentPane()
        body.addView(pane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pane.empty.show("播放过的剧集会自动保存在这里")

        grid = ui.grid(GRID_SECTION).apply {
            persistCovers = true
            // 2026-10-24 口径：历史卡与搜索/收藏**同一份默认积木**（149dp 目标卡宽、一行 5 张），
            // 不再单独缩成 120dp 六列——三种内容列表的卡片大小统一。
            spotlight = true
            onCardFocused = { item -> onCardFocused(item.id) }
            onCardActivated = { item -> openHistory(item) }
        }
        // 网格包一层管理层：垃圾桶叠加在卡片内部（右上角），与网格同层、随滚动自然跟随
        gridLayer = FrameLayout(ui.context)
        gridLayer.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        deleteBadge = buildDeleteBadge()
        gridLayer.addView(deleteBadge, FrameLayout.LayoutParams(ui.dp(BADGE_SIZE), ui.dp(BADGE_SIZE)))
        // 标题到卡片 22dp（mockup 画面4 `.b-histcards{margin-top:22px}`）
        pane.content.addView(
            gridLayer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(tokens.metrics.gridHeaderGap)
            },
        )

        reload()
    }

    /**
     * 垃圾桶：一个可聚焦的图标钮，管理态叠在聚焦卡右上角。
     * 视觉遵守唯一的那条规则——焦点 = 白底深字（icon 深色），非焦点 = 深底白 icon。
     */
    private fun buildDeleteBadge(): ImageView = ImageView(ui.context).apply {
        setImageResource(Icons.DELETE)
        isFocusable = true
        isClickable = true
        visibility = View.GONE
        setOnClickListener { deleteCurrent() }
        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            styleBadge(view as ImageView, focused)
        }
        styleBadge(this, false)
    }

    private fun styleBadge(view: ImageView, focused: Boolean) {
        view.background = GradientDrawable().apply {
            setColor(if (focused) tokens.colors.text else tokens.colors.surface)
            cornerRadius = ui.dp(tokens.metrics.keyCapRadius).toFloat()
        }
        view.setColorFilter(if (focused) tokens.colors.background else tokens.colors.text)
    }

    // ---------- 数据 ----------

    private fun reload() {
        entries = host.progress.history()
        render()
    }

    private fun target(): HistoryEntry? = entries.firstOrNull { it.series.id == targetId }

    private fun render() {
        val isEmpty = entries.isEmpty()
        rendered = if (isEmpty) null else grid.render(entries.map(CatalogCards::history))
        if (isEmpty) {
            pane.showEmpty("播放过的剧集会自动保存在这里")
            scaffold.hideBand()
            // 没有可管理的记录，管理态随之结束
            managing = false
            deleteBadge.visibility = View.GONE
            grid.highlight(null)
        } else {
            pane.showContent()
            if (managing) {
                // 删除后目标已换成相邻卡，聚光跟着垃圾桶走（否则全屏一起压暗）
                grid.highlight(targetId)
                deleteBadge.visibility = View.VISIBLE
                deleteBadge.post { positionBadge() }
            }
        }
        applyFocus()
    }

    // ---------- 交互 ----------

    private fun onCardFocused(seriesId: String) {
        targetId = seriesId
    }

    /** 管理态的确认键语义归垃圾桶（删除当前卡），不再跳详情。 */
    private fun openHistory(item: CardItem) {
        if (managing) return
        host.navigator.toDetail(item.id, resumeRecording = true)
    }

    /**
     * 焦点落定后的信息栏：落在网格的卡片上显示（片名 + 收藏状态 + 分类）；
     * 管理态统一换成管理提示——垃圾桶持焦，这条信息栏就是管理的"动作条"。
     */
    private fun onCardSettled(key: FocusKey) {
        if (managing) {
            showManageBand()
            return
        }
        val entry = grid.cardIdOf(key)?.let { id -> entries.firstOrNull { it.series.id == id } }
        if (entry == null) {
            scaffold.hideBand()
        } else {
            showBand(entry)
        }
    }

    /** 信息栏只写"这部剧"：片名 + 收藏状态 + 分类。观看位置在卡片状态条上，不重复。 */
    private fun showBand(entry: HistoryEntry) {
        // 收藏状态不能只比标题算出的系列键：分宗篇这类标题不带季号，键与收藏行对不上，
        // 已收藏的剧会显示成"没收藏"（2026-10-24 验收）。FavoriteLookup 会用季线里的 id 兜底。
        val favoriteEntry = FavoriteLookup.entry(entry.series, host.favorites)
        // 分类：历史记录的 tags 经常是空串（早期记录），回退到收藏那份，再回退到季线里这一季
        val tags = CatalogText.historyTags(
            historyTags = entry.series.tags,
            favoriteTags = favoriteEntry?.series?.tags.orEmpty(),
        ).ifBlank { FavoriteLookup.seasonTags(entry.series, host.favorites) }
        scaffold.band().show(
            title = entry.series.title,
            segments = CatalogText.bandSegments(
                favorite = favoriteEntry != null,
                tags = tags,
            ),
            keys = listOf("OK" to "继续播放", "MENU" to "管理"),
        )
    }

    /** 管理态的信息栏：与普通信息栏同一个组件、同一套视觉，只换说明。 */
    private fun showManageBand() {
        val entry = target() ?: return
        scaffold.band().show(
            title = "管理：《${entry.series.title}》",
            segments = emptyList(),
            keys = listOf("OK" to "删除这条", "MENU/返回" to "退出"),
        )
    }

    /** MENU 键 = 管理开关（宿主从 `LiteActivity` 转发过来）。 */
    override fun onMenuKey(): Boolean {
        if (entries.isEmpty()) return false
        setManaging(!managing)
        return true
    }

    /** 管理态的返回键 = 关闭菜单回到历史页（不是退出应用/回搜索页）。 */
    override fun onBack(): Boolean {
        if (!managing) return false
        setManaging(false)
        return true
    }

    /**
     * 管理态吃掉全部方向键与确认：光标锁在垃圾桶上，四向按**网格几何**换卡
     * （左右同行相邻、上下同列跨行，越界夹住），确认直接删除。
     * 不拦截的话焦点系统会把光标扔进网格，"点垃圾桶删除"就落空了。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!managing || event.action != KeyEvent.ACTION_DOWN) return false
        val index = entries.indexOfFirst { it.series.id == targetId }
        if (index < 0) return false
        val columns = rendered?.columns ?: 1
        val column = index % columns
        val next = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (column > 0) index - 1 else index
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (column < columns - 1 && index + 1 < entries.size) index + 1 else index
            KeyEvent.KEYCODE_DPAD_UP -> (index - columns).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> (index + columns).coerceAtMost(entries.lastIndex)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                deleteCurrent()
                return true
            }
            else -> return false
        }
        moveTarget(entries[next].series.id)
        return true
    }

    private fun setManaging(value: Boolean) {
        if (managing == value) return
        managing = value
        if (value) {
            if (targetId == null || entries.none { it.series.id == targetId }) {
                targetId = entries.firstOrNull()?.series?.id
            }
            deleteBadge.visibility = View.VISIBLE
            deleteBadge.post { positionBadge() }
            // 光标锁在垃圾桶上、没有任何卡持焦：聚光目标显式钉到垃圾桶所在的那张卡
            grid.highlight(targetId)
            preferredOnRender = FocusKey(KEY_BADGE)
        } else {
            // 回到被管理的那张卡：垃圾桶段消失后，留在记忆里的垃圾桶 key 会失效走阶梯
            targetId?.let { id -> preferredOnRender = grid.keyOf(id) }
            deleteBadge.visibility = View.GONE
            grid.highlight(null)
        }
        applyFocus()
    }

    /** 换卡：垃圾桶挪到新目标卡上，聚光与信息栏一起跟过去（焦点不动，落定回调不会触发）。 */
    private fun moveTarget(id: String) {
        targetId = id
        grid.highlight(id)
        deleteBadge.post { positionBadge() }
        showManageBand()
    }

    private fun deleteCurrent() {
        val entry = target() ?: return
        val index = entries.indexOfFirst { it.series.id == entry.series.id }
        scope.launch {
            host.progress.removeHistory(entry.series.id)
            // 保持管理态、目标跳到相邻卡（右边优先，末尾退一张）：连续清理不用反复进出
            targetId = (entries.getOrNull(index + 1) ?: entries.getOrNull(index - 1))?.series?.id
            reload()
            Toast.makeText(ui.context, "已删除《${entry.series.title}》", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 垃圾桶定位 ----------

    /**
     * 把垃圾桶摆到目标卡的右上角。坐标从卡片沿父链累加到管理层：
     * 与网格同一层，滚动时自然跟随，不需要监听滚动事件。
     */
    private fun positionBadge() {
        if (!managing) return
        val card = targetId?.let { id -> rendered?.views?.get(grid.keyOf(id)) } ?: return
        var x = 0
        var y = 0
        var view: View = card
        while (view !== gridLayer) {
            x += view.left
            y += view.top
            view = view.parent as? View ?: return
        }
        deleteBadge.translationX = (x + card.width - ui.dp(BADGE_SIZE) - ui.dp(BADGE_INSET)).toFloat()
        deleteBadge.translationY = (y + ui.dp(BADGE_INSET)).toFloat()
    }

    // ---------- 焦点 ----------

    /** 首焦：第一条历史 → 当前导航项（空历史时停在「历史」上）。 */
    override fun startFocus(): List<FocusKey> =
        listOfNotNull(rendered?.keys?.firstOrNull(), navKey(CatalogTab.HISTORY.ordinal))

    private fun applyFocus() {
        val views = LinkedHashMap<FocusKey, View>()
        views.putAll(navViews())
        rendered?.views?.let { views.putAll(it) }
        if (managing) views[FocusKey(KEY_BADGE)] = deleteBadge

        // 管理态光标锁在垃圾桶上：四向与确认都被 [onKeyEvent] 拦截，网格段留在图里
        // 只是为了退出管理态后 applyFocus 的结构完整
        val sections = listOf(
            rendered?.let { FocusSections.section(it.keys, it.columns) },
            if (managing) FocusSections.section(listOf(FocusKey(KEY_BADGE)), 1) else null,
        )
        applyPlan(sections, views)
    }

    private companion object {
        /** 本页网格的焦点段名（`PosterGrid.keyOf` 的前缀）：落定回调靠它认出"落在了卡片上"。 */
        const val GRID_SECTION = "history"

        /** 管理垃圾桶的焦点 key。 */
        const val KEY_BADGE = "history:delete"

        /** 垃圾桶边长与到卡片边缘的内距（dp）。 */
        const val BADGE_SIZE = 34
        const val BADGE_INSET = 6
    }
}
