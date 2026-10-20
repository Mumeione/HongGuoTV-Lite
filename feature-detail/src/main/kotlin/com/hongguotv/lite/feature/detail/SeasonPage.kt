package com.hongguotv.lite.feature.detail

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.usecase.SeasonProgress
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.layout.GridGeometry
import com.hongguotv.lite.uikit.page.PageHeader
import com.hongguotv.lite.uikit.page.TwoStepConfirm
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.PosterCard
import com.hongguotv.lite.uikit.ui.TvButton
import com.hongguotv.lite.uikit.ui.TvLink
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 收藏系列页：一个系列的全部季（季线），以及"继续播放"该落在哪一季。
 *
 * 收藏的是**系列**，进度是按**季**存的：这一页的职责就是把两者对上——
 * 哪一季是当前季、每一季我看到哪、点确认键该回到哪一季的哪一集。
 *
 * 数据按季键读（`entry` + `seasonLine`）；季线不在缓存里时自取一次，
 * 否则 S7 的 `toSeries` 路由会依赖"进页面前季线刚好还在 LRU 里"这种巧合。
 */
internal class SeasonPage(
    host: DetailFeature,
    private val seriesKey: String,
) : DetailPage(host, "series:$seriesKey") {

    private lateinit var header: PageHeader
    private lateinit var stateView: TextView
    private lateinit var retryButton: TvButton
    private lateinit var detailOnlyButton: TvButton
    private lateinit var hero: HeroBlock
    private lateinit var playButton: TvButton
    private lateinit var pickButton: TvButton
    private lateinit var favoriteButton: TvButton
    private lateinit var introButton: TvButton
    private lateinit var seasonHeader: LinearLayout
    private lateinit var seasonCountView: TextView
    private lateinit var sortLink: TvLink
    private lateinit var seasonHost: LinearLayout
    private lateinit var pager: PagerRow
    private lateinit var footerNote: TextView

    private var entry: FavoriteEntry? = null
    private var line: SeasonLine? = null
    private var anchor: Series? = null
    private var lineFailure: Throwable? = null
    private var fetching = false
    private var singleSeason: Series? = null
    private var pageIndex = 0
    private var introExpanded = false

    /** 首屏拿到数据后光标落在主操作上：进这一页是要接着看，不是要看「返回」。 */
    private var preferredOnRender: FocusKey? = null
    private val seasonCards = LinkedHashMap<String, PosterCard>()
    private val confirm = TwoStepConfirm()

    override fun build() {
        // 季键就是系列基名，先用它顶一下；拿到收藏行后用带标点的原标题
        header = scaffold.showHeader(seriesKey) { host.navigator.back() }
        val body = scaffold.scrollBody()

        stateView = ui.text("", tokens.type.subtitle, tokens.colors.muted).apply {
            setPadding(0, ui.dp(tokens.space.xxl), 0, ui.dp(tokens.space.l))
        }
        body.addView(stateView)
        retryButton = ui.button("重试") { load() }.apply { visibility = View.GONE }
        body.addView(retryButton)
        detailOnlyButton = ui.button("查看剧集详情") { anchor?.let { host.navigator.toDetail(it.id) } }
            .apply { visibility = View.GONE }
        body.addView(detailOnlyButton)

        hero = HeroBlock(ui)
        playButton = ui.button("开始播放", variant = ButtonVariant.PRIMARY) { play() }
        hero.actions.addView(playButton, ui.params(rightGap = tokens.space.s))
        pickButton = ui.button("选集") { openCurrentSeason() }
        hero.actions.addView(pickButton, ui.params(rightGap = tokens.space.s))
        favoriteButton = ui.button("取消收藏") { toggleFavorite() }
        // 焦点一离开就撤销确认：挪到「继续播放」再回来，一次按键不该把收藏取消掉
        favoriteButton.onFocused = { focused ->
            if (!focused && confirm.isArmed(seriesKey)) {
                confirm.disarm()
                renderFavoriteButton()
            }
        }
        hero.actions.addView(favoriteButton, ui.params(rightGap = tokens.space.s))
        introButton = ui.button("完整简介") { toggleIntro() }
        hero.actions.addView(introButton)
        body.addView(hero.view)

        seasonHeader = ui.row().apply {
            visibility = View.GONE
            setPadding(0, ui.dp(tokens.space.l), 0, ui.dp(tokens.space.s))
        }
        seasonCountView = ui.text("", tokens.type.subtitle, tokens.colors.text).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        seasonHeader.addView(seasonCountView, ui.params(width = 0, weight = 1f))
        sortLink = ui.link("倒序") { toggleSort() }
        seasonHeader.addView(sortLink)
        body.addView(seasonHeader)

        seasonHost = ui.column().apply { visibility = View.GONE }
        body.addView(seasonHost)

        pager = PagerRow(ui).apply { onPage = { delta -> goPage(delta) } }
        body.addView(pager.view)

        footerNote = ui.text(DetailText.SEASON_FOOTER, tokens.type.caption, tokens.colors.muted).apply {
            visibility = View.GONE
            setPadding(0, ui.dp(tokens.space.l), 0, 0)
        }
        body.addView(footerNote)

        load()
    }

    // ---------- 数据 ----------

    private fun load() {
        lineFailure = null
        singleSeason = null
        entry = host.favorites.entry(seriesKey)
        line = host.favorites.seasonLine(seriesKey)
        anchor = entry?.series
            ?: line?.seasons?.lastOrNull { it.released }
            ?: line?.seasons?.lastOrNull()
        pageIndex = 0
        preferredOnRender = if (anchor != null) KEY_PLAY else null
        render()
        if (anchor != null && line == null) fetchLine()
    }

    private fun fetchLine() {
        val series = anchor ?: return
        fetching = true
        render()
        scope.launch {
            val fetched = try {
                host.content.seasons(series.id, series)
            } catch (error: UpstreamError) {
                lineFailure = error
                null
            }
            if (!isActive) return@launch
            fetching = false
            when {
                fetched == null -> Unit
                // 单季没有"线"可言：端口不落库，页面降级成"去看剧集详情"
                fetched.seasons.size >= 2 -> {
                    host.favorites.rememberSeasonLine(seriesKey, fetched)
                    line = fetched
                }
                else -> singleSeason = fetched.seasons.firstOrNull() ?: series
            }
            render()
        }
    }

    // ---------- 渲染 ----------

    private fun render() {
        val series = anchor
        val ready = line != null

        hero.view.visibility = if (series == null) View.GONE else View.VISIBLE
        seasonHeader.visibility = if (ready) View.VISIBLE else View.GONE
        seasonHost.visibility = if (ready) View.VISIBLE else View.GONE
        footerNote.visibility = if (ready) View.VISIBLE else View.GONE

        if (series == null) {
            header.title(seriesKey)
        } else {
            header.title(titleText(series))
            renderHero(series)
        }

        val seasons = line
        if (seasons == null) {
            seasonHost.removeAllViews()
            seasonCards.clear()
            pager.hide()
        } else {
            renderSeasons(seasons)
        }
        renderState(series)
        applyFocus()
    }

    /** 收藏行存的就是系列基名；只有"季线缓存里有、收藏里没有"时才需要现算一次。 */
    private fun titleText(series: Series): String = entry?.series?.title ?: SeasonKeys.seasonBase(series.title)

    /**
     * 季线展示序：落库是旧→新（端口契约），展示层才反转。
     * 默认"新→旧"（最新一季在最左），用户想从头补看时切成正序——和搜索页同一个开关。
     */
    private fun orderedSeasons(seasons: SeasonLine): List<Series> =
        if (host.settings.current().seasonSortAsc) seasons.seasons else seasons.seasons.asReversed()

    private fun renderHero(series: Series) {
        val progress = latestProgress()
        val target = SeasonTargets.of(line, progress, anchor)

        hero.show(
            coverUrl = series.cover,
            title = titleText(series),
            meta = DetailText.seriesMeta(series, line?.seasons?.size ?: 1, favorite = entry != null),
            // 记录所属的季不在季线里时，退回"收藏的那一季"的季号，别报成最新季
            status = DetailText.seriesStatus(progress, line, entry?.series?.season ?: 0),
            intro = series.intro.ifBlank { "选择一季开始观看" },
            introExpanded = introExpanded,
        )
        introButton.visibility = if (series.intro.isBlank()) View.GONE else View.VISIBLE
        introButton.label(if (introExpanded) "收起简介" else "完整简介")
        playButton.label(target.label)
        renderFavoriteButton()
    }

    private fun renderFavoriteButton() {
        favoriteButton.label(
            when {
                entry == null -> "收藏"
                // 说明"进度不丢"：取消收藏只影响收藏页，不删任何观看记录
                confirm.isArmed(seriesKey) -> "再按一次确认（进度保留）"
                else -> "取消收藏"
            },
        )
    }

    private fun renderSeasons(seasons: SeasonLine) {
        val ordered = orderedSeasons(seasons)
        val pages = seasonPages(ordered.size)
        pageIndex = pageIndex.coerceIn(0, pages - 1)
        val slice = ordered.drop(pageIndex * SEASON_COLUMNS).take(SEASON_COLUMNS)

        val gap = ui.dp(tokens.metrics.cardGap)
        // 卡宽 = 可用宽度按 8 列均分（像素）：8 列是产品口径，不能像海报网格那样按卡宽反推列数
        val cardWidth = GridGeometry.cardWidth(ui.availableWidth(seasonHost.width), SEASON_COLUMNS, gap)

        seasonHost.removeAllViews()
        seasonCards.clear()
        val row = ui.rowTop()
        slice.forEachIndexed { position, season ->
            val card = ui.poster(cardWidth).apply {
                // 收藏的封面值得占电视存储（旧行为）：这一页复看频率高、条目稳定
                persistCover(true)
                bind(
                    DetailCards.season(
                        season = season,
                        current = season.id == entry?.series?.id,
                        progress = host.progress.progressOf(season.id),
                        watched = host.progress.isWatched(season.id),
                    ),
                )
                onActivated = { openSeason(season) }
            }
            row.addView(
                card,
                LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (position < slice.lastIndex) rightMargin = gap
                },
            )
            seasonCards[season.id] = card
        }
        seasonHost.addView(row)

        seasonCountView.text = DetailText.seasonLineTitle(seasons.seasons.size)
        sortLink.label(if (host.settings.current().seasonSortAsc) "正序" else "倒序")

        if (pages > 1) {
            val label = DetailText.seasonRangeLabel(
                firstSeason = slice.firstOrNull()?.season ?: 0,
                lastSeason = slice.lastOrNull()?.season ?: 0,
            )
            pager.show(label, hasPrev = pageIndex > 0, hasNext = pageIndex < pages - 1)
        } else {
            pager.hide()
        }
    }

    private fun renderState(series: Series?) {
        val message = when {
            series == null -> "这个系列暂时没有可用的季信息，返回上一页再进来试试"
            lineFailure != null -> TvText.error(lineFailure!!)
            singleSeason != null -> "这个系列只有一季，直接看剧集详情"
            line == null && fetching -> "正在获取季线…"
            else -> ""
        }
        stateView.text = message
        stateView.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        retryButton.visibility = if (series != null && lineFailure != null) View.VISIBLE else View.GONE
        detailOnlyButton.visibility = if (singleSeason != null) View.VISIBLE else View.GONE
    }

    // ---------- 交互 ----------

    private fun latestProgress() =
        SeasonProgress.latest(SeasonProgress.seriesIds(entry, line)) { host.progress.progressOf(it) }

    private fun play() {
        val target = SeasonTargets.of(line, latestProgress(), anchor)
        val series = target.series ?: return
        // 整季看完且没有下一季：进详情页重挑集，而不是从第一集再放一遍
        if (target.direct) host.navigator.toPlayer(series.id, target.index, target.positionMs)
        else host.navigator.toDetail(series.id)
    }

    private fun openCurrentSeason() {
        val target = SeasonTargets.of(line, latestProgress(), anchor)
        val series = target.series ?: anchor ?: return
        host.navigator.toDetail(series.id)
    }

    private fun openSeason(season: Series) {
        if (!season.released) {
            toast("《${season.title}》尚未上线")
            return
        }
        // 点了哪一季，那一季就成为收藏的"当前季"：卡片角标与「继续播放」都跟着它。
        // 挂在 host 的作用域上——页面马上要被路由销毁，挂在页面作用域会连写都没写就被取消
        host.scope.launch { host.favorites.setCurrentSeason(seriesKey, season) }
        host.navigator.toDetail(season.id)
    }

    private fun toggleSort() {
        val ascending = !host.settings.current().seasonSortAsc
        scope.launch {
            host.settings.update { it.copy(seasonSortAsc = ascending) }
            if (!isActive) return@launch
            line?.let { renderSeasons(it) }
            applyFocus()
        }
    }

    private fun toggleFavorite() {
        val series = entry?.series ?: anchor ?: return
        if (entry != null) {
            // 取消收藏是破坏性动作：两步确认（设计系统里没有模态框）
            if (!confirm.press(seriesKey)) {
                renderFavoriteButton()
                applyFocus()
                return
            }
            scope.launch {
                host.favorites.toggle(series)
                if (!isActive) return@launch
                entry = null
                host.navigator.back()
            }
        } else {
            scope.launch {
                host.favorites.toggle(series)
                if (!isActive) return@launch
                entry = host.favorites.entry(seriesKey)
                confirm.disarm()
                render()
            }
        }
    }

    private fun toggleIntro() {
        introExpanded = !introExpanded
        hero.expandIntro(introExpanded)
        anchor?.let { renderHero(it) }
        applyFocus()
    }

    private fun goPage(delta: Int) {
        val seasons = line ?: return
        val ordered = orderedSeasons(seasons)
        val pages = seasonPages(ordered.size)
        val next = (pageIndex + delta).coerceIn(0, pages - 1)
        if (next == pageIndex) return
        pageIndex = next
        renderSeasons(seasons)
        applyFocus(ordered.getOrNull(pageIndex * SEASON_COLUMNS)?.let { FocusKey(seasonKey(it.id)) })
    }

    private fun toast(message: String) {
        Toast.makeText(ui.context, message, Toast.LENGTH_SHORT).show()
    }

    // ---------- 焦点 ----------

    private fun applyFocus(preferred: FocusKey? = null) {
        val series = anchor
        val wanted = preferred ?: preferredOnRender
        preferredOnRender = null
        val views = LinkedHashMap<FocusKey, View>()
        views[KEY_BACK] = header.back
        val actionKeys = mutableListOf<FocusKey>()
        if (series != null) {
            views[KEY_PLAY] = playButton
            views[KEY_PICK] = pickButton
            views[KEY_FAVORITE] = favoriteButton
            actionKeys += KEY_PLAY
            actionKeys += KEY_PICK
            actionKeys += KEY_FAVORITE
            if (introButton.visibility == View.VISIBLE) {
                views[KEY_INTRO] = introButton
                actionKeys += KEY_INTRO
            }
        }
        if (retryButton.visibility == View.VISIBLE) views[KEY_RETRY] = retryButton
        if (detailOnlyButton.visibility == View.VISIBLE) views[KEY_DETAIL] = detailOnlyButton
        if (seasonHeader.visibility == View.VISIBLE) views[KEY_SORT] = sortLink
        seasonCards.forEach { (id, card) -> views[FocusKey(seasonKey(id))] = card }

        val pagerKeys = mutableListOf<FocusKey>()
        if (pager.view.visibility == View.VISIBLE) {
            if (pager.prev.isEnabled) {
                views[KEY_PREV] = pager.prev
                pagerKeys += KEY_PREV
            }
            if (pager.next.isEnabled) {
                views[KEY_NEXT] = pager.next
                pagerKeys += KEY_NEXT
            }
        }

        submitPlan(
            sections = listOf(
                FocusSections.section(listOf(KEY_BACK), 1),
                if (retryButton.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_RETRY), 1) else null,
                if (detailOnlyButton.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_DETAIL), 1) else null,
                FocusSections.section(actionKeys, actionKeys.size),
                if (seasonHeader.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_SORT), 1) else null,
                FocusSections.section(seasonCards.keys.map { FocusKey(seasonKey(it)) }, SEASON_COLUMNS),
                FocusSections.section(pagerKeys, pagerKeys.size),
            ),
            views = views,
            preferred = wanted,
        )
    }

    private companion object {
        /** 一条季线一页 8 张：8 列是产品口径（1080p 上每张约 96dp），不是按宽度反推出来的。 */
        const val SEASON_COLUMNS = 8

        fun seasonPages(count: Int): Int = maxOf(1, (count + SEASON_COLUMNS - 1) / SEASON_COLUMNS)

        fun seasonKey(seasonId: String): String = "season:$seasonId"

        val KEY_BACK = FocusKey("series:back")
        val KEY_RETRY = FocusKey("series:retry")
        val KEY_DETAIL = FocusKey("series:detail")
        val KEY_PLAY = FocusKey("series:play")
        val KEY_PICK = FocusKey("series:pick")
        val KEY_FAVORITE = FocusKey("series:favorite")
        val KEY_INTRO = FocusKey("series:intro")
        val KEY_SORT = FocusKey("series:sort")
        val KEY_PREV = FocusKey("series:prev")
        val KEY_NEXT = FocusKey("series:next")
    }
}
