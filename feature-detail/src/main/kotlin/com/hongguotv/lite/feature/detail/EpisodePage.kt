package com.hongguotv.lite.feature.detail

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.usecase.EpisodePages
import com.hongguotv.core.usecase.FavoriteLookup
import com.hongguotv.core.usecase.ResumePoint
import com.hongguotv.core.usecase.ResumeTarget
import com.hongguotv.core.usecase.SeasonChain
import com.hongguotv.core.usecase.SeasonLineBackfill
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.PageHeader
import com.hongguotv.lite.uikit.page.SideStrips
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.ui.ButtonVariant
import com.hongguotv.lite.uikit.ui.TvButton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 剧集详情：一季作品的信息、简介、动作与集数清单（B 方案，mockup 画面3）。
 *
 * 数据只有一条路：`ContentPort.detail(id)`，分集顺序以服务端为准，本地不重排。
 * 集数按 20 列 × 5 行分组成胶囊格——300 集的剧也真实存在，一次画完既建不动也没法用遥控器走。
 *
 * 动作列是竖排三键：继续播放 / 选集（光标跳到分集区）/ 收藏。
 * 「完整简介」按钮与可滚简介框随 B 方案取消——简介两行封顶。
 */
internal class EpisodePage(
    host: DetailFeature,
    private val seriesId: String,
    private val resumeRecording: Boolean,
) : DetailPage(host, "detail:$seriesId") {

    private lateinit var header: PageHeader
    private lateinit var stateView: TextView
    private lateinit var retryButton: TvButton
    private lateinit var hero: HeroBlock
    private lateinit var playButton: TvButton
    private lateinit var pickButton: TvButton
    private lateinit var favoriteButton: TvButton
    private lateinit var rangeLabel: TextView
    private lateinit var strips: SideStrips
    private lateinit var episodeHost: LinearLayout
    private lateinit var nextSeasonView: TextView

    private var detail: Detail? = null
    private var target: ResumeTarget = ResumeTarget(0, 0L, false)
    private var failure: Throwable? = null
    private var loading = false
    private var pageIndex = 0

    /** 分页算术：一行 20 格 × 5 行 = 100 集一页（与播放侧栏的 10×50 各是各的口径）。 */
    private val pagination = EpisodePages.DETAIL

    /** 首屏加载完成后光标落在主操作上：用户进来是要看剧，不是要看「返回」。 */
    private var preferredOnRender: FocusKey? = null
    private val episodeCells = LinkedHashMap<String, TvButton>()

    override fun onBuild() {
        header = scaffold.showHeader("剧集详情") { host.navigator.back() }
        val body = scaffold.scrollBody()

        stateView = ui.text("", tokens.type.subtitle, tokens.colors.muted).apply {
            setPadding(0, ui.dp(tokens.space.xxl), 0, ui.dp(tokens.space.l))
        }
        body.addView(stateView)
        retryButton = ui.button("重试") { load() }.apply { visibility = View.GONE }
        body.addView(retryButton)

        hero = HeroBlock(ui)
        playButton = ui.button("开始观看", variant = ButtonVariant.PRIMARY) { play() }
        hero.addAction(playButton)
        pickButton = ui.button("选集") { pickEpisode() }
        hero.addAction(pickButton)
        favoriteButton = ui.button("收藏") { toggleFavorite() }
        hero.addAction(favoriteButton, last = true)
        body.addView(hero.view)

        // 范围行只是**说明**（"这一页是第几到第几集"），不再是按钮行：翻组交给
        // 分集格两侧的长条（2026-10-24 口径）。行本身始终占位——隐藏它会让
        // 分集格紧贴 hero（维护者截图：65 集的剧看起来"集数和简介挤在一起"）。
        // 颜色用正文白（2026-10-24 验收）：muted 灰会让它读成简介的第三行，它是分集区的标题。
        rangeLabel = ui.text("", tokens.type.caption, tokens.colors.text).apply {
            gravity = android.view.Gravity.CENTER
            maxLines = 1
        }
        body.addView(
            rangeLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(tokens.space.m)
            },
        )

        // 分集格夹在左右长条之间：出格就撞到条上，按一下翻一组
        strips = SideStrips(ui, KEY_PREV, KEY_NEXT) { delta -> goPage(delta) }
        episodeHost = ui.column()
        val episodesWrap = strips.wrap(episodeHost)
        body.addView(
            episodesWrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(tokens.space.s)
            },
        )

        nextSeasonView = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
            visibility = View.GONE
            setPadding(0, ui.dp(tokens.space.l), 0, 0)
        }
        body.addView(nextSeasonView)

        load()
    }

    // ---------- 数据 ----------

    private fun load() {
        loading = true
        failure = null
        detail = null
        render()
        scope.launch {
            val loaded = try {
                host.content.detail(seriesId)
            } catch (error: UpstreamError) {
                failure = error
                null
            }
            if (!isActive) return@launch
            loading = false
            if (loaded == null) {
                render()
                return@launch
            }
            detail = loaded
            // 用户已经看到这一季现在的集数了：清掉"新增集"角标（非收藏系列端口自己忽略）
            host.favorites.acknowledge(loaded.series.id, loaded.episodes.size, System.currentTimeMillis())
            target = ResumePoint.of(loaded.episodes, host.progress.progressOf(loaded.series.id))
            pageIndex = pagination.pageOf(target.index)
            // 从历史/「继续看」进来：要的是画面，不是先看一眼详情页再跳
            if (resumeRecording) {
                openPlayer(loaded)
                return@launch
            }
            preferredOnRender = KEY_PLAY
            render()
            loadSeasonLine(loaded.series)
        }
    }

    /**
     * 季线：剧集详情的「共 N 季」副行靠它（mockup 画面3 的第二行）。
     *
     * 以前只在已收藏时取（省一次请求），结果是未收藏的剧这一行永远空着——而它是
     * "这是不是一个系列"的第一眼信息。现在只要进详情就取一次，失败静默（少一行说明，
     * 不该让整页变成错误态），拿到后与收藏时一样落库，系列页直接用。
     */
    private fun loadSeasonLine(series: Series) {
        // 已经缓存过就不再请求（从系列页/收藏回来时走这条）
        val cached = host.favorites.seasonLine(seriesKeyOf(series))
        if (cached == null) scope.launch { collectSeasonLine(series) }
    }

    /** 失败静默：缺季线只是少一行说明，不该让整页变成错误态。 */
    private suspend fun collectSeasonLine(series: Series) {
        val key = seriesKeyOf(series)
        val line = try {
            host.content.seasons(series.id, series)
        } catch (error: UpstreamError) {
            null
        }
        if (line == null) return
        host.favorites.rememberSeasonLine(key, line)
        SeasonLineBackfill.run(line, host.progress)
        // 取消后回写视图会把即将销毁的树再绑一次
        if (!currentCoroutineContext().isActive) return
        detail?.let {
            renderHero(it)
            renderNextSeason(it)
            applyFocus()
        }
    }

    // ---------- 渲染 ----------

    private fun render() {
        val data = detail
        stateView.visibility = if (data == null) View.VISIBLE else View.GONE
        stateView.text = when {
            failure != null -> TvText.error(failure!!)
            loading -> "正在加载剧集…"
            else -> ""
        }
        retryButton.visibility = if (failure != null) View.VISIBLE else View.GONE
        hero.view.visibility = if (data == null) View.GONE else View.VISIBLE
        if (data == null) {
            strips.show(visible = false, hasPrev = false, hasNext = false)
            rangeLabel.text = ""
            nextSeasonView.visibility = View.GONE
            episodeHost.removeAllViews()
            episodeCells.clear()
        } else {
            renderHero(data)
            renderEpisodes(data)
            renderNextSeason(data)
        }
        applyFocus()
    }

    private fun renderHero(data: Detail) {
        val series = data.series
        val progress = host.progress.progressOf(series.id)
        val watched = host.progress.isWatched(series.id)
        val favorite = favoriteEntryOf(series) != null
        val line = host.favorites.seasonLine(seriesKeyOf(series))

        // 标题去掉季后缀（「…第十四季」）：26sp 一行放不下，后缀会把片名挤成省略号
        // （实测「聚宝仙盆之杂灵根才是真BOSS第十…」）。季号改放副行，与"共 N 季"并列。
        val seasonLabel = series.season.takeIf { it > 0 }?.let { TvText.season(it) }
        val seasonCount = line?.let {
            DetailText.seasonNote(it.seasons.size, it.seasons.count { s -> s.released }, withReleased = false)
        }
        hero.show(
            coverUrl = series.cover,
            title = SeasonKeys.seasonBase(series.title).ifBlank { series.title },
            // 副行：季号 + "共 N 季"（季线只在拿得到时才有后半截；没收藏的剧不为此多发一次请求）
            seasonNote = listOfNotNull(seasonLabel, seasonCount?.takeIf { it.isNotBlank() }).joinToString(" · "),
            specs = DetailText.episodeSpecs(
                update = DetailText.updateLabel(data.episodes.size),
                total = data.episodes.size,
                watchedCount = host.progress.watchedUpTo(series.id),
                tags = TvText.tags(series.tags),
                record = DetailText.episodeRecord(progress, watched),
            ),
            intro = series.intro.ifBlank { "选择剧集开始观看" },
        )
        val (title, note) = DetailText.playLabelLines(DetailText.playLabel(progress, target, watched))
        playButton.label(title, note)
        favoriteButton.label(if (favorite) "已收藏" else "收藏")
    }

    private fun renderEpisodes(data: Detail) {
        val total = data.episodes.size
        val pages = pagination.pages(total)
        pageIndex = pageIndex.coerceIn(0, pages - 1)
        val range = pagination.page(total, pageIndex)
        val watchedUpTo = host.progress.watchedUpTo(data.series.id)
        val gap = ui.dp(tokens.metrics.episodeCellGap)

        episodeHost.removeAllViews()
        episodeCells.clear()
        range.chunked(pagination.columns).forEach { indices ->
            val row = ui.rowTop()
            indices.forEachIndexed { position, index ->
                val cell = ui.button(
                    label = "${index + 1}",
                    selected = index == target.index,
                    watched = index < watchedUpTo,
                    variant = ButtonVariant.CELL,
                ) { playEpisode(index) }
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (position < indices.lastIndex) rightMargin = gap
                    },
                )
                data.episodes.getOrNull(index)?.let { episodeCells[episodeKey(it.vid)] = cell }
            }
            // 末行补空格：缺格会让最后几个集数被拉宽，20 列的视觉对齐就断了
            repeat(pagination.columns - indices.size) {
                row.addView(Space(ui.context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            episodeHost.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = gap
                },
            )
        }

        // 范围行始终占位（"这一页是第几到第几集"）；单页剧没有可翻的组，整对长条收起，
        // 分集格占满一行（与收藏页的季卡同一条规则）。
        rangeLabel.text = DetailText.rangeLabel(range)
        strips.show(visible = pages > 1, hasPrev = pageIndex > 0, hasNext = pageIndex < pages - 1)
    }

    private fun renderNextSeason(data: Detail) {
        val line = host.favorites.seasonLine(seriesKeyOf(data.series))
        val next = line?.let { SeasonChain.next(it, data.series.id) }
        nextSeasonView.text = DetailText.nextSeasonHint(next)
        nextSeasonView.visibility = if (next == null) View.GONE else View.VISIBLE
    }

    /**
     * 这一季所属的收藏系列键。
     *
     * 优先用"收藏里认到的那一条"：分宗篇这类标题算出的键与收藏行不同，
     * 直接拿标题键既查不到收藏，也读不到已缓存的季线（第 10 季详情页因此没有「共 N 季」）。
     */
    private fun seriesKeyOf(series: Series): String = FavoriteLookup.favoriteKey(series, host.favorites)

    private fun favoriteEntryOf(series: Series): FavoriteEntry? = FavoriteLookup.entry(series, host.favorites)

    // ---------- 交互 ----------

    private fun play() {
        detail?.let { openPlayer(it) }
    }

    /** 「选集」= 光标跳到当前集：进详情页按一下就能落在分集区里挑。 */
    private fun pickEpisode() {
        val data = detail ?: return
        val range = pagination.page(data.episodes.size, pageIndex)
        val index = target.index.coerceIn(range.first, range.last)
        applyFocus(data.episodes.getOrNull(index)?.let { FocusKey(episodeKey(it.vid)) })
    }

    private fun playEpisode(index: Int) {
        val data = detail ?: return
        val progress = host.progress.progressOf(data.series.id)
        val vid = data.episodes.getOrNull(index)?.vid
        // 点的是"上次那一集"就从那个位置接着播，点别的集从头开始
        val position = if (progress != null && progress.episodeVid == vid && !progress.completed) {
            ResumePoint.checkpoint(progress.positionMs, progress.durationMs, completed = false).positionMs
        } else {
            0L
        }
        host.navigator.toPlayer(data.series.id, index, position)
    }

    private fun toggleFavorite() {
        val data = detail ?: return
        val owned = FavoriteLookup.entry(data.series, host.favorites)
        val key = owned?.seriesKey ?: SeasonKeys.seasonKey(data.series.title)
        if (key.isBlank()) {
            toast("这部剧的标题无法生成系列键，暂时不能收藏")
            return
        }
        // 已收藏 → 取消那一条（可能不是按本季标题建的）；没收藏 → 用本季开一条，
        // 系列键由 toggle 内部按标题算（收藏行的 title 始终是基名，这条不变量不能破）
        val target = owned?.series ?: data.series
        scope.launch {
            val added = host.favorites.toggle(target)
            if (!isActive) return@launch
            if (added) {
                host.favorites.acknowledge(data.series.id, data.episodes.size, System.currentTimeMillis())
                // 收藏之后后台补季线：系列页与 S6 的跨季连播都要用它
                collectSeasonLine(data.series)
            }
            renderHero(data)
            // 收藏键点完还留在原位：紧接着可能就要取消，光标跳走会多按好几次
            applyFocus(KEY_FAVORITE)
        }
    }

    private fun goPage(delta: Int) {
        val data = detail ?: return
        val pages = pagination.pages(data.episodes.size)
        val next = (pageIndex + delta).coerceIn(0, pages - 1)
        if (next == pageIndex) return
        pageIndex = next
        renderEpisodes(data)
        // 连翻两组时光标留在长条上；翻到尽头（那条被禁用）才落回第一格
        val first = pagination.page(data.episodes.size, pageIndex).first
        applyFocus(
            strips.preferredAfterPage(delta)
                ?: data.episodes.getOrNull(first)?.let { FocusKey(episodeKey(it.vid)) },
        )
    }

    private fun openPlayer(data: Detail) {
        val watched = host.progress.isWatched(data.series.id)
        host.navigator.toPlayer(
            seriesId = data.series.id,
            episodeIndex = if (watched) 0 else target.index,
            positionMs = if (watched) 0L else target.positionMs,
        )
    }

    private fun toast(message: String) {
        Toast.makeText(ui.context, message, Toast.LENGTH_SHORT).show()
    }

    // ---------- 焦点 ----------

    private fun applyFocus(preferred: FocusKey? = null) {
        val loaded = detail != null
        val wanted = preferred ?: preferredOnRender
        preferredOnRender = null
        val views = LinkedHashMap<FocusKey, View>()
        views[KEY_BACK] = header.back
        if (retryButton.visibility == View.VISIBLE) views[KEY_RETRY] = retryButton
        val actionKeys = mutableListOf<FocusKey>()
        if (loaded) {
            views[KEY_PLAY] = playButton
            views[KEY_PICK] = pickButton
            views[KEY_FAVORITE] = favoriteButton
            actionKeys += KEY_PLAY
            actionKeys += KEY_PICK
            actionKeys += KEY_FAVORITE
        }
        // 分集格被左右长条包夹：行首按左、行尾按右都落到条上（2026-10-24 口径）。
        // 长条不可用的那侧整段省略，内容行缘夹住自己（SideStrips 统一判）。
        val episodeKeys = episodeCells.keys.map { FocusKey(it) }
        episodeCells.forEach { (key, cell) -> views[FocusKey(key)] = cell }
        // 两侧长条必须在 views 里，否则焦点图声明了侧翼也没有落点（按不进去）
        strips.bindInto(views)

        submitPlan(
            sections = listOf(
                FocusSections.section(listOf(KEY_BACK), 1),
                if (retryButton.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_RETRY), 1) else null,
                // 动作列是竖排的：上下在键间移动（mockup 画面3 的右列）
                FocusSections.section(actionKeys, 1),
                FocusSections.section(episodeKeys, pagination.columns, flanked = true),
            ),
            views = views,
            preferred = wanted,
            // 没有显式目标也没有记忆时落主操作：进这页是要看剧，不是要看「返回」
            ladder = if (loaded) listOf(KEY_PLAY) else emptyList(),
            leftFlank = strips.leftSection(),
            rightFlank = strips.rightSection(),
            visualExits = ACTION_EXITS,
        )
    }

    private companion object {
        val KEY_BACK = FocusKey("detail:back")
        val KEY_RETRY = FocusKey("detail:retry")
        val KEY_PLAY = FocusKey("detail:play")
        val KEY_PICK = FocusKey("detail:pick")
        val KEY_FAVORITE = FocusKey("detail:favorite")
        val KEY_PREV = FocusKey("detail:prev")
        val KEY_NEXT = FocusKey("detail:next")

        /** 动作列左边是封面与简介（不可聚焦）：按左回页头返回，别掉进侧栏。 */
        val ACTION_EXITS = detailActionExits(KEY_BACK, KEY_PLAY, KEY_PICK, KEY_FAVORITE)

        fun episodeKey(vid: String): String = "ep:$vid"
    }
}
