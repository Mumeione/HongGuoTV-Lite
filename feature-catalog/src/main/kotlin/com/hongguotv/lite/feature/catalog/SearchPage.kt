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
import com.hongguotv.core.model.Series
import com.hongguotv.core.usecase.FavoriteLookup
import com.hongguotv.core.model.Settings
import com.hongguotv.core.model.Suggestion
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.ContentPane
import com.hongguotv.lite.uikit.page.GridRendered
import com.hongguotv.lite.uikit.page.PagerRow
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
 * 搜索页（B 方案，mockup 画面 1/2；2026-10-24 下午聚合改造）。
 *
 * 从上到下：标题与说明 → 片库胶囊 → 输入行 → 候选词（有才占位）→ 内容区（网格 + 翻页）；底部信息栏。
 * 与 S10 版的三处不同：
 * - **完整剧名聚合季线**（2026-10-24 规格）：季线整段进内存、只显示季线，翻页/正倒序不再反复打上游；
 * - **翻页行与卡片同层**（内容底部）：信息栏出现/收起不再推它上下；
 * - **结果卡不再被视口夹短**：按令牌自然比例显示，第二行探出一截滚动看全。
 */
internal class SearchPage(host: CatalogFeature) : CatalogPage(host, CatalogTab.SEARCH) {

    private val model = host.searchModel

    private val filterChips = mutableListOf<TvChip>()
    private lateinit var input: TvInput
    private lateinit var searchButton: TvButton
    private lateinit var sortChip: TvChip
    private lateinit var suggestRow: LinearLayout
    private lateinit var pager: PagerRow
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
        // 信息栏只在"焦点落定且落在卡片上"时出现：焦点在输入框/片库/侧栏上时收起，
        // 一帧内的中间态（焦点路过某张卡）不更新内容
        focus.onSettled = { key -> onCardSettled(key) }

        // 信息栏从第一帧起就预留 46dp：焦点进出卡片区时内容区高度不变，
        // 「继续看」的封面不会因为视口差 46dp 被重新夹一次（首屏卡片跳动）。
        scaffold.reserveBand()

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
        // 行内容由 showSuggestions 整行重建（「候选」标签在那里加，这里不预置）。
        // 底部留一档（2026-10-24 验收）：候选词与结果卡之间原来没有缝，两行文字像连成一块。
        suggestRow = ui.row().apply {
            setPadding(0, ui.dp(tokens.space.m), 0, ui.dp(tokens.space.m))
            visibility = View.GONE
        }
        body.addView(suggestRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 翻页行：官网服务端分页 + 内存滑窗，一页 10 张。
        // 位置在**结果网格之后、与卡片同层**（2026-10-24 口径）：信息栏出现/收起不再
        // 推它上下，翻了十个都没有想要的才往下翻到「下一页」。
        pager = PagerRow(ui, prevLabel = "‹ 上一页", nextLabel = "下一页 ›").apply {
            onPage = { delta -> goPage(delta) }
        }

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

        continueGrid = ui.grid(CONTINUE_SECTION).apply {
            visibility = View.GONE
            // B 方案：「继续看」是背景墙，非焦点卡压暗（mockup 画面1 的 0.62）
            spotlight = true
            onCardActivated = { item -> host.navigator.toDetail(item.id, resumeRecording = true) }
        }
        content.addView(continueGrid)

        resultGrid = ui.grid(RESULT_SECTION).apply {
            visibility = View.GONE
            // 一页最多两行 10 张：上游一页就是 10 条，剩下的靠翻页
            itemLimit = CatalogText.PAGE_SIZE
            // 结果是主角：全亮（mockup 画面2），未上线卡用 CardItem.dim 表达
            onCardActivated = { item -> openResult(item) }
        }
        // 结果卡与上方的搜索栏/候选词拉开一档（2026-10-24 验收：原来紧贴着，像输入框的下边框）
        content.addView(
            resultGrid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(tokens.metrics.gridHeaderGap)
            },
        )

