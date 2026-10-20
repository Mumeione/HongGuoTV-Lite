package com.hongguotv.lite.feature.catalog

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.usecase.FavoriteUpdateCheck
import com.hongguotv.core.usecase.SeasonProgress
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.GridRendered
import com.hongguotv.lite.uikit.page.PosterGrid
import com.hongguotv.lite.uikit.ui.CardItem
import com.hongguotv.lite.uikit.ui.PreviewRail
import com.hongguotv.lite.uikit.ui.TvButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 收藏页。
 *
 * 收藏的是**系列**（去季号的基名），卡片角标回答"有没有新的一季"。
 * 进页自动检查更新（一小时内检查过就跳过），也可手动强制重查；
 * 检查是串行的、连续失败 3 次自动停，离开本页（进详情/播放）立刻取消。
 */
internal class FavoritesPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.FAVORITES) {

    private lateinit var statusView: TextView
    private lateinit var checkButton: TvButton
    private lateinit var preview: PreviewRail
    private lateinit var emptyView: TextView
    private lateinit var grid: PosterGrid

    private var entries: List<FavoriteEntry> = emptyList()
    private var rendered: GridRendered? = null
    private var checkJob: Job? = null
    private var status: String = ""
    private var checking: Boolean = false

    override fun onBuild() {
        val header = scaffold.showHeader("我的收藏")
        statusView = header.note("")
        checkButton = ui.button("检查更新") { startCheck(force = true) }
        header.actions.addView(checkButton)

        val body = scaffold.body()
        preview = ui.preview()
        body.addView(preview)

        val content = ui.column()
        val scroll = ui.scroll(content)
        ui.keepFocusVisible(scroll)
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView = ui.text("在剧集详情中选择收藏，喜欢的剧就会出现在这里", tokens.type.subtitle, tokens.colors.muted).apply {
            setPadding(0, ui.dp(tokens.space.xxl), 0, ui.dp(tokens.space.xl))
            visibility = View.GONE
        }
        content.addView(emptyView)

        grid = ui.grid("favorites").apply {
            // 收藏封面值得占电视存储（旧行为）：列表稳定，复看频率高
            persistCovers = true
            onCardFocused = { item -> previewFavorite(item) }
            onCardActivated = { item -> openFavorite(item) }
        }
        content.addView(grid)

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
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        grid.visibility = if (isEmpty) View.GONE else View.VISIBLE
        rendered = if (isEmpty) null else grid.render(entries.map(CatalogCards::favorite))
        if (isEmpty) preview.show("", "", "")
        chrome()
        applyFocus()
    }

    private fun chrome() {
        statusView.text = status
        checkButton.label(if (checking) "正在检查…" else "检查更新")
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

    private fun previewFavorite(item: CardItem) {
        val entry = entries.firstOrNull { it.series.id == item.id } ?: return
        val line = host.favorites.seasonLine(entry.seriesKey)
        val progress = SeasonProgress.latest(entry, line) { host.progress.progressOf(it) }
        preview.show(
            title = entry.series.title,
            status = CatalogText.previewStatus(
                favorite = true,
                season = progress?.let { SeasonProgress.seasonOf(it, line, entry.series.season) } ?: entry.series.season,
                progress = progress,
                watched = host.progress.isWatched(entry.series.id),
            ),
            meta = CatalogText.meta(entry.series),
        )
    }

    private fun applyFocus() {
        val views = LinkedHashMap<FocusKey, View>()
        views.putAll(navViews())
        views[FocusKey("favorites:check")] = checkButton
        rendered?.views?.let { views.putAll(it) }

        val sections = listOf(
            navSection(),
            FocusSections.section(listOf(FocusKey("favorites:check")), 1),
            rendered?.let { FocusSections.section(it.keys, it.columns) },
        )
        applyPlan(sections, views)
    }
}
