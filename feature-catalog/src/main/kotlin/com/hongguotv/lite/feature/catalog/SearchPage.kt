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
import android.widget.Toast
import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.Settings
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.ContentPane
import com.hongguotv.lite.uikit.page.GridRendered
import com.hongguotv.lite.uikit.page.PosterGrid
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.ui.ButtonVariant
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
 * 从上到下：顶栏 → 片库筛选 → 输入行 → 联想（有才占位）→ 预览（有卡片才占位）→ 内容区。
 * 筛选、输入行固定在上方；内容区（继续看 / 结果网格 / 空态）吃掉剩余高度。
 *
 * 两条 S9 的排版口径：
 * - **固定留白只在有内容时占位**：没有联想词时不保留一整行，没有卡片时不摆那块三行预览区，
 *   首屏高度要还给卡片（维护者原话：「不能看见卡片标题，这是现在的显著问题」）；
 * - **卡片高度按内容区视口夹取**：只放得下一行卡时，封面按 `CardFit` 夹短，
 *   保证「封面 + 两行标题 + 角标」都在首屏内。
 */
internal class SearchPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.SEARCH) {

    private val model = host.searchModel

    private val filterChips = mutableListOf<TvChip>()
    private lateinit var input: TvInput
    private lateinit var searchButton: TvButton
    private lateinit var sortChip: TvChip
    private lateinit var suggestRow: LinearLayout
    private val suggestChips = mutableListOf<TvButton>()
    private lateinit var preview: PreviewRail
    private lateinit var pane: ContentPane
    private lateinit var retry: TvButton
    private lateinit var continueHeader: LinearLayout
    private lateinit var continueGrid: PosterGrid
    private lateinit var resultGrid: PosterGrid

