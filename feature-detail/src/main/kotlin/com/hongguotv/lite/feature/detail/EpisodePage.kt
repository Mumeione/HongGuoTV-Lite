package com.hongguotv.lite.feature.detail

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.Series
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.usecase.EpisodePages
import com.hongguotv.core.usecase.ResumePoint
import com.hongguotv.core.usecase.ResumeTarget
import com.hongguotv.core.usecase.SeasonChain
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.page.PageHeader
import com.hongguotv.lite.uikit.page.PagerRow
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
    private lateinit var pager: PagerRow
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
        hero.actions.addView(playButton, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT, bottomGap = tokens.metrics.heroActionGap))
        pickButton = ui.button("选集") { pickEpisode() }
        hero.actions.addView(pickButton, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT, bottomGap = tokens.metrics.heroActionGap))
        favoriteButton = ui.button("收藏") { toggleFavorite() }
        hero.actions.addView(favoriteButton, ui.params(width = ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(hero.view)

        pager = PagerRow(ui).apply { onPage = { delta -> goPage(delta) } }
        // 翻组行离 hero 14dp、分集格离翻组 10dp（mockup 画面3 的两处 margin-top）
        body.addView(
            pager.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(tokens.space.m)
            },
        )

        episodeHost = ui.column()
        body.addView(
            episodeHost,
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
        val cached = host.favorites.seasonLine(SeasonKeys.seasonKey(series.title))
        if (cached == null) scope.launch { collectSeasonLine(series) }
    }

    /** 失败静默：缺季线只是少一行说明，不该让整页变成错误态。 */
    private suspend fun collectSeasonLine(series: Series) {
        val key = SeasonKeys.seasonKey(series.title)
        val line = try {
            host.content.seasons(series.id, series)
        } catch (error: UpstreamError) {
            null
        }
        if (line == null) return
        host.favorites.rememberSeasonLine(key, line)
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
            pager.hide()
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
        val favorite = host.favorites.isFavorite(SeasonKeys.seasonKey(series.title))
        val line = host.favorites.seasonLine(SeasonKeys.seasonKey(series.title))

        hero.show(
            coverUrl = series.cover,
            title = series.title,
            // 副行"共 N 季"只在拿到季线时有据可写；没收藏的剧不为此多发一次请求
            seasonNote = line?.let { DetailText.seasonNote(it.seasons.size, it.seasons.count { s -> s.released }, withReleased = false) }.orEmpty(),
            specs = DetailText.episodeSpecs(
                update = DetailText.updateLabel(data.episodes.size),
                total = data.episodes.size,
                watchedCount = host.progress.watchedUpTo(series.id),
                tags = series.tags.replace(" · ", " / "),
                record = DetailText.episodeRecord(progress, watched),
            ),
            intro = series.intro.ifBlank { "选择剧集开始观看" },
        )
        playButton.label(DetailText.playLabel(progress, target, watched))
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

        if (pages > 1) {
            pager.show(DetailText.rangeLabel(range), hasPrev = pageIndex > 0, hasNext = pageIndex < pages - 1)
        } else {
            pager.hide()
        }
    }

    private fun renderNextSeason(data: Detail) {
        val line = host.favorites.seasonLine(SeasonKeys.seasonKey(data.series.title))
        val next = line?.let { SeasonChain.next(it, data.series.id) }
        nextSeasonView.text = DetailText.nextSeasonHint(next)
        nextSeasonView.visibility = if (next == null) View.GONE else View.VISIBLE
    }

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
        val key = SeasonKeys.seasonKey(data.series.title)
        if (key.isBlank()) {
            toast("这部剧的标题无法生成系列键，暂时不能收藏")
            return
        }
        scope.launch {
            val added = host.favorites.toggle(data.series)
            if (!isActive) return@launch
            if (added) {
                host.favorites.acknowledge(data.series.id, data.episodes.size, System.currentTimeMillis())
                // 收藏之后后台补季线：系列页与 S6 的跨季连播都要用它
                collectSeasonLine(data.series)
            }
            renderHero(data)
            applyFocus()
        }
    }

    private fun goPage(delta: Int) {
        val data = detail ?: return
        val pages = pagination.pages(data.episodes.size)
        val next = (pageIndex + delta).coerceIn(0, pages - 1)
        if (next == pageIndex) return
        pageIndex = next
        renderEpisodes(data)
        val first = pagination.page(data.episodes.size, pageIndex).first
        applyFocus(data.episodes.getOrNull(first)?.let { FocusKey(episodeKey(it.vid)) })
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
        val episodeKeys = episodeCells.keys.map { FocusKey(it) }
        episodeCells.forEach { (key, cell) -> views[FocusKey(key)] = cell }

        submitPlan(
            sections = listOf(
                FocusSections.section(listOf(KEY_BACK), 1),
                if (retryButton.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_RETRY), 1) else null,
                // 动作列是竖排的：上下在键间移动（mockup 画面3 的右列）
                FocusSections.section(actionKeys, 1),
                FocusSections.section(pagerKeys, pagerKeys.size),
                FocusSections.section(episodeKeys, pagination.columns),
            ),
            views = views,
            preferred = wanted,
            // 没有显式目标也没有记忆时落主操作：进这页是要看剧，不是要看「返回」
            ladder = if (loaded) listOf(KEY_PLAY) else emptyList(),
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

        fun episodeKey(vid: String): String = "ep:$vid"
    }
}
