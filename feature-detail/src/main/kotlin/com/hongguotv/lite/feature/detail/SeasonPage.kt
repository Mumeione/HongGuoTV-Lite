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
import com.hongguotv.lite.uikit.page.SideStrips
import com.hongguotv.lite.uikit.page.TwoStepConfirm
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.PosterCard
import com.hongguotv.lite.uikit.ui.TvButton
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
    private lateinit var seasonHeader: LinearLayout
    private lateinit var seasonCountView: TextView
    private lateinit var sortButton: TvButton
    private lateinit var seasonHost: LinearLayout
    private lateinit var seasonRow: LinearLayout
    private lateinit var strips: SideStrips

    private var entry: FavoriteEntry? = null
    private var line: SeasonLine? = null
    private var anchor: Series? = null
    private var lineFailure: Throwable? = null
    private var fetching = false
    private var singleSeason: Series? = null
    private var pageIndex = 0

    /** 组别是否已按"上次那一季"归位。只在第一次渲染季线时做一次，之后翻组就是用户的选择。 */
    private var pageRestored = false

    /** 首屏拿到数据后光标落在主操作上：进这一页是要接着看，不是要看「返回」。 */
    private var preferredOnRender: FocusKey? = null
    private val seasonCards = LinkedHashMap<String, PosterCard>()
    private val confirm = TwoStepConfirm()

    override fun onBuild() {
        // 页头写页面名，剧名交给下面的主视觉：同一行标题重复两遍既占高度又显得乱
        header = scaffold.showHeader("收藏详情") { host.navigator.back() }
        // 这一页没有信息栏，"所有季"整行要压到下半部分（mockup 画面5 的 margin-top:auto）：
        // 用会撑满高度的 body + 一根弹性留白，而不是滚动区（滚动区按内容高测量，权重不生效）
        val body = scaffold.body()

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
        hero.actions.addView(playButton, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT, bottomGap = tokens.metrics.heroActionGap))
        pickButton = ui.button("选集") { openCurrentSeason() }
        hero.actions.addView(pickButton, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT, bottomGap = tokens.metrics.heroActionGap))
        favoriteButton = ui.button("取消收藏") { toggleFavorite() }
        // 焦点一离开就撤销确认：挪到「继续播放」再回来，一次按键不该把收藏取消掉
        favoriteButton.onFocused = { focused ->
            if (!focused && confirm.isArmed(seriesKey)) {
                confirm.disarm()
                renderFavoriteButton()
            }
        }
        hero.actions.addView(favoriteButton, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(hero.view)

        // 弹性留白：把「所有季」与季卡压到页面下半部分（mockup 画面5）
        body.addView(View(ui.context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        seasonHeader = ui.row().apply {
            visibility = View.GONE
            setPadding(0, ui.dp(tokens.space.m), 0, ui.dp(tokens.space.s))
        }
        // 一行贴左排开：季数 → 倒序。翻组改由季卡两侧的长条承担（2026-10-24 口径），
        // 顶行只留"这一页是哪条线"的信息与排序开关。
        seasonCountView = ui.text("", tokens.type.compact, tokens.colors.text).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        seasonHeader.addView(seasonCountView, ui.params().apply { rightMargin = ui.dp(tokens.space.m) })
        sortButton = ui.button("倒序", variant = ButtonVariant.SMALL) { toggleSort() }
        seasonHeader.addView(sortButton, ui.params().apply { leftMargin = ui.dp(tokens.space.m) })
        body.addView(seasonHeader)

        // 季卡夹在左右长条之间：出格就撞到条上，按一下翻一组，比顶行翻组键直观
        strips = SideStrips(ui, KEY_PREV, KEY_NEXT) { delta -> goPage(delta) }
        seasonRow = ui.rowTop()
        seasonHost = strips.wrap(seasonRow).apply { visibility = View.GONE }
        body.addView(
            seasonHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // 季卡离页底留一档（mockup `.b-seasons{margin-bottom:18px}`）
                bottomMargin = ui.dp(tokens.space.xl)
            },
        )

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
        pageRestored = false
        // 上一轮停在哪一季，这一轮还落回那一张（含它所在的组）；没有记忆才落主操作
        preferredOnRender = host.lastFocus[pageKey] ?: if (anchor != null) KEY_PLAY else null
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

        if (series != null) renderHero(series)

        val seasons = line
        if (seasons == null) {
            seasonHost.visibility = View.GONE
            seasonRow.removeAllViews()
            seasonCards.clear()
            strips.show(visible = false, hasPrev = false, hasNext = false)
        } else {
            renderSeasons(seasons)
        }
        renderState(series)
        applyFocus()
    }

    /** 收藏行存的就是系列基名；只有"季线缓存里有、收藏里没有"时才需要现算一次。 */
    private fun titleText(series: Series): String = entry?.series?.title ?: SeasonKeys.seasonBase(series.title)

    /**
     * 按"上次光标停在那一季"把组别翻回去：几十季的剧一页 8 张，返回时还在第一组
     * 等于把用户刚翻的那几页丢了（story 10）。记忆里不是季卡就当没记过。
     */
    private fun restorePageIndex(ordered: List<Series>) {
        val remembered = host.lastFocus[pageKey]?.value ?: return
        if (!remembered.startsWith(SEASON_KEY_PREFIX)) return
        val id = remembered.removePrefix(SEASON_KEY_PREFIX)
        val position = ordered.indexOfFirst { it.id == id }
        if (position >= 0) pageIndex = position / SEASON_COLUMNS
    }

    /**
     * 季线展示序：落库是旧→新（端口契约），展示层才反转。
     * 默认"新→旧"（最新一季在最左），用户想从头补看时切成正序——和搜索页同一个开关。
     *
     * 未上线季永远排在最后：它们点不进去（`openSeason` 会拦住），占着第一张卡只会挡路。
     * `sortedBy` 是稳定排序，同一组内仍保持各自的新→旧 / 旧→新顺序。
     */
    private fun orderedSeasons(seasons: SeasonLine): List<Series> {
        val ordered = if (host.settings.current().seasonSortAsc) seasons.seasons else seasons.seasons.asReversed()
        return ordered.sortedBy { if (it.released) 0 else 1 }
    }

    private fun renderHero(series: Series) {
        val progress = latestProgress()
        val target = SeasonTargets.of(line, progress, anchor)
        val entryRow = entry
        val seasons = line?.seasons.orEmpty()

        hero.show(
            coverUrl = series.cover,
            title = titleText(series),
            seasonNote = DetailText.seasonNote(
                seasonCount = seasons.size,
                releasedCount = seasons.count { it.released },
                withReleased = true,
            ),
            specs = DetailText.seasonSpecs(
                update = entryRow?.let { TvText.updateProgress(it.latestSeason, it.latestEpisodes) }.orEmpty(),
                seasonCount = seasons.size,
                tags = series.tags.replace(" · ", " / "),
                record = DetailText.seasonRecord(progress, line, entryRow?.series?.season ?: 0),
            ),
            // 没有简介就把"各季进度分别保存"放进简介位：这句说明正好填上空白，
            // 比另起一页脚（会被屏幕边界切掉半行）更省高度
            intro = series.intro.ifBlank { DetailText.SEASON_FOOTER },
        )
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
        if (!pageRestored) {
            pageRestored = true
            restorePageIndex(ordered)
        }
        val pages = seasonPages(ordered.size)
        pageIndex = pageIndex.coerceIn(0, pages - 1)
        val slice = ordered.drop(pageIndex * SEASON_COLUMNS).take(SEASON_COLUMNS)

        val gap = ui.dp(tokens.metrics.seasonCardGap)
        // 卡宽 88 是 B 方案的固定口径（mockup 画面5：8 张 88dp + 10dp 间距 = 774，正好放进内容区）
        val cardWidth = ui.dp(tokens.metrics.seasonCardWidth)

        // 重建前记住"哪一季正被聚焦"：切正序/倒序之后光标要跟着那一季，别落到页面顶上
        val focusedId = seasonCards.entries.firstOrNull { it.value.isFocused }?.key
        seasonRow.removeAllViews()
        seasonCards.clear()
        // 季卡标题去掉系列名前缀（108dp 宽的卡写全名只会截断）
        val base = entry?.series?.title ?: anchor?.let { SeasonKeys.seasonBase(it.title) }.orEmpty()
        val currentSeasonId = entry?.series?.id
        slice.forEachIndexed { position, season ->
            val current = season.id == currentSeasonId
            val card = ui.poster(cardWidth).apply {
                // 这一页是详情类页面：封面**不落盘**，只按需取（落盘资格只给收藏/历史，见 S9 规格）
                bind(
                    DetailCards.season(
                        season = season,
                        base = base,
                        current = current,
                        progress = host.progress.progressOf(season.id),
                        watched = host.progress.isWatched(season.id),
                    ),
                )
                // 当前季只靠封面左上角的「当前」角标表达：mockup 不给季卡加描边，
                // 白框全留给光标（一页只有一处白色填充/描边在争注意力）
                onActivated = { openSeason(season) }
            }
            seasonRow.addView(
                card,
                LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (position < slice.lastIndex) rightMargin = gap
                },
            )
            seasonCards[season.id] = card
        }
        if (focusedId != null) seasonCards[focusedId]?.requestFocus()

        // 组范围并进季数行：长条只留 ‹ › 两个符号，"当前是哪一段"由文字说
        seasonCountView.text = if (pages > 1) {
            DetailText.seasonLineTitle(seasons.seasons.size) + " · " + DetailText.seasonRangeLabel(
                firstSeason = slice.firstOrNull()?.season ?: 0,
                lastSeason = slice.lastOrNull()?.season ?: 0,
            )
        } else {
            DetailText.seasonLineTitle(seasons.seasons.size)
        }
        sortButton.label(if (host.settings.current().seasonSortAsc) "正序" else "倒序")

        strips.show(
            visible = pages > 1,
            hasPrev = pageIndex > 0,
            hasNext = pageIndex < pages - 1,
        )
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
        }
        if (retryButton.visibility == View.VISIBLE) views[KEY_RETRY] = retryButton
        if (detailOnlyButton.visibility == View.VISIBLE) views[KEY_DETAIL] = detailOnlyButton
        seasonCards.forEach { (id, card) -> views[FocusKey(seasonKey(id))] = card }

        // 长条可用时才进焦点图；不可用那侧整段省略，内容行缘夹住自己（SideStrips 统一判）
        val leftFlank = if (seasonHost.visibility == View.VISIBLE) strips.leftSection() else null
        val rightFlank = if (seasonHost.visibility == View.VISIBLE) strips.rightSection() else null

        submitPlan(
            sections = listOf(
                FocusSections.section(listOf(KEY_BACK), 1),
                if (retryButton.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_RETRY), 1) else null,
                if (detailOnlyButton.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_DETAIL), 1) else null,
                // 动作列是竖排的：上下在键间移动（mockup 画面5 的右列）
                FocusSections.section(actionKeys, 1),
                // 排序在季数旁边（页面上方），与长条互不相干
                if (seasonHeader.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_SORT), 1) else null,
                // 季卡被左右长条包夹：行首按左、行尾按右都落到条上（2026-10-24 口径）
                FocusSections.section(
                    seasonCards.keys.map { FocusKey(seasonKey(it)) },
                    SEASON_COLUMNS,
                    flanked = true,
                ),
            ),
            views = views,
            preferred = wanted,
            // 没有显式目标也没有记忆时落主操作：进这页是要接着看，不是要看「返回」
            ladder = if (series != null) listOf(KEY_PLAY) else emptyList(),
            leftFlank = leftFlank,
            rightFlank = rightFlank,
        )
    }

    private companion object {
        /** 一条季线一页 8 张：8 列是产品口径（1080p 上每张约 96dp），不是按宽度反推出来的。 */
        const val SEASON_COLUMNS = 8

        fun seasonPages(count: Int): Int = maxOf(1, (count + SEASON_COLUMNS - 1) / SEASON_COLUMNS)

        /** 季卡焦点 key 的前缀：`restorePageIndex` 靠它认出"上次停在某一季"。 */
        const val SEASON_KEY_PREFIX = "season:"

        fun seasonKey(seasonId: String): String = "$SEASON_KEY_PREFIX$seasonId"

        val KEY_BACK = FocusKey("series:back")
        val KEY_RETRY = FocusKey("series:retry")
        val KEY_DETAIL = FocusKey("series:detail")
        val KEY_PLAY = FocusKey("series:play")
        val KEY_PICK = FocusKey("series:pick")
        val KEY_FAVORITE = FocusKey("series:favorite")
        val KEY_SORT = FocusKey("series:sort")
        val KEY_PREV = FocusKey("series:prev")
        val KEY_NEXT = FocusKey("series:next")
    }
}
