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
import com.hongguotv.core.model.FavoriteEntry
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
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.TvChip
import com.hongguotv.lite.uikit.ui.TvInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 搜索页（B 方案，mockup 画面 1/2）。
 *
 * 从上到下：标题与说明 → 片库胶囊 → 输入行 → 候选词（有才占位）→ 内容区；底部信息栏。
 * 与 S9 版的三处不同：
 * - **片库固定在输入框上方、候选词固定在输入框下方**（B 方案用户故事：互相不被挤走）；
 * - **预览区退役**：聚焦卡的信息由底部 [InfoBand] 说——空态只写片名，结果态写"片名 + 已收藏 + 分类"；
 * - 「继续看」网格开**聚光**（非焦点卡 0.62），结果网格全亮（未上线卡用暗档表达）。
 */
internal class SearchPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.SEARCH) {

    private val model = host.searchModel

    private val filterChips = mutableListOf<TvChip>()
    private lateinit var input: TvInput
    private lateinit var searchButton: TvButton
    private lateinit var sortChip: TvChip
    private lateinit var suggestRow: LinearLayout
    private val suggestChips = mutableListOf<TvButton>()
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

        // 标题行：「搜索」28 + 说明 13，底对齐读作一行（mockup .b-title）
        val titleRow = ui.rowTop().apply { gravity = Gravity.BOTTOM }
        titleRow.addView(ui.text("搜索", tokens.type.pageTitle).apply {
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        })
        titleRow.addView(ui.text(TITLE_NOTE, tokens.type.caption, tokens.colors.muted).apply {
            includeFontPadding = false
            setPadding(ui.dp(tokens.space.m), 0, 0, ui.dp(tokens.space.xxs))
        })
        body.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 片库行（B 方案：固定在输入框上方）
        val filterRow = ui.row().apply { setPadding(0, ui.dp(tokens.space.m), 0, 0) }
        filterRow.addView(ui.text("片库", tokens.type.compact).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, ui.dp(tokens.space.l), 0)
        })
        ContentFilter.entries.forEachIndexed { index, filter ->
            val chip = ui.chip(SettingRows.filterLabel(filter)) { chooseFilter(filter) }
            filterChips += chip
            filterRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index < ContentFilter.entries.lastIndex) rightMargin = ui.dp(tokens.space.s)
            })
        }
        body.addView(filterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 输入行
        val searchRow = ui.row().apply { setPadding(0, ui.dp(tokens.space.m), 0, 0) }
        input = ui.input("输入完整剧名，按确认搜索").apply {
            setText(model.keyword)
            setSelection(text.length)
            addTextChangedListener(queryWatcher)
            setOnEditorActionListener { _, _, _ ->
                submit()
                true
            }
        }
        searchRow.addView(input, LinearLayout.LayoutParams(ui.dp(tokens.metrics.inputMaxWidth), ui.dp(tokens.metrics.inputHeight)).apply {
            rightMargin = ui.dp(tokens.space.m)
        })
        // 遥控器/电视上没有"键盘上的回车"可依赖：给一个看得见、按得着的搜索按钮。
        // 输入框里的 IME 搜索动作仍然保留，两种操作都能触发。
        searchButton = ui.button("搜索", variant = ButtonVariant.STANDARD) { submit() }
        searchRow.addView(searchButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            rightMargin = ui.dp(tokens.space.s)
        })
        sortChip = ui.chip(SettingRows.sortLabel(settings().seasonSortAsc), iconRes = Icons.SORT) { toggleSort() }
        searchRow.addView(sortChip)
        body.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 候选词（B 方案：固定在输入框下方）。只在真的有联想词时占位；
        // 行内容由 showSuggestions 整行重建（「候选」标签在那里加，这里不预置）
        suggestRow = ui.row().apply {
            setPadding(0, ui.dp(tokens.space.m), 0, 0)
            visibility = View.GONE
        }
        body.addView(suggestRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 内容区：滚动网格 + 居中空态，二态互斥
        pane = ui.contentPane()
        body.addView(pane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val content = pane.content

        retry = ui.button("重试") { if (model.keyword.isNotBlank()) runSearch(model.keyword) }
        retry.visibility = View.GONE
        pane.empty.actions.addView(retry)

        // 「继续看」组标题（mockup .b-rail2：18 粗 + 12.5 弱）
        continueHeader = ui.rowTop().apply {
            gravity = Gravity.BOTTOM
            setPadding(0, ui.dp(tokens.space.xl), 0, ui.dp(tokens.space.m))
            addView(ui.text("继续看", tokens.type.subtitle).apply {
                setTypeface(null, Typeface.BOLD)
                includeFontPadding = false
            })
            addView(ui.text("最近看过的几部", tokens.type.footnote, tokens.colors.muted).apply {
                includeFontPadding = false
                setPadding(ui.dp(tokens.space.m), 0, 0, 0)
            })
            visibility = View.GONE
        }
        content.addView(continueHeader)

        continueGrid = ui.grid("continue").apply {
            visibility = View.GONE
            // B 方案：「继续看」是背景墙，非焦点卡压暗（mockup 画面1 的 0.62）
            spotlight = true
            onCardFocused = { item -> showContinueBand(item) }
            onCardActivated = { item -> host.navigator.toDetail(item.id, resumeRecording = true) }
        }
        content.addView(continueGrid)

        resultGrid = ui.grid("result").apply {
            visibility = View.GONE
            // 结果是主角：全亮（mockup 画面2），未上线卡用 CardItem.dim 表达
            onCardFocused = { item -> showResultBand(item) }
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
        // 「候选」标签要一直在第一位：清空后重放
        suggestRow.addView(ui.text("候选", tokens.type.caption, tokens.colors.dim).apply {
            setPadding(0, 0, ui.dp(tokens.space.xs), 0)
        })
        list.forEachIndexed { index, suggestion ->
            val chip = ui.button(suggestion.keyword, variant = ButtonVariant.SMALL) { runSearch(suggestion.keyword) }
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
        // 否则框架会自己把它扔到某个位置（通常是侧栏），并被焦点记忆钉住
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

    // ---------- 底部信息栏 ----------

    /** 空态（继续看）：信息栏只写片名（mockup 画面1——卡片已写了"第几季第几集"）。 */
    private fun showContinueBand(item: CardItem) {
        val title = continueEntries.firstOrNull { it.series.id == item.id }?.series?.title ?: item.artworkTitle
        scaffold.band().show(
            title = title,
            segments = emptyList(),
            keys = listOf("◀▶" to "移动", "OK" to "打开"),
        )
    }

    /** 结果态：片名 + 已收藏 + 分类（mockup 画面2；MENU 提示等行为定了再加）。 */
    private fun showResultBand(item: CardItem) {
        val series = model.items.firstOrNull { it.id == item.id } ?: return
        scaffold.band().show(
            title = series.title,
            segments = CatalogText.bandSegments(favorite = favoriteOf(series) != null, tags = series.tags),
            keys = listOf("OK" to "打开"),
        )
    }

    /** 这一季所属的收藏记录（收藏一季 = 收藏整个 IP，所以按系列键查）。 */
    private fun favoriteOf(series: Series): FavoriteEntry? {
        val key = SeasonKeys.seasonKey(series.title)
        if (key.isBlank()) return null
        return host.favorites.entry(key)
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
        continueHeader.visibility = if (showContinue) View.VISIBLE else View.GONE
        continueGrid.visibility = if (showContinue) View.VISIBLE else View.GONE
        resultGrid.visibility = if (showResult) View.VISIBLE else View.GONE
        // 信息栏伴着内容出现：没有卡片可说时整条收起，不占 46dp
        if (showContinue || showResult) scaffold.band() else scaffold.hideBand()

        // 视口还没量到时不夹（保持比例）；量到之后由布局回调再走一遍 render
        applyCardLimit()
        continueRendered = if (showContinue) {
            continueGrid.render(continueEntries.map { CatalogCards.history(it, host.progress.isWatched(it.series.id)) })
        } else {
            null
        }
        rendered = if (showResult) resultGrid.render(model.items.map { CatalogCards.search(it) }) else null

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
     * 搜索完成、切回本页、页面重建都按这条走，不再落回侧栏（UI-02/03）。
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
        /**
         * 「继续看」最多 5 部 = 网格一行的列数（1080p 上）。
         * 多一个就会折到第二行，而第二行在首屏放不下——用户要么看不见，要么得先滚一下才知道还有。
         */
        const val CONTINUE_LIMIT = 5
        const val TITLE_NOTE = "输入完整剧名，按确认搜索 · 支持拼音联想"
    }
}
