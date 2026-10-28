package com.hongguotv.lite.feature.player

import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.PlaybackOptions
import com.hongguotv.core.model.Progress
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.MediaStream
import com.hongguotv.core.port.SettingsPort
import com.hongguotv.core.usecase.EndAction
import com.hongguotv.core.usecase.EpisodeFlow
import com.hongguotv.core.usecase.FavoriteLookup
import com.hongguotv.core.usecase.RecoveryBudget
import com.hongguotv.core.usecase.ResumePoint
import com.hongguotv.core.usecase.SleepTimer
import com.hongguotv.lite.uikit.text.TvText

/**
 * 播放会话：**播放这一件事的唯一所有者**。
 *
 * 三条设计约束，缺一条这套东西就会退化成旧实现的布尔拼装：
 * 1. **不 import `android.*`，不持协程**——它是纯状态机，被"用户命令 / 引擎回调 / IO 结果"三类事件驱动，
 *    因此能在 JVM 上把"片尾连播、断网重试、定时停止、关菜单续播"全部断言一遍；
 * 2. **副作用只从 [PlayerHost] 出去**——取流、写进度、写设置、跨季跳转都由页面决定挂在哪个作用域；
 * 3. **片尾判定走 core 的 `EpisodeFlow`**——与详情页那句「本季看完后会接着播《X》」同源。
 *
 * 起播位置**不再自己从 `Progress` 推**：调用方（详情页/系列页/历史页）已经按 `ResumePoint` 归一化过了。
 */
