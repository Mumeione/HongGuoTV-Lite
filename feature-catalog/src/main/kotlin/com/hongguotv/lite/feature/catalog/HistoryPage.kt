package com.hongguotv.lite.feature.catalog

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.ContentPane
import com.hongguotv.lite.uikit.page.GridRendered
import com.hongguotv.lite.uikit.page.PosterGrid
import com.hongguotv.lite.uikit.page.TwoStepConfirm
import com.hongguotv.lite.uikit.ui.PreviewRail
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.TvLink
import kotlinx.coroutines.launch

/**
 * 历史页。
 *
 * 「管理记录」不是弹窗，而是页脚动作条：开启后对**当前聚焦的那条**执行
 * 继续播放 / 标记已看（恢复未看）/ 删除。删除要两步确认——S4 没有模态组件，
 * 用"再按一次"替代确认框，焦点移开即撤销。
 */
internal class HistoryPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.HISTORY) {

    private lateinit var preview: PreviewRail
    private lateinit var pane: ContentPane
    private lateinit var grid: PosterGrid
    private lateinit var manageButton: TvButton
    private lateinit var footer: LinearLayout
    private lateinit var footerLabel: TextView
    private lateinit var resumeButton: TvButton
    private lateinit var watchedButton: TvButton
    private lateinit var deleteButton: TvButton
    private lateinit var doneButton: TvLink

    private var entries: List<HistoryEntry> = emptyList()
    private var rendered: GridRendered? = null
    private var managing: Boolean = false
    private var targetId: String? = null
    private val confirm = TwoStepConfirm()

