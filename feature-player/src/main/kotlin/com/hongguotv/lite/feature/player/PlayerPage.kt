package com.hongguotv.lite.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.PlaybackOptions
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.Settings
import com.hongguotv.core.usecase.FavoriteLookup
import com.hongguotv.core.usecase.loadCatching
import com.hongguotv.core.usecase.SeasonLineBackfill
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSession
import com.hongguotv.lite.uikit.focus.KeyRepeatGate
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.token.UiTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放页：一块画面 + 一层侧栏 + 一张提示卡。
 *
 * 页面的职责只有三件，且都很薄：
 * 1. **把按键翻成命令**（[playerKeyOf] → [PlayerKeys] → 执行），自己不判断业务；
 * 2. **实现 [PlayerHost]**：取流、写进度、写设置、跨季跳转各自挂在正确的作用域上；
 * 3. **跟着状态画**（[PlaybackSession.onStateChanged]）：侧栏状态行、提示卡、焦点。
 *
 * 三条 S5 学到的纪律在这里落地：读请求挂页面作用域（离开即取消）、写操作挂宿主作用域
 * （页面马上销毁也不会"还没写就被取消"）、加载失败一律兜成失败态而不崩进程
 * （V1.0.0 起加载走 `core.usecase.loadCatching`：脏本地数据会从仓库 `require` 抛
 * `IllegalArgumentException`，装配错误同样该进失败态而非崩进程）。
 */
