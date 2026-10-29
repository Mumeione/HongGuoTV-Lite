package com.hongguotv.lite.feature.detail

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.usecase.loadCatching
import com.hongguotv.core.usecase.SeasonProgress
import com.hongguotv.lite.uikit.focus.FocusExits
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
    private lateinit var checkButton: TvButton
    private lateinit var checkNote: TextView
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

    /** 检查进行中：按钮禁用、重入直接返回（不重复发季线请求）。 */
    private var checking = false

    /** 分类兜底最多问两次详情：收藏行是卡片元数据，有些卡片本来就不带分类。 */
    private var tagsAttempts = 0

    /** 组别是否已按"上次那一季"归位。只在第一次渲染季线时做一次，之后翻组就是用户的选择。 */
    private var pageRestored = false

    /** 首屏拿到数据后光标落在主操作上：进这一页是要接着看，不是要看「返回」。 */
    private var preferredOnRender: FocusKey? = null
    private val seasonCards = LinkedHashMap<String, PosterCard>()
    private val confirm = TwoStepConfirm()

    override fun onBuild() {
        // 页头写页面名，剧名交给下面的主视觉：同一行标题重复两遍既占高度又显得乱。
        // 不给返回键：遥控器的返回键本来就能回上一页；
        // 没有返回键时 PageHeader 不给标题加左间距，标题与检查键因此与封面左边平齐。
        header = scaffold.showHeader("收藏详情")
        // 多季剧的「检查更新」在这一页：检查结果（有没有新季）就落在下面的季卡行上。
        // 单季剧没有这一页，那颗键留在剧集详情页。
        checkButton = ui.button("检查更新", variant = ButtonVariant.SMALL) { runCheck() }.apply {
            visibility = View.GONE
        }
        header.lead().addView(
            checkButton,
            LinearLayout.LayoutParams(ui.dp(tokens.metrics.leadActionWidth), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = ui.dp(tokens.space.m)
            },
        )
        checkNote = header.leadNote("").apply {
            textSize = tokens.type.footnote
            visibility = View.GONE
        }
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
        hero.addAction(playButton)
        pickButton = ui.button("选集") { openCurrentSeason() }
        hero.addAction(pickButton)
        favoriteButton = ui.button("取消收藏") { toggleFavorite() }
        // 焦点一离开就撤销确认：挪到「继续播放」再回来，一次按键不该把收藏取消掉
        favoriteButton.onFocused = { focused ->
            if (!focused && confirm.isArmed(seriesKey)) {
                confirm.disarm()
                renderFavoriteButton()
            }
        }
        hero.addAction(favoriteButton, last = true)
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
        // 缓存只当首屏：旧版本解析丢过季（实测万妖图录传缺第二季、正传被记成无季号），
        // 进这一页就再问一次。刷新失败保留旧数据，不把整页变成错误态。
        if (anchor != null) fetchLine()
    }

    private fun fetchLine() {
        if (fetching) return
        val series = anchor ?: return
        fetching = true
        // 已有缓存时不进加载态：页面上已经有一条可用的季线，不该为了刷新闪一下空
        if (line == null) render()
        scope.launch {
            // 旧收藏行没有 albumId（这个字段是 2026-10-24 才落盘的）：seasons() 会先问一次详情，
            // 把「分宗篇=第 1 季」按详情季号并入；这里不再重复取详情。
            val fetched = loadCatching({ error -> if (line == null) lineFailure = error }) {
                host.content.seasons(series.id, series)
            }
            if (!isActive) return@launch
            fetching = false
            when {
                fetched == null -> Unit
                // 单季没有"线"可言：端口不落库，页面降级成"去看剧集详情"
                fetched.seasons.size >= 2 -> {
                    host.favorites.rememberSeasonLine(seriesKey, fetched)
                    line = fetched
                    // 锚点本尊带着详情元数据（封面/分类/集数）：写回收藏行，
                    // 旧收藏的 albumId 与元数据一起补全，下次直接读本地。
                    fetched.seasons.firstOrNull { it.id == series.id }?.let {
                        host.favorites.setCurrentSeason(seriesKey, it)
                    }
                    // 重读收藏行：「更新」与「记录」都从季线派生，缓存刚换过，snapshot 已过期
                    entry = host.favorites.entry(seriesKey)
                    anchor = entry?.series ?: anchor
                }
                line == null -> singleSeason = fetched.seasons.firstOrNull() ?: series
            }
            render()
        }
    }

    // ---------- 检查更新 ----------

    /**
     * 多季剧的「检查更新」：重拉一次季线，把"有没有新一季"说清楚，并写回检查时间。
     *
     * 与 [fetchLine] 的分工：进页那次是**静默刷新**（失败保留旧数据、不多说一句话）；
     * 用户按下这一键要的是**一个答案**，所以结果写进页头说明行，失败也如实说。
     * 写回 checkedAt 的收益与单剧检查相同：这一部在收藏页的自动轮转里退出 12 小时，
     * 不占别人的名额。
     *
     * 首屏的 [fetchLine] 可能还在跑（有缓存季线时按钮已经出现）：不取消它——两个请求都只读、
     * 写入的是同一份季线，多一条 GET 换来"按下去一定有答案"，比让用户等一个时长未知的静默刷新好。
     */
    private fun runCheck() {
        val series = anchor ?: return
        if (entry == null || checking) return
        checking = true
        checkButton.label("正在检查…")
        checkButton.isEnabled = false
        checkButton.alpha = tokens.metrics.disabledAlpha
        checkNote.visibility = View.GONE
        scope.launch {
            // 用户按的是"检查"，不能拿缓存顶：一次季线请求换来这次结果
            val fetched = loadCatching { host.content.seasons(series.id, series) }
            if (!isActive) return@launch
            checking = false
            resetCheckButton()
            if (fetched == null) {
                showCheckNote(DetailText.checkStatus(DetailText.CheckFinding.Failed))
                return@launch
            }
            if (fetched.seasons.size >= 2) {
                host.favorites.rememberSeasonLine(seriesKey, fetched)
                line = fetched
                // 锚点本尊带着详情元数据（封面/分类/集数）：写回收藏行，旧收藏的 albumId 一起补全
                fetched.seasons.firstOrNull { it.id == series.id }?.let {
                    host.favorites.setCurrentSeason(seriesKey, it)
                }
                entry = host.favorites.entry(seriesKey)
                anchor = entry?.series ?: anchor
            }
            val current = entry?.series ?: series
            // 当前季集数就从这份季线里取；本尊不在线里时传 0 —— acknowledge 的语义是
            // "只刷新检查时间"，不拿别的季的数字顶，否则会把一次"变小"当成确认。
            val episodes = fetched.seasons.firstOrNull { it.id == current.id }?.episodeCount ?: 0
            host.favorites.acknowledge(current.id, episodes, System.currentTimeMillis())
            // 季线可能整条变了：重画 hero 与季卡。光标在检查键上，刷新不抢焦（shouldRequest）。
            render()
            val seasons = line ?: fetched
            showCheckNote(
                DetailText.seasonCheckStatus(
                    finding = DetailText.checkFinding(seasons, current, episodes),
                    seasonCount = seasons.seasons.size,
                ),
            )
        }
    }

    /** 检查键的显隐。说明行跟着一起藏——否则留一行空字，页头看起来像少了东西。 */
    private fun syncCheckButton(visible: Boolean) {
        val hadFocus = checkButton.isFocused
        checkButton.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) checkNote.visibility = View.GONE
        // 键被收起时把光标交给主操作：焦点跟着 GONE 一起消失的话，遥控器整页就失灵了
        if (!visible && hadFocus) applyFocus(KEY_PLAY)
    }

    private fun resetCheckButton() {
        checkButton.label("检查更新")
        checkButton.isEnabled = true
        checkButton.alpha = 1f
    }

    private fun showCheckNote(text: String) {
        if (checkButton.visibility != View.VISIBLE) return
        checkNote.text = text
        checkNote.visibility = View.VISIBLE
    }

    // ---------- 渲染 ----------

    private fun render() {
        val series = anchor
        val ready = line != null

        hero.view.visibility = if (series == null) View.GONE else View.VISIBLE
        seasonHeader.visibility = if (ready) View.VISIBLE else View.GONE
        seasonHost.visibility = if (ready) View.VISIBLE else View.GONE
        // 检查键要等季线到手才出现：没有季线时按下只能再发一次请求，而首屏正在 fetchLine，
        // 两个请求会撞在一起。季线到手之后（含失败后重试成功）它才是一个有意义的入口。
        syncCheckButton(visible = series != null && entry != null && ready)

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

    /**
     * 收藏行存的就是系列基名；只有"季线缓存里有、收藏里没有"时才需要现算一次。
     *
     * 两级兜底：上游给的标题可能是空串（`?:` 只挡 null），空标题会让 hero 变成一块白板——
     * 先退到原样标题，再退到季号（季卡列表里认得出是哪一季）。同 `EpisodePage.renderHero`。
     */
    private fun titleText(series: Series): String = (entry?.series?.title ?: SeasonKeys.seasonBase(series.title))
        .ifBlank { series.title }
        .ifBlank { TvText.season(series.season) }

    /**
     * 按"上次光标停在那一季"把组别翻回去：几十季的剧一页 8 张，返回时还在第一组
     * 等于把用户刚翻的那几页丢了。记忆里不是季卡就当没记过。
     */
    private fun restorePageIndex(ordered: List<Series>) {
        val remembered = host.lastFocus[pageKey]?.value ?: return
        if (!remembered.startsWith(SEASON_KEY_PREFIX)) return
        val id = remembered.removePrefix(SEASON_KEY_PREFIX)
        val position = ordered.indexOfFirst { it.id == id }
        if (position >= 0) pageIndex = position / SEASON_COLUMNS
    }

    /**
     * 季线展示序：**按季号排，未上线季也在序列里**（2026-10-24 口径）。
     *
     * 以前把未上线季一律挪到最后，结果"第 10 季未上线"排在"第 3 季"后面：
     * 用户看到的是一条断掉的季线，而不是这部剧真实的季序。未上线季只是点不进去
     * （[openSeason] 会拦），位置该在哪就在哪。
     *
     * 默认"新→旧"（最新一季在最左），用户想从头补看时切成正序——和搜索页同一个开关。
     * 季号缺失（0）的条目没有可排的位置，正倒序都放最后。
     */
    private fun orderedSeasons(seasons: SeasonLine): List<Series> {
        val known = seasons.seasons.filter { it.season > 0 }.sortedBy { it.season }
        val unknown = seasons.seasons.filter { it.season <= 0 }
        val ordered = if (host.settings.current().seasonSortAsc) known + unknown else known.asReversed() + unknown
        return ordered
    }

    private fun renderHero(series: Series) {
        val progress = latestProgress()
        val target = SeasonTargets.of(line, progress, anchor)
        val entryRow = entry
        val seasons = line?.seasons.orEmpty()
        val tags = tagsOf(series)

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
                // 分类行必须在（2026-10-24 验收）：拿不到就先占位，详情回来后会补上
                tags = TvText.tags(tags).ifBlank { "—" },
                record = DetailText.seasonRecord(progress, line, entryRow?.series?.season ?: 0),
            ),
            // 有简介就显示简介；没有时用"各季进度分别保存…"补位。
            // 这句本身没有换行（2026-10-24 口径：一行显示），信息块底部因此贴着封面底。
            intro = series.intro.ifBlank { DetailText.SEASON_FOOTER },
        )
        val (title, note) = DetailText.playLabelLines(target.label)
        playButton.label(title, note)
        renderFavoriteButton()
    }

    /**
     * 分类来源（按可靠度）：收藏行本身 → 季线里同一季 → 季线里**分类最多的一季** → 现取一次详情。
     *
     * 收藏行是从卡片落库的，卡片不一定带分类；同一系列各季的分类通常同源，
     * 取字符最多的那份最不容易缺项（2026-10-24 验收：「留分类最多的那季或收藏的那季」）。
     */
    private fun tagsOf(series: Series): String {
        val fromEntry = entry?.series?.tags.orEmpty()
        if (fromEntry.isNotBlank()) return fromEntry
        val fromLine = line?.seasons?.firstOrNull { it.id == series.id }?.tags.orEmpty()
        if (fromLine.isNotBlank()) return fromLine
        val richest = line?.seasons?.maxByOrNull { it.tags.length }?.tags.orEmpty()
        if (richest.isNotBlank()) return richest
        backfillTags(series)
        return ""
    }

    private fun backfillTags(series: Series) {
        // 最多试两次：一次失败（网络抖动）不该让分类永远停在占位「—」上
        if (tagsAttempts >= MAX_TAG_ATTEMPTS) return
        tagsAttempts++
        scope.launch {
            // 分类补全失败静默（同 fetchLine：脏数据也不该崩进程）
            val detail = loadCatching { host.content.detail(series.id) }
            if (!isActive) return@launch
            if (detail == null || detail.series.tags.isBlank()) return@launch
            // 只补"当前收藏的那一季"：别把用户选的季换成别的季
            if (entry?.series?.id != detail.series.id) return@launch
            // 写回收藏行：分类、封面、简介一起补全，系列页下次直接从本地读
            host.favorites.setCurrentSeason(seriesKey, detail.series)
            entry = host.favorites.entry(seriesKey)
            tagsAttempts = 0
            render()
        }
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
        // 卡宽取令牌。满 7 张时按剩余宽度**等距展开**：首卡左侧、卡与卡之间、末卡右侧
        // 共用一组弹性空格；与长条之间另有 SideStrips 的固定留白。末页不足 7 张时保持
        // 原网格口径左对齐、卡间距取令牌值，不把三张卡拉成一片，也不让首卡横向跳位。
        val cardWidth = ui.dp(tokens.metrics.seasonCardWidth)
        // 季卡标题去掉系列名前缀（108dp 宽的卡写全名只会截断）
        val base = entry?.series?.title ?: anchor?.let { SeasonKeys.seasonBase(it.title) }.orEmpty()
        val currentSeasonId = entry?.series?.id
        val ids = slice.map { it.id }
        fun cardItem(season: Series) = DetailCards.season(
            season = season,
            base = base,
            current = season.id == currentSeasonId,
            progress = host.progress.progressOf(season.id),
        )

        // 后台季线刷新回来时 ids 与顺序通常没变：就地重绑角标，不重建行。
        // 重建会把正在聚焦的季卡摘掉，框架会做一次被动落焦（通常是侧栏）。
        if (seasonCards.keys.toList() == ids) {
            slice.forEach { season ->
                seasonCards[season.id]?.apply {
                    bind(cardItem(season))
                    onActivated = { openSeason(season) }
                }
            }
        } else {
            // 重建前记住"哪一季正被聚焦"：切正序/倒序之后光标要跟着那一季，别落到页面顶上
            val focusedId = seasonCards.entries.firstOrNull { it.value.isFocused }?.key
            seasonRow.removeAllViews()
            seasonCards.clear()
            // 只剩满 7 张的页需要展开：8 个等权空格把余量分到两端与卡间。
            // 末页保持左对齐、卡间距固定，避免首卡位置随翻页横向跳动。
            val spread = slice.size == SEASON_COLUMNS
            if (spread) addRowSpring(gap)
            slice.forEachIndexed { position, season ->
                val card = ui.poster(cardWidth).apply {
                    // 这一页是详情类页面：封面**不落盘**，只按需取（落盘资格只给收藏/历史）
                    bind(cardItem(season))
                    // 当前季只靠封面左上角的「当前」角标表达：mockup 不给季卡加描边，
                    // 白框全留给光标（一页只有一处白色填充/描边在争注意力）
                    onActivated = { openSeason(season) }
                }
                seasonRow.addView(card, LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
                seasonCards[season.id] = card
                if (spread) {
                    addRowSpring(gap)
                } else if (position < slice.lastIndex) {
                    addRowSpring(gap, weight = 0f)
                }
            }
            if (focusedId != null) seasonCards[focusedId]?.requestFocus()
        }

        // 组范围并进季数行：长条只留「上一组/下一组」两枚翻组键，"当前是哪一段"由文字说
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

    /** 季卡行的空格：最小宽度 [minWidthPx]，权重 [weight] 决定是否参与平分剩余宽度。 */
    private fun addRowSpring(minWidthPx: Int, weight: Float = 1f) {
        seasonRow.addView(
            Space(ui.context),
            LinearLayout.LayoutParams(minWidthPx, 0, weight),
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
            // 光标留在排序键上：切成正序后马上还想切回去，不该被扔回"继续播放"
            applyFocus(KEY_SORT)
        }
    }

    private fun toggleFavorite() {
        val series = entry?.series ?: anchor ?: return
        if (entry != null) {
            // 取消收藏是破坏性动作：两步确认（设计系统里没有模态框）
            if (!confirm.press(seriesKey)) {
                renderFavoriteButton()
                // 把光标**留在这一键上**：重提焦点图时若落回"继续播放"，焦点一离开就撤销确认，
                // 这个键永远等不到第二次按下（维护者实测：取消收藏失效）
                applyFocus(KEY_FAVORITE)
                return
            }
            scope.launch {
                host.favorites.toggle(series)
                if (!isActive) return@launch
                entry = null
                host.navigator.back()
            }
        } else {
            // 已满 100 部又收藏新的一条：写入口按 addedAt 淘汰最旧的一部，先说清是哪一部
            favoriteCapacityWarning(host.favorites)?.let { toast(it) }
            scope.launch {
                host.favorites.toggle(series)
                if (!isActive) return@launch
                entry = host.favorites.entry(seriesKey)
                confirm.disarm()
                // 刚点了收藏：光标留在这一键上，紧接着要取消也不用重新找
                preferredOnRender = KEY_FAVORITE
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
        // 连翻两组：光标留在刚按的长条上；翻到尽头那条被禁用时才落回第一张季卡
        val preferred = strips.preferredAfterPage(delta)
            ?: ordered.getOrNull(pageIndex * SEASON_COLUMNS)?.let { FocusKey(seasonKey(it.id)) }
        applyFocus(preferred)
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
        // 页头只剩「检查更新」一颗（多季剧才有）：返回键由遥控器的返回键承担
        val checkVisible = checkButton.visibility == View.VISIBLE
        if (checkVisible) views[KEY_CHECK] = checkButton
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
        // 排序键：声明了焦点段还不够，视觉绑定只认 views 里的项——漏了它就是"按不到、选不中"
        // （维护者实测：正序/倒序切换按钮无法点击无法选中）
        if (seasonHeader.visibility == View.VISIBLE) views[KEY_SORT] = sortButton
        seasonCards.forEach { (id, card) -> views[FocusKey(seasonKey(id))] = card }

        // 长条可用时才进焦点图；不可用那侧整段省略，内容行缘夹住自己（SideStrips 统一判）。
        // 同时把长条放进 views：只声明段、不给 View，光标永远落不到它身上（无法选中）
        if (seasonHost.visibility == View.VISIBLE) strips.bindInto(views)
        val leftFlank = if (seasonHost.visibility == View.VISIBLE) strips.leftSection() else null
        val rightFlank = if (seasonHost.visibility == View.VISIBLE) strips.rightSection() else null

        val cardKeys = seasonCards.keys.map { FocusKey(seasonKey(it)) }
        val firstCard = cardKeys.firstOrNull()
        // 季卡按上固定回「排序」，「排序」按下固定回第一张季卡：不再按矩形距离
        // 在排序键与右侧动作列之间二选一（用户按上/下的意图都是"上一行"）。
        // 目标不在屏上时退回几何，不改 FocusExits 的约定。
        //
        // 「排序」左右都没有同一行的项（两侧夹住自己），按上回页头的检查键——
        // 它上方隔着弹性留白与 hero，几何落点会跳到右侧动作列。
        // 季卡行缘在没有长条时同样夹住自己，不撞侧栏、不撞动作列。
        val sortExit = if (seasonHeader.visibility == View.VISIBLE) {
            sortExits(KEY_SORT, check = if (checkVisible) KEY_CHECK else null)
        } else {
            FocusExits.NONE
        }
        val cardExits = if (firstCard == null) {
            FocusExits.NONE
        } else {
            FocusExits.up(KEY_SORT, cardKeys) + FocusExits.down(firstCard, listOf(KEY_SORT))
        }
        // 检查键：按下回主操作，自己上/左/右夹住；主操作按上/左回它（见 detailCheckExits）
        val exits = (if (checkVisible) detailCheckExits(KEY_CHECK, KEY_PLAY) else FocusExits.NONE) +
            (if (series != null) {
                detailActionExits(KEY_PLAY, KEY_PICK, KEY_FAVORITE, if (checkVisible) KEY_CHECK else null)
            } else {
                FocusExits.NONE
            }) +
            sortExit + cardExits +
            gridEdgeExits(cardKeys, SEASON_COLUMNS, leftFlank, rightFlank)

        submitPlan(
            sections = listOf(
                // 页头：（多季剧的）检查更新
                if (checkVisible) FocusSections.section(listOf(KEY_CHECK), 1) else null,
                if (retryButton.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_RETRY), 1) else null,
                if (detailOnlyButton.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_DETAIL), 1) else null,
                // 动作列是竖排的：上下在键间移动（mockup 画面5 的右列）
                FocusSections.section(actionKeys, 1),
                // 排序在季数旁边（页面上方），与长条互不相干
                if (seasonHeader.visibility == View.VISIBLE) FocusSections.section(listOf(KEY_SORT), 1) else null,
                // 季卡被左右长条包夹：行首按左、行尾按右都落到条上（2026-10-24 口径）
                FocusSections.section(cardKeys, SEASON_COLUMNS, flanked = true),
            ),
            views = views,
            preferred = wanted,
            // 没有显式目标也没有记忆时落主操作：进这页是要接着看，不是要看页头
            ladder = if (series != null) listOf(KEY_PLAY) else emptyList(),
            leftFlank = leftFlank,
            rightFlank = rightFlank,
            // 侧栏按右直接进主操作：第一段是页头的检查键，低频动作不该接这一下
            sideEntry = if (series != null) KEY_PLAY else null,
            visualExits = exits,
        )
    }

    private companion object {
        /**
         * 一条季线一页 **7 张**（2026-10-24 验收口径）：两侧各让出一条翻组长条的位置，
         * 7 列是产品口径，不是按宽度反推出来的。
         */
        const val SEASON_COLUMNS = 7

        /** 分类兜底详情最多取两次（见 [backfillTags]）。 */
        const val MAX_TAG_ATTEMPTS = 2

        fun seasonPages(count: Int): Int = maxOf(1, (count + SEASON_COLUMNS - 1) / SEASON_COLUMNS)

        /** 季卡焦点 key 的前缀：`restorePageIndex` 靠它认出"上次停在某一季"。 */
        const val SEASON_KEY_PREFIX = "season:"

        fun seasonKey(seasonId: String): String = "$SEASON_KEY_PREFIX$seasonId"

        val KEY_CHECK = FocusKey("series:check")
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
