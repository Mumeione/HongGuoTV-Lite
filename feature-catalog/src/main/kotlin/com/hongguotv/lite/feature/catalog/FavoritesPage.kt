package com.hongguotv.lite.feature.catalog

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.usecase.FavoriteUpdateCheck
import com.hongguotv.core.usecase.SeasonProgress
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.focus.navKey
import com.hongguotv.lite.uikit.page.ContentPane
import com.hongguotv.lite.uikit.page.GridRendered
import com.hongguotv.lite.uikit.page.PosterGrid
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.ui.CardItem
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.TvButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 收藏页。
 *
 * 收藏的是**系列**（去季号的基名），卡片角标回答"更新到哪里"。
 * 进页自动检查更新（一小时内检查过就跳过），也可手动强制重查；
 * 检查是串行的、连续失败 3 次自动停，离开本页（进详情/播放）立刻取消。
 *
 * B 方案：底部信息栏 = 片名 + 已收藏 + 更新到…（无 mockup，跟随搜索结果的信息栏口径）。
 */
internal class FavoritesPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.FAVORITES) {

    private lateinit var statusView: TextView
    private lateinit var checkButton: TvButton
    private lateinit var pane: ContentPane
    private lateinit var grid: PosterGrid

    private var entries: List<FavoriteEntry> = emptyList()
    private var rendered: GridRendered? = null
    private var checkJob: Job? = null
    private var status: String = ""
    private var checking: Boolean = false

    override fun onBuild() {
        // 信息栏只在"焦点落定且落在卡片上"时出现，离开卡片区就收起
        focus.onSettled = { key -> onCardSettled(key) }

        val header = scaffold.showHeader("我的收藏")
        // B 方案：顶层页用大标题（与详情片名同档）
        header.titleSize(tokens.type.display)
        // 顺序按维护者口径：我的收藏 → 检查更新 → 更新说明，三者贴在一起靠左，不推到屏幕右边。
        // 2026-10-24 口径：按钮与说明都缩小一档（小号胶囊 + footnote），它是低频动作不抢主视觉。
        checkButton = ui.button("检查更新", variant = ButtonVariant.SMALL) { startCheck(force = true) }
        // 定宽（不是 minWidth）：文案在「检查更新」↔「正在检查…」间切换时右侧说明不重排。
        // minWidth 只保下限，长文案仍会把说明顶走——这条要的是"宽度不变"。
        header.lead().addView(
            checkButton,
            LinearLayout.LayoutParams(ui.dp(tokens.metrics.leadActionWidth), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = ui.dp(tokens.space.m)
            },
        )
        statusView = header.leadNote("").apply { textSize = tokens.type.footnote }

        val body = scaffold.body()
        pane = ui.contentPane()
        body.addView(pane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pane.empty.show("在剧集详情中选择收藏，喜欢的剧就会出现在这里")

        grid = ui.grid(GRID_SECTION).apply {
            // 收藏封面值得占电视存储（旧行为）：列表稳定，复看频率高
            persistCovers = true
            // 聚光压暗与搜索页「继续看」、历史页一致：非焦点卡 0.62
            spotlight = true
            onCardActivated = { item -> openFavorite(item) }
        }
        // 标题到卡片 22dp（mockup 口径，与历史页同一条）
        pane.content.addView(
            grid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(tokens.metrics.gridHeaderGap)
            },
        )

        entries = host.favorites.all()
        status = FavoriteLabels.idleStatus(entries.size, dueEntries().size)
        render()
        // 自动检查：1 小时有效期在用例里判定，这里只发起
        startCheck(force = false)
    }

    // ---------- 检查更新 ----------

    private fun dueEntries(): List<FavoriteEntry> =
        FavoriteUpdateCheck.due(entries, System.currentTimeMillis(), force = false)

    private fun startCheck(force: Boolean) {
        if (checkJob?.isActive == true) return
        val source = host.favorites.all()
        entries = source
        if (source.isEmpty()) {
            status = FavoriteLabels.idleStatus(0, 0)
            chrome()
            return
        }
        if (!force && FavoriteUpdateCheck.due(source, System.currentTimeMillis(), force = false).isEmpty()) {
            status = FavoriteLabels.idleStatus(source.size, 0)
            chrome()
            return
        }

        checking = true
        chrome()
        checkJob = scope.launch {
            try {
                val report = FavoriteUpdateCheck.run(
                    entries = source,
                    content = host.content,
                    favorites = host.favorites,
                    clock = System::currentTimeMillis,
                    force = force,
                    onProgress = { index, total, title ->
                        status = FavoriteLabels.progressText(index, total, title)
                        chrome()
                    },
                    // 每检查完一部就刷新角标：走 grid 的增量路径，光标不动
                    onChecked = { reloadCards() },
                )
                status = FavoriteLabels.summary(report)
            } finally {
                checking = false
                if (isActive) {
                    reloadCards()
                    chrome()
                }
            }
        }
    }

