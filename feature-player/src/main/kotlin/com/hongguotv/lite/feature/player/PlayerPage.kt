package com.hongguotv.lite.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.PlaybackOptions
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.model.Settings
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSession
import com.hongguotv.lite.uikit.focus.KeyRepeatGate
import com.hongguotv.lite.uikit.text.TvText
import com.hongguotv.lite.uikit.token.Tokens
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
 * （页面马上销毁也不会"还没写就被取消"）、只捕 `UpstreamError`（装配错误要炸，不要伪装成加载失败）。
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
    private val focus = FocusSession(PAGE_KEY)
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
        session.onStateChanged = { render() }
        notice = PlayerNotice(ui) { action -> runNoticeAction(action) }
        notice.attachTo(root)
        buildSidebar()
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, view -> focus.onFocusChanged(view) }
        handler.postDelayed(tick, TICK_MS)
        loadDetail()
    }

    // ---------- 视图 ----------

    private fun buildSidebar() {
        sidebar = PlayerSidebar(
            ui,
            object : PlayerSidebar.Callbacks {
                override fun onMode(mode: PlayerSidebar.Mode) {
                    sidebar.setMode(mode)
                    render()
                    bindFocus(PlayerSidebar.KEY_MODE)
                }

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
            },
        )
        val width = (ui.context.resources.displayMetrics.widthPixels * tokens.metrics.sidebarWidthRatio).toInt()
        val margin = ui.dp(tokens.space.xl)
        root.addView(
            sidebar.view,
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
                topMargin = margin
                bottomMargin = margin
                rightMargin = margin
            },
        )
    }

    // ---------- 数据 ----------

    private fun loadDetail() {
        scope.launch {
            val loaded = try {
                host.content.detail(seriesId)
            } catch (error: UpstreamError) {
                loadFailure = error
                null
            }
            if (!isActive) return@launch
            if (loaded == null) {
                render()
                return@launch
            }
            detail = loaded
            preloadSeasonLine(loaded)
            session.start(loaded, startIndex, startPositionMs)
            render()
        }
    }

    /**
     * 季线预取：跨季连播要读 `FavoritesPort.seasonLine`，而它只在"收藏过的剧 + 详情页收藏时"才落库。
     * 从历史里直接"继续播放"的路径不会经过详情页，这里补一次，失败静默（少连一季，不该打断播放）。
     */
    private fun preloadSeasonLine(data: Detail) {
        val key = SeasonKeys.seasonKey(data.series.title)
        if (key.isBlank()) return
        if (!host.favorites.isFavorite(key)) return
        if (host.favorites.seasonLine(key) != null) return
        // 读请求挂页面作用域：用户离开播放页就不必再为"也许有下一季"发请求
        scope.launch {
            val line = try {
                host.content.seasons(data.series.id, data.series)
            } catch (error: UpstreamError) {
                null
            } ?: return@launch
            if (!isActive) return@launch
            host.favorites.rememberSeasonLine(key, line)
        }
    }

    // ---------- 渲染 ----------

    private fun render() {
        if (!::session.isInitialized || !::notice.isInitialized || !::sidebar.isInitialized) return
        notice.render(session.state, loadFailure, sidebar.visible, lowerQuality())
        if (sidebar.visible) sidebarModel()?.let { sidebar.bind(it) }
        syncFocus()
    }

    /** 比当前低一档的清晰度；已经是最低档返回 null。 */
    private fun lowerQuality(): Int? = PlaybackOptions.qualities.filter { it < session.quality }.maxOrNull()

    private fun sidebarModel(): PlayerSidebar.Model? {
        val data = detail ?: return null
        if (data.episodes.isEmpty()) return null
        val state = session.state
        val index = state.indexOr(startIndex).coerceIn(0, data.episodes.lastIndex)
        val position = if (engine.ready) engine.positionMs else 0L
        // 读**会话自持的值**，不读端口的异步写入结果：否则改完倍速要等下一次 tick 才看到新标签
        val current = host.settings.current().copy(
            speed = session.speed,
            quality = session.quality,
            frame = session.frame,
        )
        return PlayerSidebar.Model(
            title = PlayerText.title(data.series.title, index),
            status = PlayerText.status(
                state = state,
                positionMs = position,
                durationMs = engine.durationMs,
                quality = current.quality,
                speed = current.speed,
                sleep = sleepLabel(),
            ),
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
            val binding = sidebar.focusBinding(preferred) ?: return
            focus.bind(binding.plan, binding.views)
            requestFocus(binding.preferred)
            return
        }
        val binding = notice.focusBinding(preferred) ?: return
        focus.bind(binding.plan, binding.views)
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
                bindFocus(PlayerSidebar.KEY_MODE)
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
            if (sidebar.visible) sidebarModel()?.let { sidebar.bind(it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ---------- 生命周期 ----------

    fun pause() {
        if (::session.isInitialized) session.onLifecyclePause()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        streamJob?.cancel()
        jobs.cancel()
        if (::session.isInitialized) session.release()
        toast?.cancel()
    }

    // ---------- PlayerHost ----------

    override fun requestStream(vid: String, quality: Int) {
        streamJob?.cancel()
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
        host.scope.launch { host.progress.save(progress) }
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