    private var rendered: GridRendered? = null
    private var continueRendered: GridRendered? = null
    private var continueEntries: List<HistoryEntry> = emptyList()
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    /** 内容区视口高（像素）：夹取卡片高度用。首次布局前是 0（不夹，保持比例）。 */
    private var viewport = 0

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
        // 遥控器/电视上没有"键盘上的回车"可依赖：给一个看得见、按得着的搜索按钮。
        // 输入框里的 IME 搜索动作仍然保留，两种操作都能触发。
        searchButton = ui.button("搜索", variant = ButtonVariant.PRIMARY) { submit() }
        searchRow.addView(searchButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ui.dp(tokens.metrics.inputHeight),
        ).apply {
            rightMargin = ui.dp(tokens.space.m)
        })
        sortChip = ui.chip(SettingRows.sortLabel(settings().seasonSortAsc), iconRes = Icons.SORT) { toggleSort() }
        searchRow.addView(sortChip)
        body.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 联想：只在真的有联想词时占位——没有词还留 44dp 空白，首屏就少半张卡
        suggestRow = ui.row().apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        body.addView(suggestRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(tokens.metrics.inputHeight)))

        // 预览：只在有卡片可聚焦时占位（空态/加载中/出错时把高度还给提示文字）
        preview = ui.preview().apply { visibility = View.GONE }
        body.addView(preview)

        // 内容区：滚动网格 + 居中空态，二态互斥
        pane = ui.contentPane()
        body.addView(pane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val content = pane.content

        retry = ui.button("重试") { if (model.keyword.isNotBlank()) runSearch(model.keyword) }
        retry.visibility = View.GONE
        pane.empty.actions.addView(retry)

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

        // 内容区按真实视口夹一次卡片高度：布局前量不到，只能先按比例画一帧，
        // 首帧之后（下一次布局）再来一次——卡片就地改高度，不重建视图，焦点不受影响
        pane.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val height = view.height
            if (height <= 0 || height == viewport) return@addOnLayoutChangeListener
            view.post {
                viewport = height
                render()
            }
        }

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
                // 结果到手就把光标交给第一张卡：用户按确认是要看结果，不是要回到搜索框
                preferredOnRender = model.items.firstOrNull()?.let { resultGrid.keyOf(it.id) }
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
        suggestRow.visibility = if (suggestChips.isEmpty()) View.GONE else View.VISIBLE
        applyFocus()
    }

    private fun clearSuggestions() {
        if (suggestChips.isEmpty() && suggestRow.childCount == 0) {
            suggestRow.visibility = View.GONE
            return
        }
        // 联想里正被聚焦的那一项马上要被删掉：先把光标交回输入框，
        // 否则框架会自己把它扔到某个位置（通常是顶栏），并被焦点记忆钉住
        val hadFocus = suggestChips.any { it.isFocused }
        suggestChips.clear()
        suggestRow.removeAllViews()
        suggestRow.visibility = View.GONE
        if (hadFocus) preferredOnRender = FocusKey("search:input")
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

    /** 搜索卡那一行收藏状态：收藏了就说"更新到哪里"（与收藏页同一口径），没收藏留空。 */
    private fun favoriteBadgeOf(series: Series): String {
        val key = SeasonKeys.seasonKey(series.title)
        if (key.isBlank() || !host.favorites.isFavorite(key)) return ""
        val entry = host.favorites.entry(key) ?: return "已收藏"
        return FavoriteLabels.updateBadge(entry)
    }

    private fun previewHistory(item: CardItem) {
        val entry = continueEntries.firstOrNull { it.series.id == item.id } ?: return
        // 继续看的剧也可能是收藏的整个 IP：预览的信息栏要一并说出来
        val favorite = host.favorites.isFavorite(SeasonKeys.seasonKey(entry.series.title))
        preview.show(
            title = entry.series.title,
            status = CatalogText.previewStatus(
                favorite = favorite,
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
        val stateText = when {
            model.loading -> "正在搜索…"
            error != null -> TvText.error(error)
            showContinue || showResult -> null
            else -> CatalogText.emptySearch(model.keyword, current.filter)
        }

        if (stateText == null) pane.showContent() else pane.showEmpty(stateText)
        retry.visibility = if (error != null) View.VISIBLE else View.GONE
        preview.visibility = if (showContinue || showResult) View.VISIBLE else View.GONE
        continueHeader.visibility = if (showContinue) View.VISIBLE else View.GONE
        continueGrid.visibility = if (showContinue) View.VISIBLE else View.GONE
        resultGrid.visibility = if (showResult) View.VISIBLE else View.GONE

        // 视口还没量到时不夹（保持比例）；量到之后由布局回调再走一遍 render
        applyCardLimit()
        continueRendered = if (showContinue) {
            // 继续看的卡不带片名也不带预览兜底：季号自己带上，"第九季 · 第 20 集 · 05:00"
            continueGrid.render(continueEntries.map { CatalogCards.history(it, host.progress.isWatched(it.series.id), withSeason = true) })
        } else {
            null
        }
        rendered = if (showResult) resultGrid.render(model.items.map { CatalogCards.search(it, favoriteBadgeOf(it)) }) else null

        if (!showContinue && !showResult) preview.show("", "", "")
        applyFocus()
    }

    /**
     * 把内容区视口高度交给两个网格：首屏只放得下一行卡时，封面按它夹短。
     *
     * 「继续看」上方还有一行小标题，那一块要从可用高度里扣掉；首帧量不到它的高度时先不扣，
     * 下一次布局会按实测值重算（卡片就地改高度，不重建、不丢焦点）。
     */
    private fun applyCardLimit() {
        if (viewport <= 0) return
        resultGrid.cardHeightLimit = viewport
        val header = if (continueHeader.visibility == View.VISIBLE) continueHeader.height else 0
        continueGrid.cardHeightLimit = (viewport - header).coerceAtLeast(0)
    }

    private fun syncFilterChips(selected: ContentFilter) {
        filterChips.forEachIndexed { index, chip -> chip.isSelected = ContentFilter.entries[index] == selected }
    }

    /**
     * 首焦：第一张结果卡 → 搜索框。
     * 搜索完成、切回本页、页面重建都按这条走，不再落回顶栏（UI-02/03）。
     */
    override fun startFocus(): List<FocusKey> =
        listOfNotNull(rendered?.keys?.firstOrNull() ?: continueRendered?.keys?.firstOrNull(), FocusKey("search:input"))

    private fun applyFocus() {
        val views = LinkedHashMap<FocusKey, View>()
        views.putAll(navViews())
        filterChips.forEachIndexed { index, chip -> views[FocusKey("filter:$index")] = chip }
        views[FocusKey("search:input")] = input
        views[FocusKey("search:go")] = searchButton
        views[FocusKey("search:sort")] = sortChip
        suggestChips.forEachIndexed { index, chip -> views[FocusKey("suggest:$index")] = chip }
        if (retry.visibility == View.VISIBLE) views[FocusKey("search:retry")] = retry
        continueRendered?.views?.let { views.putAll(it) }
        rendered?.views?.let { views.putAll(it) }

        val sections = listOf(
            navSection(),
            FocusSections.section(FocusSections.keys("filter", filterChips.size), filterChips.size),
            FocusSections.section(
                listOf(FocusKey("search:input"), FocusKey("search:go"), FocusKey("search:sort")),
                3,
            ),
            FocusSections.section(FocusSections.keys("suggest", suggestChips.size), suggestChips.size),
            if (retry.visibility == View.VISIBLE) FocusSections.section(listOf(FocusKey("search:retry")), 1) else null,
            continueRendered?.let { FocusSections.section(it.keys, it.columns) },
            rendered?.let { FocusSections.section(it.keys, it.columns) },
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
