package com.hongguotv.lite.feature.catalog

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.Settings
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.page.GridRendered
import com.hongguotv.lite.uikit.page.PosterGrid
import com.hongguotv.lite.uikit.ui.CardItem
import com.hongguotv.lite.uikit.ui.Icons
import com.hongguotv.lite.uikit.ui.PreviewRail
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.TvChip
import com.hongguotv.lite.uikit.ui.TvInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 搜索页。
 *
 * 从上到下：顶栏 → 片库筛选 → 输入行 → 联想 → 预览 → 结果网格（或"继续看"/空态）。
 * 筛选、输入行、联想都固定在上方（不随结果滚动）；预览固定，网格单独滚。
 */
internal class SearchPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.SEARCH) {

    private val model = host.searchModel

    private val filterChips = mutableListOf<TvChip>()
    private lateinit var input: TvInput
    private lateinit var sortChip: TvChip
    private lateinit var suggestRow: LinearLayout
    private val suggestChips = mutableListOf<TvButton>()
    private lateinit var preview: PreviewRail
    private lateinit var stateView: TextView
    private lateinit var retry: TvButton
    private lateinit var continueHeader: LinearLayout
    private lateinit var continueGrid: PosterGrid
    private lateinit var resultGrid: PosterGrid

    private var rendered: GridRendered? = null
    private var continueRendered: GridRendered? = null
    private var continueEntries: List<HistoryEntry> = emptyList()
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    override fun onBuild() {
        val body = scaffold.body()

        // 片库筛选：整行居中，与顶栏同一视觉轴
        val filterRow = ui.row().apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(tokens.space.m), 0, ui.dp(tokens.space.m))
        }
        filterRow.addView(ui.text("片库", tokens.type.subtitle).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, ui.dp(tokens.space.xl), 0)
        })
        ContentFilter.entries.forEachIndexed { index, filter ->
            val chip = ui.chip(SettingRows.filterLabel(filter)) { chooseFilter(filter) }
            filterChips += chip
            filterRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index < ContentFilter.entries.lastIndex) rightMargin = ui.dp(tokens.space.s)
            })
        }
        body.addView(filterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 输入行：输入框 + 排序。输入完按确认就搜，不另放"搜索"按钮。
        val searchRow = ui.row().apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ui.dp(tokens.space.s))
        }
        input = ui.input("输入完整剧名，按确认搜索").apply {
            setText(model.keyword)
            setSelection(text.length)
            addTextChangedListener(queryWatcher)
            setOnEditorActionListener { _, _, _ ->
                submit()
                true
            }
        }
        val inputWidth = minOf(ui.dp(tokens.metrics.inputMaxWidth), (ui.context.resources.displayMetrics.widthPixels * tokens.metrics.inputWidthRatio).toInt())
        searchRow.addView(input, LinearLayout.LayoutParams(inputWidth, ui.dp(tokens.metrics.inputHeight)).apply {
            rightMargin = ui.dp(tokens.space.m)
        })
        sortChip = ui.chip(SettingRows.sortLabel(settings().seasonSortAsc), iconRes = Icons.SORT) { toggleSort() }
        searchRow.addView(sortChip)
        body.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 联想：固定高度的一行，出现/消失时下面不会整体跳一下
        suggestRow = ui.row().apply { gravity = Gravity.CENTER }
        body.addView(suggestRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(tokens.metrics.inputHeight)))

        preview = ui.preview()
        body.addView(preview)

        val content = ui.column()
        val scroll = ui.scroll(content)
        ui.keepFocusVisible(scroll)
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        stateView = ui.text("", tokens.type.subtitle, tokens.colors.muted).apply {
            setPadding(0, ui.dp(tokens.space.xxl), 0, ui.dp(tokens.space.xl))
            visibility = View.GONE
        }
        content.addView(stateView)

        retry = ui.button("重试") { if (model.keyword.isNotBlank()) runSearch(model.keyword) }
        retry.visibility = View.GONE
        content.addView(retry)

        continueHeader = ui.row().apply {
            setPadding(0, ui.dp(tokens.space.s), 0, ui.dp(tokens.space.m))
            addView(ui.text("继续看", tokens.type.subtitle).apply { setTypeface(null, Typeface.BOLD) })
            addView(ui.text("最近看过的几部", tokens.type.caption, tokens.colors.muted).apply {
                setPadding(ui.dp(tokens.space.m), ui.dp(tokens.space.xxs), 0, 0)
            })
            visibility = View.GONE
        }
        content.addView(continueHeader)

        continueGrid = ui.grid("continue").apply {
            visibility = View.GONE
            onCardFocused = { item -> previewHistory(item) }
            onCardActivated = { item -> host.navigator.toDetail(item.id, resumeRecording = true) }
        }
        content.addView(continueGrid)

        resultGrid = ui.grid("result").apply {
            visibility = View.GONE
            onCardFocused = { item -> previewResult(item) }
            onCardActivated = { item -> openResult(item) }
        }
        content.addView(resultGrid)

        if (model.keyword.isBlank()) continueEntries = host.progress.history(limit = CONTINUE_LIMIT)
        render()
    }

    // ---------- 交互 ----------

    private fun settings(): Settings = host.settings.current()

    private fun submit() {
        val keyword = input.text.toString().trim()
        if (keyword.isEmpty()) {
            toast("先输入剧名，再按确认搜索")
            input.requestFocus()
            return
        }
        runSearch(keyword)
    }

    private fun runSearch(keyword: String, filter: ContentFilter = settings().filter) {
        val clean = keyword.trim()
        if (clean.isEmpty() || clean.length > MAX_KEYWORD) return
        if (input.text.toString() != clean) {
            input.setText(clean)
            input.setSelection(clean.length)
        }
        // setText 会触发联想防抖：这次搜索不需要联想词，按掉它
        suggestJob?.cancel()
        hideKeyboard()
        clearSuggestions()

        model.keyword = clean
        model.clearResult()
        model.loading = true
        continueEntries = emptyList()
        render()

        searchJob?.cancel()
        searchJob = scope.launch {
            try {
                val result = host.content.search(clean, filter)
                if (model.keyword != clean) return@launch
                model.apply(result, settings().seasonSortAsc)
            } catch (error: UpstreamError) {
                if (model.keyword != clean) return@launch
                model.error = error
            } finally {
                // 取消时不要回写视图：页面可能已经被切换掉
                if (isActive) {
                    model.loading = false
                    render()
                }
            }
        }
    }

    private val queryWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onQueryEdited(s?.toString().orEmpty())
    }

    private fun onQueryEdited(raw: String) {
        val keyword = raw.trim()
        suggestJob?.cancel()
        if (keyword.length < SUGGEST_MIN_LENGTH || keyword == model.keyword) {
            clearSuggestions()
            return
        }
        suggestJob = scope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            // 联想只是输入辅助：失败静默，不打断打字（旧行为）。取消异常不在这里吞掉。
            val list = try {
                host.content.suggest(keyword)
            } catch (error: UpstreamError) {
                return@launch
            }
            // 打字还在继续：这次联想已经过期
            if (input.text.toString().trim() != keyword) return@launch
            showSuggestions(list.take(SUGGEST_LIMIT))
        }
    }

    private fun showSuggestions(list: List<Suggestion>) {
        suggestRow.removeAllViews()
        suggestChips.clear()
        list.forEachIndexed { index, suggestion ->
            val chip = ui.button(suggestion.keyword) { runSearch(suggestion.keyword) }
            suggestRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index < list.lastIndex) rightMargin = ui.dp(tokens.space.s)
            })
            suggestChips += chip
        }
        applyFocus()
    }

    private fun clearSuggestions() {
        if (suggestChips.isEmpty() && suggestRow.childCount == 0) return
        suggestChips.clear()
        suggestRow.removeAllViews()
        applyFocus()
    }

    private fun chooseFilter(filter: ContentFilter) {
        val current = settings()
        if (current.filter == filter) return
        scope.launch { host.settings.update { it.copy(filter = filter) } }
        syncFilterChips(filter)
        if (model.keyword.isNotBlank()) {
            runSearch(model.keyword, filter)
        } else {
            model.error = null
            render()
        }
    }

    private fun toggleSort() {
        val ascending = !settings().seasonSortAsc
        scope.launch { host.settings.update { it.copy(seasonSortAsc = ascending) } }
        sortChip.label(SettingRows.sortLabel(ascending))
        model.reorder(ascending)
        render()
    }

    private fun openResult(item: CardItem) {
        val series = model.items.firstOrNull { it.id == item.id } ?: return
        if (!series.released) {
            toast("《${series.title}》尚未上线")
            return
        }
        host.navigator.toDetail(series.id)
    }

    private fun previewResult(item: CardItem) {
        val series = model.items.firstOrNull { it.id == item.id } ?: return
        val favorite = host.favorites.isFavorite(SeasonKeys.seasonKey(series.title))
        preview.show(
            title = series.title,
            status = CatalogText.previewStatus(
                favorite = favorite,
                season = series.season,
                progress = host.progress.progressOf(series.id),
                watched = host.progress.isWatched(series.id),
            ),
            meta = CatalogText.meta(series),
        )
    }

    private fun previewHistory(item: CardItem) {
        val entry = continueEntries.firstOrNull { it.series.id == item.id } ?: return
        preview.show(
            title = entry.series.title,
            status = CatalogText.previewStatus(
                favorite = false,
                season = entry.series.season,
                progress = entry.progress,
                watched = host.progress.isWatched(entry.series.id),
            ),
            meta = CatalogText.meta(entry.series),
        )
    }

    // ---------- 渲染 ----------

    private fun render() {
        val current = settings()
        syncFilterChips(current.filter)
        sortChip.label(SettingRows.sortLabel(current.seasonSortAsc))

        val error = model.error
        val showContinue = model.keyword.isBlank() && error == null && continueEntries.isNotEmpty()
        val showResult = error == null && model.items.isNotEmpty()
        val showState = model.loading || error != null || (!showContinue && !showResult)

        stateView.visibility = if (showState) View.VISIBLE else View.GONE
        stateView.text = when {
            model.loading -> "正在搜索…"
            error != null -> CatalogText.error(error)
            showContinue || showResult -> ""
            else -> CatalogText.emptySearch(model.keyword, current.filter)
        }
        retry.visibility = if (error != null) View.VISIBLE else View.GONE
        continueHeader.visibility = if (showContinue) View.VISIBLE else View.GONE
        continueGrid.visibility = if (showContinue) View.VISIBLE else View.GONE
        resultGrid.visibility = if (showResult) View.VISIBLE else View.GONE

        continueRendered = if (showContinue) {
            continueGrid.render(continueEntries.map { CatalogCards.history(it, host.progress.isWatched(it.series.id)) })
        } else {
            null
        }
        rendered = if (showResult) resultGrid.render(model.items.map(CatalogCards::search)) else null

        if (!showContinue && !showResult) preview.show("", "", "")
        applyFocus()
    }

    private fun syncFilterChips(selected: ContentFilter) {
        filterChips.forEachIndexed { index, chip -> chip.isSelected = ContentFilter.entries[index] == selected }
    }

    private fun applyFocus() {
        val views = LinkedHashMap<FocusKey, View>()
        views.putAll(navViews())
        filterChips.forEachIndexed { index, chip -> views[FocusKey("filter:$index")] = chip }
        views[FocusKey("search:input")] = input
        views[FocusKey("search:sort")] = sortChip
        suggestChips.forEachIndexed { index, chip -> views[FocusKey("suggest:$index")] = chip }
        if (retry.visibility == View.VISIBLE) views[FocusKey("search:retry")] = retry
        continueRendered?.views?.let { views.putAll(it) }
        rendered?.views?.let { views.putAll(it) }

        val sections = listOf(
            navSection(),
            CatalogSections.section(CatalogSections.keys("filter", filterChips.size), filterChips.size),
            CatalogSections.section(listOf(FocusKey("search:input"), FocusKey("search:sort")), 2),
            CatalogSections.section(CatalogSections.keys("suggest", suggestChips.size), suggestChips.size),
            if (retry.visibility == View.VISIBLE) CatalogSections.section(listOf(FocusKey("search:retry")), 1) else null,
            continueRendered?.let { CatalogSections.section(it.keys, it.columns) },
            rendered?.let { CatalogSections.section(it.keys, it.columns) },
        )
        applyPlan(sections, views)
    }

    // ---------- 平台小工具 ----------

    private fun hideKeyboard() {
        val manager = ui.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(ui.context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MAX_KEYWORD = 80
        const val SUGGEST_MIN_LENGTH = 2
        const val SUGGEST_LIMIT = 4
        const val SUGGEST_DEBOUNCE_MS = 400L
        const val CONTINUE_LIMIT = 6
    }
}