internal class PlayerPage(
    private val host: PlayerFeature,
    private val seriesId: String,
    private val startIndex: Int,
    private val startPositionMs: Long,
) : PlayerHost {

    private val ui = host.ui
    private val tokens: Tokens = ui.tokens
    private val root = FrameLayout(ui.context)
    private val jobs = Job()
    private val scope = CoroutineScope(host.scope.coroutineContext + jobs)
    // 播放页（抽屉）与详情页一样按视觉位置走：分集格、两侧长条与右列设置是一条横带
    private val focus = FocusSession(PAGE_KEY, root = root)

    /**
     * 全局焦点监听的唯一实例，[release] 时摘掉。
     *
     * `ViewTreeObserver` 是窗口级共享的：播放页被路由摘下之后监听器仍留在窗口的 observer 上，
     * 它捕获了本页与整棵侧栏视图树——每看一集就漏一页。
     */
    private val focusListener =
        android.view.ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            focus.onFocusChanged(oldFocus, newFocus)
        }
    private val engine = ExoPlayerEngine(ui.context, tokens)
    private val handler = Handler(Looper.getMainLooper())
    private val repeatGate = KeyRepeatGate(SEEK_REPEAT_GATE_MS)

    private lateinit var session: PlaybackSession
    private lateinit var sidebar: PlayerSidebar
    private lateinit var notice: PlayerNotice

    private var detail: Detail? = null
    private var loadFailure: Throwable? = null
    private var streamJob: Job? = null
    private var toast: Toast? = null
    private var lastFocusToken: String? = null

    /** 片尾预取：决定、去重、预备流归属都在这一层，页面只喂当前播放状态。 */
    private val prefetcher = EpisodePrefetcher(
        scope = scope,
        media = host.media,
        clock = { SystemClock.elapsedRealtime() },
    )

    /**
     * 会话还没建起来就被要求暂停（宿主在后台把播放页推上来时会发生）：
     * 记下来，等 `start()` 之后补一次生命周期暂停。否则流一回来就出声——
     * 页面刚建、`session` 还是 `Idle`，`onLifecyclePause` 那时什么都做不了。
     */
    private var pausedBeforeStart = false

    fun view(): View = root

    fun build() {
        root.setBackgroundColor(Color.BLACK)
        root.addView(
            engine.view(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        session = PlaybackSession(engine, host.settings, host.favorites, this, HandlerScheduler(handler)) {
            System.currentTimeMillis()
        }
        session.onStateChanged = { state ->
            prefetcher.onSessionState(state)
            render()
        }
        notice = PlayerNotice(ui) { action -> runNoticeAction(action) }
        notice.attachTo(root)
        buildOperationHint()
        buildSidebar()
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        handler.postDelayed(tick, TICK_MS)
        loadDetail()
    }

    // ---------- 视图 ----------

    /**
     * 按键操作提示（2026-10-24 口径）：**与抽屉同进退**——按 MENU 打开抽屉才出现，
     * 贴在抽屉上沿；不再是常驻画面顶部的浮字。平时画面保持纯净，键位说明在要用的时候才读。
     */
    private lateinit var operationHint: TextView

    /** 抽屉打开时的整屏压暗层（`Colors.scrim`）：画面让位给选集与设置，但仍看得见。 */
    private val scrim = View(ui.context)

    private fun buildOperationHint() {
        // （键, 说明）成对 → 键白粗、说明弱一档；段落之间用全角空格分开（mockup .op-hint）
        val hints = PlayerText.operationHints()
        val spanned = android.text.SpannableStringBuilder()
        hints.forEachIndexed { index, (key, label) ->
            if (index > 0) spanned.append(HINT_GAP)
            val boldStart = spanned.length
            spanned.append(key)
            spanned.setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                boldStart,
                spanned.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spanned.setSpan(
                android.text.style.ForegroundColorSpan(tokens.colors.text),
                boldStart,
                spanned.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spanned.append(" ").append(label)
        }
        operationHint = ui.text("", tokens.type.caption, tokens.colors.muted).apply {
            setText(spanned)
            visibility = View.GONE
        }
    }

    private fun buildSidebar() {
        sidebar = PlayerSidebar(
            ui,
            object : PlayerSidebar.Callbacks {
                override fun selectEpisode(index: Int) {
                    // 选集之后回到选集页：用户可能还要接着挑，不该把他留在输入框里
                    sidebar.setMode(PlayerSidebar.Mode.EPISODES)
                    session.selectEpisode(index)
                    render()
                }

                override fun changeSetting(setting: PlayerSetting) {
                    applySetting(setting)
                    render()
                }

                override fun close() = closeMenu()

                override fun pageChanged() = render()
            },
        )
        // 压暗层垫在抽屉之下、画面之上；显隐跟着抽屉走（render 里同步）
        scrim.setBackgroundColor(tokens.colors.scrim)
        root.addView(
            scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // 操作提示与抽屉装进同一个贴底栈：提示在上、抽屉在下，一起出现一起消失
        val stack = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(
            operationHint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = ui.dp(tokens.space.s)
                // 提示贴着抽屉左缘再往右挪一档（mockup .op-hint{left:20px}）
                leftMargin = ui.dp(HINT_MARGIN_LEFT)
            },
        )
        stack.addView(sidebar.view)
        val marginH = ui.dp(tokens.metrics.drawerPaddingH)
        // B 方案：抽屉贴底（左右 18dp、底部 16dp），高度 286dp 由抽屉自己定
        root.addView(
            stack,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                leftMargin = marginH
                rightMargin = marginH
                bottomMargin = ui.dp(tokens.metrics.overlayBottomGap)
            },
        )
    }

    // ---------- 数据 ----------

    private fun loadDetail() {
        scope.launch {
            // 脏历史/收藏里的非法 id 会从仓库 require 抛 IllegalArgumentException：兜成失败态，不崩进程。
            val loaded = loadCatching({ loadFailure = it }) { host.content.detail(seriesId) }
            if (!isActive) return@launch
            if (loaded == null) {
                render()
                return@launch
            }
            detail = loaded
            // 起播的其余步骤与详情加载同一待遇：它们在 loadCatching
            // 之外抛任何异常，都会被宿主作用域静默吃进 logcat——loadFailure 为 null、
            // 状态停在 Idle、画面全黑、按键无响应，用户只能杀进程。宁可多一张失败卡。
            val started = loadCatching({ loadFailure = it }) {
                // 历史行是"播过才有的"：拿到详情时把这一行的元数据补全（旧记录的分类常常是空的，
                // 历史页信息栏因此缺一段）。只更新已有行，不会因为打开播放页就造一条历史
                host.progress.rememberSeries(loaded.series)
                // 季线预取失败降级成"少连一季"（内部网络请求已自带兜底，这里防它的组装路径）
                runCatching { preloadSeasonLine(loaded) }
                session.start(loaded, startIndex, startPositionMs)
                true
            }
            if (started == null) {
                render()
                return@launch
            }
            // 建页时就已退到后台：补一次暂停，别让流装成"自动播放"
            if (pausedBeforeStart) session.onLifecyclePause()
            render()
        }
    }

    /**
     * 季线预取：跨季连播要读 `FavoritesPort.seasonLine`，而缓存只在"详情页收藏时"才落库。
     * 从历史/搜索直接"继续播放"的路径不会经过详情页，这里补一次，失败静默（少连一季，不该打断播放）。
     *
     * **不要求已收藏**：未收藏的多季剧同样要跨季续播；而且拿不到季线时
     * 播放器无法判断"是不是最后一季"，片尾就不敢说"全剧已播完"。
     */
    private fun preloadSeasonLine(data: Detail) {
        // 与详情/系列页同一条键：收藏过的剧用收藏行键，避免分宗篇/折桂篇各落一份缓存。
        val key = FavoriteLookup.favoriteKey(data.series, host.favorites)
        if (key.isBlank()) return
        if (host.favorites.seasonLine(key) != null) return
        // 读请求挂页面作用域：用户离开播放页就不必再为"也许有下一季"发请求
        scope.launch {
            // 同 loadDetail：脏数据导致的任何异常都按"缺季线"静默。
            val line = loadCatching { host.content.seasons(data.series.id, data.series) } ?: return@launch
            if (!isActive) return@launch
            host.favorites.rememberSeasonLine(key, line)
            // 季线里的季凡是有观看记录的都回填完整元数据：跨季连播直接落进来的季
            // 起初只有"id + 进度"，历史页不能是无名黑块（2026-10-24 收藏闭环口径）
            SeasonLineBackfill.run(line, host.progress)
        }
    }

    // ---------- 片尾预取 ----------

    /**
     * 片尾前把下一集的流备好：到了片尾只是"换一条已经打开的流"，不再等取地址与解索引。
     *
     * 页面只负责把"播放器现在是什么样"喂给 [EpisodePrefetcher]；
     * 该不该取、失败怎么退避、预备流归谁，都在那一层。
     */
    private fun maybePrefetch() {
        val data = detail
        val state = session.state as? PlaybackState.Playing
        if (data == null || state == null) {
            prefetcher.clear()
            return
        }
        prefetcher.tick(
            nextVid = data.episodes.getOrNull(state.index + 1)?.vid,
            quality = session.quality,
            conditions = EpisodePrefetcher.Conditions(
                positionMs = engine.positionMs,
                durationMs = engine.durationMs,
                bufferedPositionMs = engine.bufferedPositionMs,
                speed = session.effectiveSpeed,
                playing = state.pausedBy == null,
                autoNext = host.settings.current().autoNext,
                lowRam = tokens.tier == UiTier.LOW_RAM,
                online = isOnline(),
                metered = meteredNetwork(),
            ),
        )
    }

    /**
     * 计费网络（手机热点）不预取：下一集不是用户此刻要的，不该花他现在的流量。
     * 拿不到网络能力时按计费处理（保守）。
     */
    @SuppressLint("MissingPermission")
    private fun meteredNetwork(): Boolean {
        val manager = ui.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---------- 渲染 ----------

    private fun render() {
        if (!::session.isInitialized || !::notice.isInitialized || !::sidebar.isInitialized) return
        notice.render(session.state, loadFailure, sidebar.visible, lowerQuality(), noticeTitle())
        if (sidebar.visible) sidebarModel()?.let { sidebar.bind(it) }
        // 压暗层与抽屉同进退；操作提示（2026-10-24 口径）与抽屉同进退：
        // 按 MENU 才出现、贴在抽屉上沿，平时画面保持纯净（不再常驻顶部）。
        scrim.visibility = if (sidebar.visible) View.VISIBLE else View.GONE
        operationHint.visibility = if (sidebar.visible) View.VISIBLE else View.GONE
        syncFocus()
    }

    /** 提示层信息栏左侧的"这部剧"：`剧名 · 第 x 集`；详情没取回来时是空串（只显示原因侧）。 */
    private fun noticeTitle(): String =
        detail?.let { PlayerText.title(it.series.title, currentIndex()) } ?: ""

    /** 比当前低一档的清晰度；已经是最低档返回 null。 */
    private fun lowerQuality(): Int? = PlaybackOptions.qualities.filter { it < session.quality }.maxOrNull()

    private fun sidebarModel(): PlayerSidebar.Model? {
        val data = detail ?: return null
        if (data.episodes.isEmpty()) return null
        val state = session.state
        val index = state.indexOr(startIndex).coerceIn(0, data.episodes.lastIndex)
        // 读**会话自持的值**，不读端口的异步写入结果：否则改完倍速要等下一次 tick 才看到新标签
        val current = host.settings.current().copy(
            speed = session.speed,
            quality = session.quality,
            frame = session.frame,
        )
        return PlayerSidebar.Model(
            title = PlayerText.title(data.series.title, index),
            status = PlayerText.drawerStatus(state),
            total = data.episodes.size,
            current = index,
            watchedUpTo = host.progress.watchedUpTo(data.series.id),
            settings = PlayerText.settingsRows(current, sleepLabel()),
        )
    }

    private fun sleepLabel(): String =
        TvText.sleepTimer(session.sleep.remainingMs, session.sleep.episodesLeft)

    // ---------- 焦点 ----------

    /**
     * 焦点只在**结构变了**的时候重提：侧栏开关、模式/翻组变化、提示卡按钮集合变化。
     * 每秒一次的 tick 不会重提，否则用户正在第三行按着方向键，光标会被按回第一行。
     */
    private fun syncFocus() {
        val token = buildString {
            append(sidebar.visible)
            if (sidebar.visible) append(':').append(sidebar.bindingSignature())
            append(':').append(notice.signature())
        }
        if (token == lastFocusToken) return
        lastFocusToken = token
        bindFocus(null)
    }

    private fun bindFocus(preferred: FocusKey?) {
        if (sidebar.visible) {
            // 结构变了但用户没给目标时，优先留在"现在正被按的那一项"（例如翻组后的长条）；
            // 否则每翻一组光标都被拽回当前集，连翻两次得先挪回来
            val keep = preferred ?: sidebar.focusedKey()
            val binding = sidebar.focusBinding(keep) ?: return
            // 抽屉里是"分集格 → 两侧长条 → 右列设置"的一条横带：方向键按视觉位置走
            focus.bind(binding.plan, binding.views, visual = true)
            requestFocus(binding.preferred)
            return
        }
        val binding = notice.focusBinding(preferred) ?: return
        focus.bind(binding.plan, binding.views, visual = true)
        requestFocus(binding.preferred)
    }

    private fun requestFocus(key: FocusKey?) {
        if (focus.request(key)) return
        // 视图还没挂上窗口时一定失败：等一次 post 再要，别让光标留在页外
        root.post { focus.request(key) }
    }

    // ---------- 侧栏开关 ----------

    private fun openMenu() {
        if (detail == null || sidebar.visible) return
        // 打开侧栏就暂停：预备流也一并收掉（暂停时不预取，也不占着连接）
        prefetcher.clear()
        session.openMenu()
        sidebar.setMode(PlayerSidebar.Mode.EPISODES)
        sidebar.show()
        render()
        bindFocus(PlayerSidebar.episodeKey(currentIndex()))
    }

    private fun closeMenu() {
        if (!sidebar.visible) return
        sidebar.hide()
        session.closeMenu()
        render()
    }

    private fun currentIndex(): Int = session.state.indexOr(startIndex)

    // ---------- 播放设置 ----------

    private fun applySetting(setting: PlayerSetting) {
        val current = host.settings.current()
        when (setting) {
            PlayerSetting.SPEED -> session.setSpeed(next(current.speed, PlaybackOptions.speeds))
            PlayerSetting.QUALITY -> session.setQuality(next(current.quality, PlaybackOptions.qualities))
            PlayerSetting.FRAME -> session.setFrame(next(current.frame, FrameMode.entries))
            PlayerSetting.SLEEP -> cycleSleep()
            PlayerSetting.RESTART -> session.restart()
        }
    }

    private fun cycleSleep() {
        // 从**真实状态**推当前档位，而不是记一个自己会飘的下标：
        // 定时到点会自己取消，记在下标里的位置就跟用户看到的那行字对不上了
        val remaining = session.sleep.remainingMs
        val episodes = session.sleep.episodesLeft
        val current = when {
            remaining != null -> {
                val minutes = ((remaining + 59_999) / 60_000).toInt()
                when {
                    minutes >= 90 -> SleepChoice.M90
                    minutes >= 60 -> SleepChoice.M60
                    minutes >= 30 -> SleepChoice.M30
                    else -> SleepChoice.M15
                }
            }
            episodes >= 5 -> SleepChoice.EPISODES_5
            episodes >= 3 -> SleepChoice.EPISODES_3
            episodes >= 1 -> SleepChoice.EPISODES_1
            else -> SleepChoice.OFF
        }
        when (val next = current.next()) {
            SleepChoice.OFF -> session.sleepFor(null)
            SleepChoice.EPISODES_1 -> session.sleepAfterEpisodes(1)
            SleepChoice.EPISODES_3 -> session.sleepAfterEpisodes(3)
            SleepChoice.EPISODES_5 -> session.sleepAfterEpisodes(5)
            else -> session.sleepFor(next.minutes)
        }
    }

    /** 下一档；当前值不在档位里（盘上被改坏）时回到第一档。 */
    private fun <T> next(value: T, options: List<T>): T {
        val index = options.indexOf(value)
        return if (index < 0) options.first() else options[(index + 1) % options.size]
    }

    private enum class SleepChoice(val minutes: Int) {
        OFF(0),
        M15(15),
        M30(30),
        M60(60),
        M90(90),
        EPISODES_1(0),
        EPISODES_3(0),
        EPISODES_5(0),
        ;

        fun next(): SleepChoice = entries[(ordinal + 1) % entries.size]
    }

    // ---------- 按键 ----------

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::session.isInitialized) return false
        val key = playerKeyOf(event.keyCode) ?: return false
        val press = pressKindOf(event)
        if (sidebar.visible) return handleSidebarKey(key, press)
        if (notice.actionable) {
            // 提示卡上有按钮：方向键/确认交回焦点系统，否则按钮永远按不到；
            // 但 MENU 与返回必须由播放页吃掉——退给系统的话，出错/结束态下按返回会直接退出应用。
            if (key == PlayerKey.MENU && press == PressKind.TAP) {
                openMenu()
                return true
            }
            if (key == PlayerKey.BACK && press == PressKind.TAP) {
                host.navigator.back()
                return true
            }
            return false
        }
        if (press == PressKind.TAP) repeatGate.reset()
        if (key == PlayerKey.LEFT && press == PressKind.REPEAT && !repeatGate.accept(nowMillis())) return true
        PlayerKeys.commandFor(key, press)?.let(::execute)
        return true
    }

    private fun handleSidebarKey(key: PlayerKey, press: PressKind): Boolean {
        if (press != PressKind.TAP) return false
        if (key == PlayerKey.MENU) {
            closeMenu()
            return true
        }
        if (key == PlayerKey.BACK) {
            if (sidebar.mode != PlayerSidebar.Mode.EPISODES) {
                sidebar.setMode(PlayerSidebar.Mode.EPISODES)
                render()
                bindFocus(PlayerSidebar.episodeKey(currentIndex()))
            } else {
                closeMenu()
            }
            return true
        }
        if (sidebar.mode == PlayerSidebar.Mode.JUMP) {
            when (key) {
                PlayerKey.DELETE -> {
                    sidebar.backspace()
                    return true
                }
                PlayerKey.CENTER -> {
                    sidebar.submitDigits()
                    return true
                }
                else -> {
                    val digit = PlayerKeys.digitOf(key) ?: return false
                    sidebar.appendDigit(digit)
                    return true
                }
            }
        }
        // 抽屉里直接按数字 = 进跳集模式（B 方案：不再需要先按「跳转集数」按钮）
        PlayerKeys.digitOf(key)?.let { digit ->
            sidebar.enterJump()
            sidebar.appendDigit(digit)
            render()
            return true
        }
        return false
    }

    private fun execute(command: PlayerCommand) {
        when (command) {
            is PlayerCommand.SeekBy -> session.seekBy(command.deltaMs)
            is PlayerCommand.FastForward -> session.fastForward(command.on)
            is PlayerCommand.SkipEpisode -> session.skip(command.delta)
            PlayerCommand.TogglePlay -> session.toggle()
            PlayerCommand.Play -> session.play()
            PlayerCommand.Pause -> session.pause()
            PlayerCommand.OpenSidebar -> openMenu()
            PlayerCommand.Back -> host.navigator.back()
        }
    }

    private fun runNoticeAction(action: NoticeAction) {
        when (action) {
            // 「立即重试」有两种来由：详情没取回来、或播放本身出错
            NoticeAction.RETRY -> if (loadFailure != null) {
                loadFailure = null
                render()
                loadDetail()
            } else {
                session.retry()
            }

            NoticeAction.LOWER_QUALITY -> lowerQuality()?.let { session.setQuality(it) }
            NoticeAction.STOP_RETRY -> session.stopAutoRetry()
            NoticeAction.REPLAY -> {
                session.restart()
                session.play()
            }

            NoticeAction.NEXT -> session.skip(1)
            NoticeAction.RESUME -> session.play()
            NoticeAction.BACK -> host.navigator.back()
        }
    }

    /** 每秒一次：定时停止到点、侧栏状态行刷新。 */
    private val tick = object : Runnable {
        override fun run() {
            session.onTick()
            maybePrefetch()
            if (sidebar.visible) sidebarModel()?.let { sidebar.bind(it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ---------- 生命周期 ----------

    fun pause() {
        prefetcher.clear()
        if (::session.isInitialized) session.onLifecyclePause() else pausedBeforeStart = true
        // 后台不心跳：定时停止、断网重连都等回到前台再说。
        // 留着 tick 的话，"等联网自动重试"会在用户看不见的时候重新取流并开播。
        handler.removeCallbacks(tick)
    }

    /** 回到前台：恢复每秒一次的心跳。 */
    fun resume() {
        if (!::session.isInitialized) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    fun release() {
        prefetcher.clear()
        handler.removeCallbacksAndMessages(null)
        streamJob?.cancel()
        jobs.cancel()
        // 监听挂到窗口级 observer 上：不摘就一直持有本页。observer 已死时 remove 会抛异常
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(focusListener)
        if (::session.isInitialized) session.release()
        toast?.cancel()
    }

    // ---------- PlayerHost ----------

    override fun requestStream(vid: String, quality: Int) {
        streamJob?.cancel()
        streamJob = null
        // 片尾预取好了就直接换上去：取地址与解索引都已经在本地完成，
        // 画面上只剩"换源"这一下，不再有等网络的转圈。
        val prepared = prefetcher.take(vid, quality)
        if (prepared != null) {
            session.onStreamReady(prepared, seamless = true)
            return
        }
        streamJob = scope.launch {
            val stream = try {
                host.media.open(vid, quality)
            } catch (cancelled: CancellationException) {
                // 取消不是失败：吞掉会破坏结构化并发
                throw cancelled
            } catch (error: Exception) {
                // 端口契约只承诺 UpstreamError，但解密层的 require/容器校验会抛别的异常。
                // 这里接住并归成"取流失败"，绝不让它冒到主线程变成崩溃。
                if (isActive) session.onStreamFailed(error)
                return@launch
            }
            if (!isActive) {
                stream.close()
                return@launch
            }
            session.onStreamReady(stream)
        }
    }

    override fun save(progress: Progress) {
        // 写盘挂宿主作用域：页面马上要被路由销毁时，挂在页面作用域会"还没写就被取消"
        host.scope.launch {
            host.progress.save(progress)
            // 新记录只带 series_id，标题/封面/分类得从详情补上——否则历史页的卡片是无名黑块
            detail?.series?.let { host.progress.rememberSeries(it) }
        }
    }

    override fun applySettings(transform: (Settings) -> Settings) {
        host.scope.launch { host.settings.update(transform) }
    }

    override fun openPlayer(seriesId: String, episodeIndex: Int, positionMs: Long) {
        host.navigator.toPlayer(seriesId, episodeIndex, positionMs)
    }

    override fun keepScreenOn(on: Boolean) {
        engine.view().keepScreenOn = on
    }

    override fun notify(text: String) {
        toast?.cancel()
        toast = Toast.makeText(ui.context, text, Toast.LENGTH_SHORT).apply { show() }
    }

    /**
     * 断网判断：状态机据此决定"排自动重试"还是"等联网"。
     *
     * 权限 `ACCESS_NETWORK_STATE` 由 **app 模块的清单**声明（S0 起就有）：应用权限只在壳里治理，
     * 不散到各 feature 模块的清单里，所以这里按 lint 提示静音——它只看本模块的清单。
     */
    @SuppressLint("MissingPermission")
    override fun isOnline(): Boolean {
        val manager = ui.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val PAGE_KEY = "player"
        const val TICK_MS = 1_000L

        /** 长按左键的重复间隔：比系统连发（约 50ms）慢得多，快退才不会一按就飞到片头。 */
        const val SEEK_REPEAT_GATE_MS = 320L

        /** 操作提示段与段之间的全角空格（mockup `.op-hint` 的排版）。 */
        const val HINT_GAP = "　"

        /** 操作提示离抽屉左缘的距离（mockup `.op-hint{left:20px}`）。 */
        const val HINT_MARGIN_LEFT = 20
    }
}

/** 状态里的"当前第几集"；`Idle` 时用入口给的下标兜底。 */
private fun PlaybackState.indexOr(fallback: Int): Int = when (this) {
    PlaybackState.Idle -> fallback
    is PlaybackState.Preparing -> index
    is PlaybackState.Playing -> index
    is PlaybackState.Ended -> index
    is PlaybackState.Failed -> index
}

/** 状态机只认 [Scheduler]，页面用主线程 Handler 实现。 */
private class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun after(delayMs: Long, action: () -> Unit): ScheduledTask {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return ScheduledTask { handler.removeCallbacks(runnable) }
    }
}