    override fun onBuild() {
        val header = scaffold.showHeader("观看历史")
        header.note("最近 30 条 · 收藏不受影响")
        manageButton = ui.button("管理记录") { setManaging(!managing) }
        header.actions.addView(manageButton)

        val body = scaffold.body()
        preview = ui.preview().apply { visibility = View.GONE }
        body.addView(preview)

        pane = ui.contentPane()
        body.addView(pane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pane.empty.show("播放过的剧集会自动保存在这里")

        grid = ui.grid("history").apply {
            persistCovers = true
            onCardFocused = { item -> onCardFocused(item.id) }
            onCardActivated = { item -> host.navigator.toDetail(item.id, resumeRecording = true) }
        }
        pane.content.addView(grid)

        footer = scaffold.footer()
        footer.visibility = View.GONE
        footerLabel = ui.text("", tokens.type.label, tokens.colors.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        footer.addView(footerLabel, ui.params(width = 0, weight = 1f))
        resumeButton = ui.button("继续播放") { target()?.let { host.navigator.toDetail(it.series.id, resumeRecording = true) } }
        watchedButton = ui.button("标记已看") { toggleWatched() }
        deleteButton = ui.button("删除记录") { deleteRecord() }
        // 焦点一离开就撤销确认：否则挪到"继续播放"再回来，一次按键就把记录删了
        deleteButton.onFocused = { focused ->
            if (!focused) {
                confirm.disarm()
                updateFooter()
            }
        }
        doneButton = ui.link("完成") { setManaging(false) }
        footer.addView(resumeButton, ui.params(rightGap = tokens.space.s))
        footer.addView(watchedButton, ui.params(rightGap = tokens.space.s))
        footer.addView(deleteButton, ui.params(rightGap = tokens.space.s))
        footer.addView(doneButton)

        reload()
    }

    // ---------- 数据 ----------

    private fun reload() {
        entries = host.progress.history()
        render()
    }

    private fun target(): HistoryEntry? = entries.firstOrNull { it.series.id == targetId }

    private fun render() {
        val isEmpty = entries.isEmpty()
        manageButton.visibility = if (isEmpty) View.GONE else View.VISIBLE
        rendered = if (isEmpty) null else grid.render(entries.map { CatalogCards.history(it, host.progress.isWatched(it.series.id)) })
        if (isEmpty) {
            pane.showEmpty("播放过的剧集会自动保存在这里")
            preview.visibility = View.GONE
            managing = false
            footer.visibility = View.GONE
            preview.show("", "", "")
        } else {
            pane.showContent()
            preview.visibility = View.VISIBLE
        }
        updateFooter()
        applyFocus()
    }

    private fun updateFooter() {
        val entry = target()
        footerLabel.text = if (entry == null) "在网格里选中一条记录" else "管理：《${entry.series.title}》"
        watchedButton.label(if (entry != null && host.progress.isWatched(entry.series.id)) "恢复未看" else "标记已看")
        deleteButton.label(if (entry != null && confirm.isArmed(entry.series.id)) "再按一次确认删除" else "删除记录")
    }

    // ---------- 交互 ----------

    private fun onCardFocused(seriesId: String) {
        if (targetId != seriesId) {
            confirm.disarm()
            targetId = seriesId
        }
        val entry = entries.firstOrNull { it.series.id == seriesId } ?: return
        // 收藏一季 = 收藏整个 IP：历史页预览的信息栏要说明这部剧在收藏里、更新到了哪
        val key = SeasonKeys.seasonKey(entry.series.title)
        val favoriteEntry = if (key.isBlank()) null else host.favorites.entry(key)
        preview.show(
            title = entry.series.title,
            status = listOf(
                CatalogText.previewStatus(
                    favorite = favoriteEntry != null,
                    season = entry.series.season,
                    progress = entry.progress,
                    watched = host.progress.isWatched(entry.series.id),
                ),
                favoriteEntry?.let { FavoriteLabels.updateBadge(it) },
            ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            meta = CatalogText.meta(entry.series),
        )
        if (managing) updateFooter()
    }

    private fun setManaging(value: Boolean) {
        if (managing == value) return
        managing = value
        confirm.disarm()
        if (value && targetId == null) targetId = entries.firstOrNull()?.series?.id
        footer.visibility = if (value) View.VISIBLE else View.GONE
        updateFooter()
        applyFocus()
    }

    private fun toggleWatched() {
        val entry = target() ?: return
        val next = !host.progress.isWatched(entry.series.id)
        scope.launch {
            host.progress.setWatched(entry.series.id, next)
            reload()
        }
    }

    private fun deleteRecord() {
        val entry = target() ?: return
        if (!confirm.press(entry.series.id)) {
            updateFooter()
            return
        }
        scope.launch {
            host.progress.removeHistory(entry.series.id)
            targetId = null
            // 删掉目标后退出管理态：留一个"选中的记录"已经不存在的动作条只会让人困惑
            managing = false
            footer.visibility = View.GONE
            reload()
            Toast.makeText(ui.context, "已删除《${entry.series.title}》的记录", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 焦点 ----------

    /** 首焦：第一条历史 → 当前导航项（空历史时停在「历史」上）。 */
    override fun startFocus(): List<FocusKey> =
        listOfNotNull(rendered?.keys?.firstOrNull(), FocusKey("nav:${CatalogTab.HISTORY.ordinal}"))

    private fun applyFocus() {
        val views = LinkedHashMap<FocusKey, View>()
        views.putAll(navViews())
        if (manageButton.visibility == View.VISIBLE) views[FocusKey("history:manage")] = manageButton
        rendered?.views?.let { views.putAll(it) }
        if (managing) {
            views[FocusKey("history:resume")] = resumeButton
            views[FocusKey("history:watched")] = watchedButton
            views[FocusKey("history:delete")] = deleteButton
            views[FocusKey("history:done")] = doneButton
        }

        val sections = listOf(
            navSection(),
            if (manageButton.visibility == View.VISIBLE) FocusSections.section(listOf(FocusKey("history:manage")), 1) else null,
            rendered?.let { FocusSections.section(it.keys, it.columns) },
            if (managing) {
                FocusSections.section(
                    listOf(
                        FocusKey("history:resume"),
                        FocusKey("history:watched"),
                        FocusKey("history:delete"),
                        FocusKey("history:done"),
                    ),
                    4,
                )
            } else {
                null
            },
        )
        applyPlan(sections, views)
    }
}