        // 翻页行与卡片同层：跟着内容滚动，不随信息栏的出现/收起而移动
        content.addView(
            pager.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(tokens.space.s)
            },
        )

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

    /**
     * 搜索 / 翻页。
     *
     * [page] > 1 是翻页：**不清空当前页**，窗口数据在手就纯内存切换；
     * 缺数据才按序补拉服务端，失败也只提示、不把用户手里的结果换成一屏错误。
     * 第 1 页是新搜索，按旧口径清空并进入加载态；完整剧名命中锚点后
     * 追加一次季线请求做聚合（失败静默回退网页结果）。
     */
    private fun runSearch(keyword: String, filter: ContentFilter = settings().filter, page: Int = 1) {
        val clean = keyword.trim()
        if (clean.isEmpty() || clean.length > MAX_KEYWORD) return
        if (input.text.toString() != clean) {
            input.setText(clean)
            input.setSelection(clean.length)
        }
        val firstPage = page <= 1
        // 翻页时不动输入区：用户只是翻结果，键盘与候选词保持原样
        if (firstPage) {
            // setText 会触发联想防抖：这次搜索不需要联想词，按掉它
            suggestJob?.cancel()
            hideKeyboard()
            clearSuggestions()
            model.clearResult()
            continueEntries = emptyList()
        }
        model.keyword = clean
        model.loading = true
        render()

        searchJob?.cancel()
        searchJob = scope.launch {
            try {
                if (firstPage) {
                    val result = host.content.search(clean, filter, 1)
                    if (model.keyword != clean) return@launch
                    model.absorb(result)
                    // 完整剧名：先补季线，季线到手（或确认没有）再**一次性提交窗口**。
                    // 先提交网页结果再替换成季线，用户会看到"卡一下、少了很多选项"；
                    // 命中锚点说明这条搜索的目的就是只看这部剧，中间那十张不必上屏。
                    // 只捕 UpstreamError 不用 runCatching：取消必须原样传播。
                    // 单季剧（<2 季）没有"线"可言，保持网页结果。
                    val anchor = model.anchor()
                    if (anchor != null) {
                        render()
                        val line = try {
                            host.content.seasons(anchor.id, anchor)
                        } catch (error: UpstreamError) {
                            null
                        }
                        if (model.keyword != clean) return@launch
                        if (line != null && line.seasons.size >= 2) model.setSeasonLine(line.seasons)
                    }
                    model.loading = false
                    presentAndRender(1)
                } else {
                    // 翻页：窗口缺数据才补拉（按服务端顺序，去重要求前面的页在场）
                    while (!model.hasWindow(page)) {
                        val next = model.nextServerPage() ?: break
                        val fetched = host.content.search(clean, filter, next)
                        if (model.keyword != clean) return@launch
                        model.absorb(fetched)
                    }
                    presentAndRender(page)
                }
            } catch (error: UpstreamError) {
                if (model.keyword != clean) return@launch
                if (firstPage) {
                    model.error = error
                } else {
                    // 翻页失败：保留当前页，只提示一句。换成一屏错误等于把用户已看到的结果扔掉
                    toast(TvText.error(error))
                }
            } finally {
                // 取消时不要回写视图：页面可能已经被切换掉。
                // 成功路径已在提交前清掉 loading，这里不再多画一帧。
                if (isActive && model.loading) {
                    model.loading = false
                    render()
                }
            }
        }
    }

    /** 窗口切到 [target]、光标交给第一张卡（用户按翻页/搜索是要看结果，不是要回到搜索框）。 */
    private fun presentAndRender(target: Int) {
        model.present(target, settings().seasonSortAsc)
        preferredOnRender = model.items.firstOrNull()?.let { resultGrid.keyOf(it.id) }
        render()
    }

    /** 翻页：窗口在手直接内存切换，缺数据才走 [runSearch] 的补拉路径。 */
    private fun goPage(delta: Int) {
        val target = (model.page + delta).coerceIn(1, model.pageCount)
        if (target == model.page && model.items.isNotEmpty()) return
        runSearch(model.keyword, settings().filter, target)
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
        // 非聚合的网页结果没有可排的方向：开关此时是隐藏的，这里只是兜住程序性调用
        if (!model.aggregated) return
        val ascending = !settings().seasonSortAsc
        scope.launch { host.settings.update { it.copy(seasonSortAsc = ascending) } }
        sortChip.label(SettingRows.sortLabel(ascending))
        // 内存聚合视图整体重排（不发请求），页码不变
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

    /**
     * 焦点落定后的信息栏：落在「继续看」或结果网格的卡片上才显示，落到输入区/片库/侧栏
     * 整条收起。两个网格共用一个回调，按段名前缀分发。
     */
    private fun onCardSettled(key: FocusKey) {
        val continueId = continueGrid.cardIdOf(key)
        val resultId = resultGrid.cardIdOf(key)
        when {
            continueId != null -> showContinueBand(continueId)
            resultId != null -> showResultBand(resultId)
            else -> scaffold.hideBand()
        }
    }

    /** 空态（继续看）：信息栏只写片名（mockup 画面1——卡片已写了"第几季第几集"）。 */
    private fun showContinueBand(seriesId: String) {
        val title = continueEntries.firstOrNull { it.series.id == seriesId }?.series?.title
        if (title == null) {
            scaffold.hideBand()
            return
        }
        scaffold.band().show(
            title = title,
            segments = emptyList(),
            keys = listOf("◀▶" to "移动", "OK" to "打开"),
        )
    }

    /** 结果态：片名 + 已收藏 + 分类（mockup 画面2；MENU 提示等行为定了再加）。 */
    private fun showResultBand(seriesId: String) {
        val series = model.items.firstOrNull { it.id == seriesId }
        if (series == null) {
            scaffold.hideBand()
            return
        }
        val favorite = favoriteOf(series)
        scaffold.band().show(
            title = series.title,
            segments = CatalogText.searchSegments(
                series = series,
                // 聚合态与卡片同一口径：角标带「第N季」，不再退成"全 N 集"
                aggregated = model.aggregated,
                favorite = favorite != null,
                // 搜索卡片的分类偶尔缺（网页结果不带 category_schema），收藏行那份通常更全
                tags = CatalogText.historyTags(series.tags, favorite?.series?.tags.orEmpty()),
            ),
            keys = listOf("OK" to "打开"),
        )
    }

    /**
     * 这一季所属的收藏记录（收藏一季 = 收藏整个 IP）。
     *
     * 按系列键查之外还要看季线：分宗篇这类标题不带季号，键是它自己，与已收藏的正传对不上
     * ——只比键的话会显示成"没收藏"（2026-10-24 验收）。
     */
    private fun favoriteOf(series: Series): FavoriteEntry? = FavoriteLookup.entry(series, host.favorites)

    // ---------- 渲染 ----------

    /**
     * 命中完整剧名、季线还没到手：窗口还空着，先挂稳定占位。
     *
     * 这期间不画网页口径的翻页行（季线到手后页数通常会变），否则用户会看到第二次变化。
     */
    private fun awaitingSeasonLine(): Boolean =
        model.loading && model.items.isEmpty() && model.page == 1 && model.anchor() != null

    private fun render() {
        val current = settings()
        syncFilterChips(current.filter)
        sortChip.label(SettingRows.sortLabel(current.seasonSortAsc))
        // 正序/倒序只对聚合季线有意义：模糊词的网页结果整键隐藏，不摆一个按了没反应的开关
        sortChip.visibility = if (model.aggregated) View.VISIBLE else View.GONE

        val error = model.error
        val showContinue = model.keyword.isBlank() && error == null && continueEntries.isNotEmpty()
        val showResult = error == null && model.items.isNotEmpty()
        val emptyPage = model.hasSearched && model.page > 1 && model.items.isEmpty()
        val stateText = when {
            // 加载中但手里还有结果（翻页补拉）：旧窗口继续在屏上，不闪"正在搜索…"
            model.loading -> when {
                model.items.isNotEmpty() -> null
                // 命中完整剧名后补拉季线：给一句稳定的等待文案，季线到手一次成屏
                awaitingSeasonLine() -> "正在整理季线…"
                else -> "正在搜索…"
            }
            error != null -> TvText.error(error)
            showContinue || showResult -> null
            emptyPage && current.filter != ContentFilter.ALL ->
                "这一页没有「${SettingRows.filterLabel(current.filter)}」的结果，翻回上一页看看"
            emptyPage -> "这一页没有新的结果，翻回上一页看看"
            else -> CatalogText.emptySearch(model.keyword, current.filter)
        }

        if (stateText == null) pane.showContent() else pane.showEmpty(stateText)
        retry.visibility = if (error != null) View.VISIBLE else View.GONE
        continueHeader.visibility = if (showContinue) View.VISIBLE else View.GONE
        continueGrid.visibility = if (showContinue) View.VISIBLE else View.GONE
        resultGrid.visibility = if (showResult) View.VISIBLE else View.GONE
        // 翻页行只要"搜过且多页"就在（页脚）：结果为空的那一页也要留着它，否则用户翻不回去。
        // 等季线的那一帧除外：那时页数是网页口径，季线到手会变，先不画。
        val showPager = error == null && model.hasSearched && !awaitingSeasonLine() &&
            (model.pageCount > 1 || model.page > 1)
        if (showPager) {
            pager.show(
                CatalogText.searchRange(model.page, model.pageCount, model.estimatedTotal),
                hasPrev = model.page > 1,
                hasNext = model.hasNext,
            )
        } else {
            pager.hide()
        }
        // 信息栏伴着卡片出现：焦点落到卡片上时由落定回调显示；没有卡片可说时收起。
        // 本页预留了槽位高度（reserveBand），收起也不释放空间——内容区高度保持稳定。
        if (!showContinue && !showResult) scaffold.hideBand()

        // 视口还没量到时不夹（保持比例）；量到之后由布局回调再走一遍 render
        applyCardLimit()
        continueRendered = if (showContinue) {
            continueGrid.render(continueEntries.map { CatalogCards.history(it, host.progress.isWatched(it.series.id)) })
        } else {
            null
        }
        rendered = if (showResult) resultGrid.render(model.items.map { CatalogCards.search(it, model.aggregated) }) else null

        // 翻页预热：下一页的数据已在内存时先把封面取回来，翻过去直接命中；
        // 数据还没补拉（需要额外请求）就不预取——预热不能变成额外的上游流量。
        if (showResult) {
            resultGrid.prefetchCovers(
                model.pageItems(model.page + 1, current.seasonSortAsc)
                    .map { CatalogCards.search(it, model.aggregated) },
            )
        }

        applyFocus()
    }

    /**
     * 把内容区视口高度交给「继续看」网格：首屏只放得下一行卡时，封面按它夹短。
     *
     * **结果网格不再夹**（2026-10-24 口径）：视口强夹把两行硬塞首屏，封面被裁、
     * 卡片变小——结果卡按令牌自然比例显示，第二行自然探出一截、滚动看全。
     */
    private fun applyCardLimit() {
        if (viewport <= 0) return
        val header = if (continueHeader.visibility == View.VISIBLE) continueHeader.height else 0
        continueGrid.cardHeightLimit = (viewport - header).coerceAtLeast(0)
    }

    private fun syncFilterChips(selected: ContentFilter) {
        filterChips.forEachIndexed { index, chip -> chip.isSelected = ContentFilter.entries[index] == selected }
    }

    /**
     * 首焦：第一张结果卡 → 搜索框。
     * 搜索完成、切回本页、页面重建都按这条走，不再落回侧栏。
     */
    override fun startFocus(): List<FocusKey> =
        listOfNotNull(rendered?.keys?.firstOrNull() ?: continueRendered?.keys?.firstOrNull(), FocusKey("search:input"))

    /**
     * 输入行的可聚焦项。排序键只在聚合态出现：非聚合时它整键隐藏，
     * 焦点段与 views 都不能再包含它（否则程序性落焦会指向一个看不见的视图）。
     */
    private fun inputKeys(): List<FocusKey> = buildList {
        add(FocusKey("search:input"))
        add(FocusKey("search:go"))
        if (sortChip.visibility == View.VISIBLE) add(FocusKey("search:sort"))
    }

    private fun applyFocus() {
        val views = LinkedHashMap<FocusKey, View>()
        views.putAll(navViews())
        filterChips.forEachIndexed { index, chip -> views[FocusKey("filter:$index")] = chip }
        views[FocusKey("search:input")] = input
        views[FocusKey("search:go")] = searchButton
        if (sortChip.visibility == View.VISIBLE) views[FocusKey("search:sort")] = sortChip
        suggestChips.forEachIndexed { index, chip -> views[FocusKey("suggest:$index")] = chip }
        if (retry.visibility == View.VISIBLE) views[FocusKey("search:retry")] = retry
        // 翻页行：禁用的那一侧占空槽（第一页没有「上一页」，但「下一页」仍在第二列）
        val pagerSection = pager.focusSection(views, KEY_PREV, KEY_NEXT)
        continueRendered?.views?.let { views.putAll(it) }
        rendered?.views?.let { views.putAll(it) }
        val inputKeys = inputKeys()

        val sections = listOf(
            // 片库这一行只有半屏宽：按列号比例映射会落到「搜索」按钮上，显式指到输入框
            FocusSections.section(
                FocusSections.keys("filter", filterChips.size),
                filterChips.size,
                down = FocusKey("search:input"),
            ),
            // 输入行：排序键只在聚合态出现，列数跟着可见项走；
            // 按上一律回片库的「全部」——按列号映射会落到「短剧」。
            FocusSections.section(inputKeys, inputKeys.size, up = FocusKey("filter:0")),
            FocusSections.section(FocusSections.keys("suggest", suggestChips.size), suggestChips.size),
            if (retry.visibility == View.VISIBLE) FocusSections.section(listOf(FocusKey("search:retry")), 1) else null,
            continueRendered?.let { FocusSections.section(it.keys, it.columns) },
            rendered?.let { FocusSections.section(it.keys, it.columns) },
            // 翻页行与卡片同层（内容底部）：声明顺序 = 视觉顺序，网格之后声明
            pagerSection,
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
        /** 本页两个网格的焦点段名（`PosterGrid.keyOf` 的前缀）：落定回调按前缀分发信息栏。 */
        const val CONTINUE_SECTION = "continue"
        const val RESULT_SECTION = "result"
        /**
         * 「继续看」最多 5 部 = 网格一行的列数（1080p 上）。
         * 多一个就会折到第二行，而第二行在首屏放不下——用户要么看不见，要么得先滚一下才知道还有。
         */
        const val CONTINUE_LIMIT = 5
        const val TITLE_NOTE = "输入完整剧名，按确认搜索 · 支持拼音联想"
        val KEY_PREV = FocusKey("search:prev")
        val KEY_NEXT = FocusKey("search:next")
    }
}