class PlaybackSession(
    private val engine: PlaybackEngine,
    private val settings: SettingsPort,
    private val favorites: FavoritesPort,
    private val host: PlayerHost,
    private val scheduler: Scheduler,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    var state: PlaybackState = PlaybackState.Idle
        private set

    /** 状态变化回调。页面据此刷新侧栏状态行与提示卡。 */
    var onStateChanged: ((PlaybackState) -> Unit)? = null

    /** 定时停止。会话级状态，关掉应用就失效。 */
    val sleep: SleepTimer = SleepTimer(clock)

    private val recovery = RecoveryBudget()
    private var detail: Detail? = null
    private var index = 0
    private var startPositionMs = 0L
    private var retryPositionMs = 0L
    private var pausedBy: PauseReason? = null
    private var menuOpen = false
    private var fastForward = false
    private var userSpeed: Float = PlaybackOptions.normalizeSpeed(1f)
    private var streamLoaded = false
    private var pendingRetry: ScheduledTask? = null

    /** 当前生效的倍速 / 清晰度 / 画面模式。侧栏读这三个，**不读端口的异步写入结果**。 */
    var speed: Float = PlaybackOptions.normalizeSpeed(1f)
        private set

    var quality: Int = PlaybackOptions.qualities.last()
        private set

    var frame: FrameMode = FrameMode.FIT
        private set

    /**
     * 实际生效的倍速：按住右键的临时 2 倍速也算。
     * 片尾预取按墙钟时间算窗口，读的必须是这个值而不是 [speed]（用户设定值）。
     */
    val effectiveSpeed: Float get() = if (fastForward) PlayerKeys.FAST_FORWARD_SPEED else userSpeed

    /** 侧栏是否打开。 */
    val isMenuOpen: Boolean get() = menuOpen

    // ---------- 起播 ----------

    /**
     * 开始播放。[index] 与 [positionMs] 由调用方给（已归一化，播放器不再自己从 `Progress` 推）。
     */
    fun start(detail: Detail, index: Int, positionMs: Long) {
        engine.setListener(engineListener)
        val current = settings.current()
        speed = PlaybackOptions.normalizeSpeed(current.speed)
        userSpeed = speed
        quality = PlaybackOptions.normalizeQuality(current.quality)
        frame = current.frame
        // 画面模式是"进来就该是上次设的样"：只在切换时才应用会让每次重进都回落到默认
        engine.setFrame(frame)
        this.detail = detail
        pausedBy = null
        if (detail.episodes.isEmpty()) {
            setState(PlaybackState.Ended(0, lastOfSeason = true))
            return
        }
        playEpisode(index, positionMs)
    }

    /**
     * 换集/重取流。[keepBudget] 为 true 表示这是"自动重试"的一环，不能把重试预算清掉
     * （旧实现用 `recovering` 参数表达同一件事）。
     */
    private fun playEpisode(target: Int, positionMs: Long, keepBudget: Boolean = false) {
        val data = detail ?: return
        if (data.episodes.isEmpty()) return
        cancelRetry()
        saveProgressIfReady()
        index = target.coerceIn(0, data.episodes.lastIndex)
        startPositionMs = positionMs.coerceAtLeast(0)
        retryPositionMs = startPositionMs
        streamLoaded = false
        if (!keepBudget) recovery.reset()
        engine.stop()
        setState(PlaybackState.Preparing(index))
        host.requestStream(data.episodes[index].vid, quality)
    }

    /**
     * 页面取流成功。引擎装载之后由引擎回调把状态推到 [PlaybackState.Playing]。
     *
     * [seamless] 只影响显示：片尾前预取好、接下一集的那条流不在画面上闪缓冲转圈。
     */
    fun onStreamReady(stream: MediaStream, seamless: Boolean = false) {
        streamLoaded = true
        engine.setSpeed(effectiveSpeed)
        engine.load(stream, startPositionMs, playWhenReady = pausedBy == null, seamless = seamless)
        setState(PlaybackState.Playing(index, pausedBy, buffering = true))
    }

    /** 页面取流失败。归类成 [PlaybackFailure.Unavailable]，按预算自动重试。 */
    fun onStreamFailed(cause: Throwable) {
        fail(PlaybackFailure.Unavailable(cause))
    }

    // ---------- 播放命令 ----------

    /** 确认键 / 媒体播放暂停键：一个键盖三种情形（出错→重试、结束→重播、其余→切换）。 */
    fun toggle() {
        when (val current = state) {
            is PlaybackState.Failed -> retry()
            is PlaybackState.Ended -> {
                engine.seekTo(0)
                play()
            }
            is PlaybackState.Playing -> if (current.pausedBy == null) pause(PauseReason.USER) else play()
            else -> Unit
        }
    }

    fun play() {
        if (state is PlaybackState.Failed) {
            retry()
            return
        }
        // 停在片尾时再按播放：先回到片头。media3 的 STATE_ENDED 下 play() 不会自己回头，
        // 不显式 seek 的话用户按了播放键却什么都没发生——定时停止正好停在片尾时最容易撞上。
        if (state is PlaybackState.Ended || engine.ended) engine.seekTo(0)
        cancelRetry()
        pausedBy = null
        engine.play()
        if (state is PlaybackState.Playing || state is PlaybackState.Ended) {
            setState(PlaybackState.Playing(index, null, engine.buffering))
        }
        host.keepScreenOn(true)
    }

    /** 暂停。[reason] 决定"谁能恢复"：菜单只看 MENU，用户手动暂停不会被关菜单"续"回去。 */
    fun pause(reason: PauseReason = PauseReason.USER) {
        pausedBy = reason
        if (state is PlaybackState.Playing) {
            engine.pause()
            saveProgressIfReady()
            host.keepScreenOn(false)
            setState(PlaybackState.Playing(index, reason, engine.buffering))
        }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(currentPositionMs() + deltaMs)
    }

    fun seekTo(positionMs: Long) {
        // 用 streamLoaded 而不是 ready：缓冲中位置同样有效，用 ready 会让"缓冲时按左右键没反应"
        if (!streamLoaded) return
        val duration = engine.durationMs.coerceAtLeast(0)
        if (duration <= 0) return
        val target = positionMs.coerceIn(0, (duration - SEEK_TAIL_MS).coerceAtLeast(0))
        engine.seekTo(target)
        if (state is PlaybackState.Playing) setState(PlaybackState.Playing(index, pausedBy, engine.buffering))
        host.notify("${TvText.clock(target)} / ${TvText.clock(duration)}")
    }

    fun skip(delta: Int) {
        val data = detail ?: return
        val target = index + delta
        if (target !in data.episodes.indices) return
        playEpisode(target, 0L)
    }

    /**
     * 侧栏选集。点当前这一集**不重新加载**（用户只是想关掉侧栏），
     * 但如果是出错态，点它就等于"重试这一集"。
     */
    fun selectEpisode(target: Int) {
        val data = detail ?: return
        if (target !in data.episodes.indices) return
        if (target == index) {
            if (state is PlaybackState.Failed) retry() else closeMenu()
            return
        }
        playEpisode(target, 0L)
    }

    fun restart() {
        if (streamLoaded) engine.seekTo(0)
    }

    /** 按住右键的临时 2 倍速。**不进设置**：松手就回到用户设定的倍速。 */
    fun fastForward(on: Boolean) {
        if (fastForward == on) return
        fastForward = on
        engine.setSpeed(if (on) PlayerKeys.FAST_FORWARD_SPEED else userSpeed)
        // 用户本来就把倍速设成 2× 时，没必要再喊一句"2 倍速快进"
        if (on && userSpeed != PlayerKeys.FAST_FORWARD_SPEED) host.notify(PlayerText.FAST_FORWARD)
    }

    fun setSpeed(value: Float) {
        val normalized = PlaybackOptions.normalizeSpeed(value)
        speed = normalized
        userSpeed = normalized
        host.applySettings { it.copy(speed = normalized) }
        if (!fastForward) engine.setSpeed(normalized)
        refresh()
    }

    /**
     * 改清晰度：写设置后**从当前位置重新取流**（旧行为）。
     *
     * 不继承重试预算：换清晰度是一次新的观看意图，不是"自动重试的一环"。
     * 继承的话，上一档把三次预算烧完之后换到 720P，新流首败就直接判 `Exhausted`，
     * 用户看到"已自动重试 3 次"却一次都没重试过。
     */
    fun setQuality(value: Int) {
        val normalized = PlaybackOptions.normalizeQuality(value)
        if (normalized == quality) return
        quality = normalized
        host.applySettings { it.copy(quality = normalized) }
        if (detail == null) return
        playEpisode(index, currentPositionMs())
    }

    fun setFrame(mode: FrameMode) {
        frame = mode
        engine.setFrame(mode)
        host.applySettings { it.copy(frame = mode) }
    }

    // ---------- 定时停止 ----------

    fun sleepFor(minutes: Int?) {
        if (minutes == null) sleep.cancel() else sleep.afterMinutes(minutes)
        refresh()
    }

    fun sleepAfterEpisodes(count: Int?) {
        if (count == null) sleep.cancel() else sleep.afterEpisodes(count)
        refresh()
    }

    /**
     * 页面每秒调一次。做两件事：
     * - 定时到点就停（只对"正在播"生效，暂停时不该突然弹提示）；
     * - 断网时排的是"等联网"，这里负责发现网回来了（没有 NetworkCallback 的心跳，一秒一次够用）。
     */
    fun onTick() {
        val failed = state as? PlaybackState.Failed
        if (failed != null && failed.retry == RetryState.WaitingNetwork) {
            if (host.isOnline()) playEpisode(index, retryPositionMs, keepBudget = true)
            return
        }
        val playing = state as? PlaybackState.Playing ?: return
        if (playing.pausedBy != null) return
        if (sleep.poll()) stopForSleep()
    }

    private fun stopForSleep() {
        pausedBy = PauseReason.SLEEP
        engine.pause()
        saveProgressIfReady()
        host.keepScreenOn(false)
        cancelRetry()
        setState(PlaybackState.Playing(index, PauseReason.SLEEP, engine.buffering))
    }

    // ---------- 侧栏 ----------

    fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        // 取流还没完成（Preparing）时也要记下"因为菜单而暂停"，否则取流一回来就带着声音开播，
        // 侧栏开着却听见片头在放（旧实现是 `playWhenReady && playerMenu == null`）。
        if (pausedBy == null) {
            if (state is PlaybackState.Playing) {
                pause(PauseReason.MENU)
            } else {
                // 取流还没完成：只记下"因为菜单而暂停"，装载时按 playWhenReady = false 起
                pausedBy = PauseReason.MENU
                refresh()
            }
        } else {
            refresh()
        }
    }

    fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        if (pausedBy == PauseReason.MENU) play() else refresh()
    }

    // ---------- 生命周期与释放 ----------

    /** 应用退到后台：暂停、保存、停掉在途重试。回到前台**不自动续播**。 */
    fun onLifecyclePause() {
        // 定时停止是"这一次观看"的意图：退到后台就不该继续算（旧行为）。
        // 不取消的话，用户半小时后回来一按播放，会被一个早已过期的定时立刻停住。
        sleep.cancel()
        when (state) {
            is PlaybackState.Playing -> pause(PauseReason.LIFECYCLE)
            // 取流还没完成就退到后台：只记下"因为生命周期而暂停"。
            // 不记的话，流回来时按 `playWhenReady = pausedBy == null` 装，会在后台带着声音开播。
            // **只认 Preparing**：出错 / 片尾时若也记上，用户回前台按「重试 / 下一集」会装载成暂停态，
            // 提示卡消失、画面不动——那是把一个本该能播的操作变成按了没反应。
            is PlaybackState.Preparing -> if (pausedBy == null) {
                pausedBy = PauseReason.LIFECYCLE
                refresh()
            }
            else -> Unit
        }
        cancelRetry()
    }

    fun release() {
        cancelRetry()
        saveProgressIfReady()
        sleep.cancel()
        host.keepScreenOn(false)
        engine.release()
        state = PlaybackState.Idle
    }

    // ---------- 失败与重试 ----------

    fun retry() {
        cancelRetry()
        recovery.reset()
        playEpisode(index, retryPositionMs)
    }

    fun stopAutoRetry() {
        cancelRetry()
        val failed = state as? PlaybackState.Failed ?: return
        setState(failed.copy(retry = RetryState.None))
    }

    private fun fail(failure: PlaybackFailure) {
        retryPositionMs = currentPositionMs()
        saveProgressIfReady()
        cancelRetry()
        // 解码不了的资源重试三次也解不了：直接进失败态，让用户换清晰度
        if (failure !is PlaybackFailure.Unavailable) {
            setState(PlaybackState.Failed(index, failure, RetryState.None))
            return
        }
        // 断网时**不排期**：排了也只是在八秒内把三次预算烧光，用户会看到"已自动重试 3 次"却一次都没成
        if (!host.isOnline()) {
            setState(PlaybackState.Failed(index, failure, RetryState.WaitingNetwork))
            return
        }
        val delay = recovery.consume()
        if (delay == null) {
            setState(PlaybackState.Failed(index, failure, RetryState.Exhausted))
            return
        }
        setState(PlaybackState.Failed(index, failure, RetryState.Scheduled(clock() + delay)))
        pendingRetry = scheduler.after(delay) {
            pendingRetry = null
            if (state is PlaybackState.Failed) playEpisode(index, retryPositionMs, keepBudget = true)
        }
    }

    private fun cancelRetry() {
        pendingRetry?.cancel()
        pendingRetry = null
    }

    // ---------- 片尾 ----------

    private fun handleEnded() {
        saveProgress(completed = true)
        if (sleep.episodeEnded()) {
            stopForSleep()
            return
        }
        val data = detail ?: return
        val action = EpisodeFlow.after(
            index = index,
            total = data.episodes.size,
            autoNext = settings.current().autoNext,
            line = seasonLine(),
            currentSeriesId = data.series.id,
            currentSeason = data.series.season,
        )
        when (action) {
            is EndAction.Play -> playEpisode(action.index, 0L)
            is EndAction.NextSeason -> {
                // 换季等于换路由：先停住本页，再让导航层去开下一季
                setState(PlaybackState.Ended(index, lastOfSeason = true))
                host.keepScreenOn(false)
                host.openPlayer(action.series.id, 0, 0L)
            }
            is EndAction.Stop -> {
                setState(
                    PlaybackState.Ended(
                        index = index,
                        lastOfSeason = index >= data.episodes.lastIndex,
                        seriesEnded = action.seriesEnded,
                    ),
                )
                host.keepScreenOn(false)
            }
        }
    }

    /** 季线只认缓存：详情页收藏时已经落库，播放器不为了"也许有下一季"额外发请求。 */
    private fun seasonLine() = detail?.let {
        favorites.seasonLine(FavoriteLookup.favoriteKey(it.series, favorites))
    }

    // ---------- 进度 ----------

    /**
     * 当前位置。判据是 [streamLoaded] 而不是引擎"就绪"：
     * 缓冲中、出错暂停时位置同样有效，用 ready 会让"出错前看到 40 分钟"退回到片头重看。
     */
    private fun currentPositionMs(): Long =
        if (streamLoaded) engine.positionMs.coerceAtLeast(0) else startPositionMs

    private fun saveProgressIfReady() {
        if (!streamLoaded) return
        saveProgress(completed = false)
    }

    /**
     * 写进度。位置归一化与页面**同源**（`ResumePoint`）：<5s 归零、距片尾 3s 算看完。
     * 两处各写一套就会出现"详情页说差 2 秒没看完、播放器存成已看完"。
     */
    private fun saveProgress(completed: Boolean) {
        val data = detail ?: return
        val episode = data.episodes.getOrNull(index) ?: return
        val duration = engine.durationMs.coerceAtLeast(0)
        val checkpoint = ResumePoint.checkpoint(engine.positionMs.coerceAtLeast(0), duration, completed)
        host.save(
            Progress(
                seriesId = data.series.id,
                episodeVid = episode.vid,
                episodeIndex = index,
                positionMs = checkpoint.positionMs,
                durationMs = duration,
                completed = checkpoint.completed,
                updatedAt = clock(),
            ),
        )
    }

    // ---------- 引擎回调 ----------

    private val engineListener = object : EngineListener {
        override fun onReady() {
            setState(PlaybackState.Playing(index, pausedBy, buffering = false))
            host.keepScreenOn(pausedBy == null)
        }

        override fun onChanged() {
            val current = state
            if (current !is PlaybackState.Playing) return
            val next = current.copy(pausedBy = pausedBy, buffering = engine.buffering)
            if (next != current) setState(next) else refresh()
        }

        override fun onEnded() = handleEnded()

        override fun onFailed(failure: PlaybackFailure) = fail(failure)
    }

    private fun setState(next: PlaybackState) {
        if (state == next) return
        state = next
        onStateChanged?.invoke(next)
    }

    /** 状态没变但"读出来的数"变了（位置、定时剩余）：让页面重画一遍状态行。 */
    private fun refresh() {
        onStateChanged?.invoke(state)
    }

    private companion object {
        /** 快进到片尾前留半秒：停在整个文件末尾时解码器会报 EOF，看起来像播放失败。 */
        const val SEEK_TAIL_MS = 500L
    }
}