    private fun stopCheck() {
        checkJob?.cancel()
        checkJob = null
        checking = false
        chrome()
    }

    // ---------- 数据与渲染 ----------

    private fun reloadCards() {
        entries = host.favorites.all()
        render()
    }

    private fun render() {
        val isEmpty = entries.isEmpty()
        if (isEmpty) {
            pane.showEmpty("在剧集详情中选择收藏，喜欢的剧就会出现在这里")
            scaffold.hideBand()
        } else {
            pane.showContent()
            // 信息栏的出现与内容由落定回调管（onCardSettled），这里不再无条件占位
        }
        rendered = if (isEmpty) null else grid.render(entries.map(CatalogCards::favorite))
        chrome()
        applyFocus()
    }

    private fun chrome() {
        statusView.text = status
        checkButton.label(if (checking) "正在检查…" else "检查更新")
    }

    // ---------- 信息栏 ----------

    /**
     * 焦点落定后的信息栏：落在本页网格的卡片上才显示（片名 + 已收藏 + 更新到…），
     * 落到别处（「检查更新」、侧栏）整条收起。
     */
    private fun onCardSettled(key: FocusKey) {
        val entry = grid.cardIdOf(key)?.let { id -> entries.firstOrNull { it.series.id == id } }
        if (entry == null) {
            scaffold.hideBand()
        } else {
            showBand(entry)
        }
    }

    /**
     * 信息栏 = 片名 + **观看到…** + 分类（2026-10-24 验收）。
     *
     * 收藏页的用户问题是"我看到哪了"，不是"更新到哪"（后者在卡片角标上）。
     * 分类取收藏这一季的；收藏行没带分类时退到季线里同 id 的那一季。
     */
    private fun showBand(entry: FavoriteEntry) {
        val line = host.favorites.seasonLine(entry.seriesKey)
        val progress = SeasonProgress.latest(entry, line) { host.progress.progressOf(it) }
        val tags = entry.series.tags.ifBlank {
            line?.seasons?.firstOrNull { it.id == entry.series.id }?.tags.orEmpty()
        }
        scaffold.band().show(
            title = entry.series.title,
            segments = CatalogText.favoriteSegments(
                progress = watchLabel(progress, line, entry.series.season),
                tags = tags,
            ),
            keys = listOf("OK" to "打开"),
        )
    }

    /** 「观看到第九季 第20集」/「已看完」/ 空串（没有记录就不说）。 */
    private fun watchLabel(progress: Progress?, line: SeasonLine?, fallbackSeason: Int): String = when {
        progress == null -> ""
        progress.completed -> "已看完"
        else -> "观看到" + TvText.cardProgress(
            season = SeasonProgress.seasonOf(progress, line, fallbackSeason),
            index = progress.episodeIndex,
        )
    }

    // ---------- 交互 ----------

    private fun openFavorite(item: CardItem) {
        val entry = entries.firstOrNull { it.series.id == item.id } ?: return
        // 进详情/播放即停：检查不该在播放时抢带宽
        stopCheck()
        if (host.favorites.seasonLine(entry.seriesKey) != null) {
            host.navigator.toSeries(entry.seriesKey)
        } else {
            host.navigator.toDetail(entry.series.id)
        }
    }

    /**
     * 首焦：第一张收藏卡 → 当前导航项。
     * 空收藏时光标停在「收藏」这个导航项上（空页里没有可落的内容项），
     * 「检查更新」往下按一下就够到，不需要抢占首焦。
     */
    override fun startFocus(): List<FocusKey> =
        listOfNotNull(firstCard(), navKey(CatalogTab.FAVORITES.ordinal))

    /** 第一张收藏卡（空收藏为 null）：首焦阶梯与侧栏按右入口共用同一个落点。 */
    private fun firstCard(): FocusKey? = rendered?.keys?.firstOrNull()

    private fun applyFocus() {
        val views = LinkedHashMap<FocusKey, View>()
        views.putAll(navViews())
        views[FocusKey("favorites:check")] = checkButton
        rendered?.views?.let { views.putAll(it) }

        val sections = listOf(
            FocusSections.section(listOf(FocusKey("favorites:check")), 1),
            rendered?.let { FocusSections.section(it.keys, it.columns) },
        )
        // 侧栏按右直接进第一张收藏卡（「检查更新」只从下方按上可达）；
        // 空收藏没有卡片，sideEntry 为 null 退回默认落按钮本身（页内唯一内容项）。
        applyPlan(sections, views, sideEntry = firstCard())
    }

    private companion object {
        /** 本页网格的焦点段名（`PosterGrid.keyOf` 的前缀）：落定回调靠它认出"落在了卡片上"。 */
        const val GRID_SECTION = "favorites"
    }
}
