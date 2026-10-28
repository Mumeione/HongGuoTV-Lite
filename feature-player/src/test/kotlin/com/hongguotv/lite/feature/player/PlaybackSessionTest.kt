package com.hongguotv.lite.feature.player

import com.hongguotv.core.model.Detail
import com.hongguotv.core.model.Episode
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.Settings
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.MediaStream
import com.hongguotv.core.port.SettingsPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 播放会话：状态机的外部行为。
 *
 * 每条用例只谈"发起了哪些宿主调用、状态变成什么"——不碰视图、不碰 ExoPlayer、不碰真网络，
 * 所以片尾连播、断网重试、定时停止这些最难在真机上复现的情形都能直接断言。
 */
class PlaybackSessionTest {

    // ---------- 假件 ----------

    private class FakeEngine : PlaybackEngine {
        data class Load(val positionMs: Long, val playWhenReady: Boolean, val seamless: Boolean = false)

        private var listener: EngineListener? = null
        val loads = mutableListOf<Load>()
        val seeks = mutableListOf<Long>()
        val speeds = mutableListOf<Float>()
        val frames = mutableListOf<FrameMode>()
        var plays = 0
        var pauses = 0
        var stops = 0
        var releases = 0

        override var positionMs = 0L
        override var durationMs = 0L
        override var bufferedPositionMs = 0L
        override var buffering = false
        override var ready = false
        override var ended = false

        /** 测试自己的观察点：引擎实际是"在播"还是"停着"。不在 [PlaybackEngine] 契约里。 */
        var playing = false

        override fun setListener(listener: EngineListener?) {
            this.listener = listener
        }

        override fun load(stream: MediaStream, positionMs: Long, playWhenReady: Boolean, seamless: Boolean) {
            loads += Load(positionMs, playWhenReady, seamless)
            this.positionMs = positionMs
            ready = false
            ended = false
            playing = playWhenReady
        }

        override fun play() {
            plays++
            playing = true
        }

        override fun pause() {
            pauses++
            playing = false
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            this.positionMs = positionMs
        }

        override fun setSpeed(speed: Float) {
            speeds += speed
        }

        override fun setFrame(mode: FrameMode) {
            frames += mode
        }

        override fun stop() {
            stops++
            ready = false
            ended = false
            playing = false
            positionMs = 0
        }

        override fun release() {
            releases++
        }

        /** 解码器就绪：引擎报"可以播了"。 */
        fun becomesReady() {
            ready = true
            ended = false
            listener?.onReady()
        }

        fun emitEnded() {
            ended = true
            listener?.onEnded()
        }

        fun emitFailure(failure: PlaybackFailure) = listener?.onFailed(failure)
    }

    private object FakeStream : MediaStream {
        override val length: Long = 1_000_000L
        override fun read(offset: Long, size: Int): ByteArray = ByteArray(size)
        override fun close() = Unit
    }

    private class FakeSettings(var value: Settings = Settings()) : SettingsPort {
        override fun current(): Settings = value
        override suspend fun update(transform: (Settings) -> Settings) {
            value = transform(value)
        }
    }

    private class FakeFavorites(
        private val line: SeasonLine?,
        private val lineKey: String? = null,
        private val favorite: FavoriteEntry? = null,
    ) : FavoritesPort {
        /** 播放器读季线时实际用的键：用于断言它跟收藏行键一致，而不是标题键。 */
        val requestedKeys = mutableListOf<String>()

        override fun all(): List<FavoriteEntry> = listOfNotNull(favorite)
        override fun isFavorite(seriesKey: String): Boolean = favorite?.seriesKey == seriesKey
        override fun entry(seriesKey: String): FavoriteEntry? = favorite?.takeIf { it.seriesKey == seriesKey }
        override suspend fun toggle(series: Series): Boolean = false
        override suspend fun setCurrentSeason(seriesKey: String, series: Series) = Unit
        override fun seasonLine(seriesKey: String): SeasonLine? {
            requestedKeys += seriesKey
            return if (lineKey == null || seriesKey == lineKey) line else null
        }
        override fun rememberSeasonLine(seriesKey: String, line: SeasonLine) = Unit
        override fun acknowledge(seriesId: String, totalEpisodes: Int, at: Long) = Unit
    }

    private class RecordingHost : PlayerHost {
        data class StreamRequest(val vid: String, val quality: Int)

        val streams = mutableListOf<StreamRequest>()
        val saves = mutableListOf<Progress>()
        val notices = mutableListOf<String>()
        var settings = Settings()
        var openPlayer: Triple<String, Int, Long>? = null
        var screenOn: Boolean? = null
        var online = true

        override fun requestStream(vid: String, quality: Int) {
            streams += StreamRequest(vid, quality)
        }

        override fun save(progress: Progress) {
            saves += progress
        }

        override fun applySettings(transform: (Settings) -> Settings) {
            settings = transform(settings)
        }

        override fun openPlayer(seriesId: String, episodeIndex: Int, positionMs: Long) {
            openPlayer = Triple(seriesId, episodeIndex, positionMs)
        }

        override fun keepScreenOn(on: Boolean) {
            screenOn = on
        }

        override fun notify(text: String) {
            notices += text
        }

        override fun isOnline(): Boolean = online
    }

    private class FakeScheduler : Scheduler {
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

        /** 曾经排过的所有延时（含已经执行的）：用来断言退避序列。 */
        val allDelays = mutableListOf<Long>()

        val pending: Int get() = tasks.size

        override fun after(delayMs: Long, action: () -> Unit): ScheduledTask {
            val entry = delayMs to action
            tasks += entry
            allDelays += delayMs
            return ScheduledTask { tasks.remove(entry) }
        }

        fun fire() {
            val entry = tasks.removeAt(0)
            entry.second()
        }
    }

    private class Fixture(
        episodes: Int = 3,
        autoNext: Boolean = true,
        speed: Float = 1f,
        frame: FrameMode = FrameMode.FIT,
        line: SeasonLine? = null,
        lineKey: String? = null,
        favorite: FavoriteEntry? = null,
        title: String = "某剧第一季",
    ) {
        val engine = FakeEngine()
        val settingsPort = FakeSettings(Settings(autoNext = autoNext, speed = speed, frame = frame))
        val host = RecordingHost()
        val scheduler = FakeScheduler()
        val favorites = FakeFavorites(line, lineKey, favorite)
        var now = 1_000_000L
        val session = PlaybackSession(engine, settingsPort, favorites, host, scheduler) { now }
        val detail = Detail(
            series = Series(id = "s1", title = title, season = 1),
            episodes = List(episodes) { Episode(vid = "v$it", index = it) },
        )

        fun start(index: Int = 0, position: Long = 0) = apply {
            session.start(detail, index, position)
        }

        /** 取流成功 + 解码器就绪：这是"用户真的看到了画面"的那一刻。 */
        fun ready() = apply {
            session.onStreamReady(FakeStream)
            engine.durationMs = 300_000L
            engine.becomesReady()
        }
    }

    private fun seasonLine() = SeasonLine(
        listOf(
            Series(id = "s1", title = "某剧第一季", season = 1),
            Series(id = "s2", title = "某剧第二季", season = 2),
        ),
    )

    // ---------- 起播 ----------

    @Test
    fun startingRequestsTheStreamOfTheRequestedEpisode() {
        val f = Fixture().start(index = 1, position = 5_000)
        assertEquals(PlaybackState.Preparing(1), f.session.state)
        assertEquals(listOf(RecordingHost.StreamRequest("v1", 1080)), f.host.streams)
    }

    @Test
    fun aReadyStreamPlaysFromTheRequestedPosition() {
        val f = Fixture().start(index = 1, position = 5_000).ready()
        assertEquals(FakeEngine.Load(5_000, playWhenReady = true), f.engine.loads.single())
        assertEquals(PlaybackState.Playing(1, null, buffering = false), f.session.state)
        assertTrue(f.host.screenOn == true)
    }

    @Test
    fun startingAppliesTheSavedFrameMode() {
        val f = Fixture(frame = FrameMode.ZOOM).start()
        // 画面模式是"进来就该是上次设的样"：只在切换时应用会让每次重进都回落默认
        assertEquals(FrameMode.ZOOM, f.engine.frames.single())
        assertEquals(FrameMode.ZOOM, f.session.frame)
    }

    // ---------- 命令 ----------

    @Test
    fun pausingSavesThePositionAndPlayingResumes() {
        val f = Fixture().start().ready()
        f.engine.positionMs = 12_000
        f.session.pause()
        assertEquals(PlaybackState.Playing(0, PauseReason.USER, buffering = false), f.session.state)
        assertEquals(12_000L, f.host.saves.single().positionMs)
        f.session.play()
        assertEquals(PlaybackState.Playing(0, null, buffering = false), f.session.state)
        assertEquals(1, f.engine.plays)
    }

    @Test
    fun aShortWatchIsNormalizedToZeroOnDisk() {
        val f = Fixture().start().ready()
        f.engine.positionMs = 2_000
        f.session.pause()
        // "续播 2 秒"没有意义：与详情页共用 ResumePoint 的口径
        assertEquals(0L, f.host.saves.single().positionMs)
    }

    @Test
    fun aWatchRightBeforeTheEndIsSavedAsCompleted() {
        val f = Fixture().start().ready()
        f.engine.positionMs = 299_000
        f.session.pause()
        assertTrue(f.host.saves.single().completed)
    }

    @Test
    fun skippingIsClampedAtBothEnds() {
        val f = Fixture().start(0)
        f.session.skip(-1)
        assertEquals(1, f.host.streams.size)
        f.session.skip(1)
        assertEquals("v1", f.host.streams.last().vid)
        // 到了最后一集再往下就没了，不回到第一集
        f.session.skip(5)
        assertEquals(2, f.host.streams.size)
    }

    @Test
    fun seekingIsClampedInsideTheDuration() {
        val f = Fixture().start().ready()
        f.engine.positionMs = 100_000
        f.session.seekBy(PlayerKeys.SEEK_STEP_MS)
        assertEquals(110_000L, f.engine.seeks.last())
        f.session.seekBy(1_000_000)
        assertEquals(299_500L, f.engine.seeks.last())
        f.session.seekBy(-1_000_000)
        assertEquals(0L, f.engine.seeks.last())
    }

    @Test
    fun seekingTellsTheUserWhereItLanded() {
        val f = Fixture().start().ready()
        f.engine.positionMs = 100_000
        f.session.seekBy(PlayerKeys.SEEK_STEP_MS)
        assertEquals("01:50 / 05:00", f.host.notices.last())
    }

    @Test
    fun selectingTheCurrentEpisodeOnlyClosesTheMenu() {
        val f = Fixture().start().ready()
        f.session.openMenu()
        f.session.selectEpisode(0)
        assertFalse(f.session.isMenuOpen)
        assertEquals(1, f.host.streams.size)
    }

    // ---------- 片尾 ----------

    @Test
    fun theEndOfAnEpisodeContinuesToTheNextOne() {
        val f = Fixture(episodes = 3).start(index = 1).ready()
        f.engine.positionMs = 300_000
        f.engine.emitEnded()
        assertEquals("v2", f.host.streams.last().vid)
        assertTrue(f.host.saves.last().completed)
    }

    @Test
    fun theEndOfTheLastEpisodeOpensTheNextSeason() {
        val f = Fixture(episodes = 2, line = seasonLine()).start(index = 1).ready()
        f.engine.positionMs = 300_000
        f.engine.emitEnded()
        assertEquals(Triple("s2", 0, 0L), f.host.openPlayer)
        assertEquals(PlaybackState.Ended(1, lastOfSeason = true), f.session.state)
    }

    /**
     * 分宗篇这类标题不带季号：标题键与收藏行键不同。
     * 播放器读季线必须用收藏行键，否则会各落一份缓存、跨季链也可能读空。
     */
    @Test
    fun seasonLineIsLookedUpByTheFavoriteKeyNotTheTitleKey() {
        val line = SeasonLine(
            listOf(
                Series(id = "s1", title = "某剧分宗篇", season = 1),
                Series(id = "s2", title = "某剧第二季", season = 2),
            ),
        )
        val favorite = FavoriteEntry(
            series = Series(id = "s1", title = "某剧", season = 1),
            addedAt = 0L,
            status = FavoriteStatus.UNCHECKED,
            seriesKey = "某剧",
        )
        val f = Fixture(
            episodes = 2,
            line = line,
            lineKey = "某剧",
            favorite = favorite,
            title = "某剧分宗篇",
        ).start(index = 1).ready()
        f.engine.positionMs = 300_000
        f.engine.emitEnded()
        assertEquals(setOf("某剧"), f.favorites.requestedKeys.toSet())
        assertEquals(Triple("s2", 0, 0L), f.host.openPlayer)
    }

    @Test
    fun theEndOfTheLastEpisodeWithoutANextSeasonStops() {
        // 带季号的剧拿不到季线：只能停，**不能**说"全剧播完"（也许还有下一季）
        val f = Fixture(episodes = 2).start(index = 1).ready()
        f.engine.positionMs = 300_000
        f.engine.emitEnded()
        assertNull(f.host.openPlayer)
        assertEquals(PlaybackState.Ended(1, lastOfSeason = true, seriesEnded = false), f.session.state)
        assertEquals(false, f.host.screenOn)
    }

    @Test
    fun theEndOfTheLastEpisodeOfTheLastReleasedSeasonSaysTheSeriesIsOver() {
        // 季线只有本季（最新一季）：最后一集播完 = 没有更新的了，提示要区别于"本季已结束"
        val f = Fixture(episodes = 2, line = SeasonLine(listOf(Series(id = "s1", title = "某剧第一季", season = 1))))
            .start(index = 1).ready()
        f.engine.positionMs = 300_000
        f.engine.emitEnded()
        assertEquals(PlaybackState.Ended(1, lastOfSeason = true, seriesEnded = true), f.session.state)
    }

    @Test
    fun autoNextOffStopsWhereItEnded() {
        val f = Fixture(episodes = 3, autoNext = false).start(index = 0).ready()
        f.engine.positionMs = 300_000
        f.engine.emitEnded()
        assertEquals(PlaybackState.Ended(0, lastOfSeason = false), f.session.state)
        assertEquals(1, f.host.streams.size)
    }

    @Test
    fun playingFromTheEndedStateRewindsFirst() {
        val f = Fixture(episodes = 1, autoNext = false).start(0).ready()
        f.engine.positionMs = 300_000
        f.engine.emitEnded()
        f.engine.seeks.clear()
        f.session.play()
        // 停在片尾时按播放要回到片头：media3 的 STATE_ENDED 下 play() 不会自己回头
        assertEquals(listOf(0L), f.engine.seeks)
        assertEquals(PlaybackState.Playing(0, null, buffering = false), f.session.state)
    }

    @Test
    fun theSleepTimerWinsOverAutoNext() {
        val f = Fixture(episodes = 3).start(index = 0).ready()
        f.session.sleepAfterEpisodes(1)
        f.engine.positionMs = 300_000
        f.engine.emitEnded()
        assertEquals(PlaybackState.Playing(0, PauseReason.SLEEP, buffering = false), f.session.state)
        assertEquals(1, f.host.streams.size)
        assertEquals(0, f.session.sleep.episodesLeft)
    }

    @Test
    fun theSleepTimerStopsWithoutWaitingForTheEndOfTheEpisode() {
        val f = Fixture().start().ready()
        f.session.sleepFor(15)
        f.now += 15 * 60_000L
        f.session.onTick()
        assertEquals(PlaybackState.Playing(0, PauseReason.SLEEP, buffering = false), f.session.state)
    }

    // ---------- 失败与重试 ----------

    @Test
    fun aStreamFailureSchedulesAnAutomaticRetry() {
        val f = Fixture().start()
        f.session.onStreamFailed(IOException("boom"))
        val failed = f.session.state as PlaybackState.Failed
        assertEquals(RetryState.Scheduled(1_001_000L), failed.retry)
        assertTrue(failed.failure is PlaybackFailure.Unavailable)
        assertEquals(listOf(1_000L), f.scheduler.allDelays)
        f.scheduler.fire()
        assertEquals(2, f.host.streams.size)
        assertEquals(PlaybackState.Preparing(0), f.session.state)
    }

    @Test
    fun onlyThreeAutomaticRetriesAreBudgeted() {
        val f = Fixture().start()
        f.session.onStreamFailed(IOException("boom"))
        f.scheduler.fire()
        f.session.onStreamFailed(IOException("boom"))
        f.scheduler.fire()
        f.session.onStreamFailed(IOException("boom"))
        f.scheduler.fire()
        assertEquals(listOf(1_000L, 2_500L, 5_000L), f.scheduler.allDelays)

        f.session.onStreamFailed(IOException("boom"))
        val failed = f.session.state as PlaybackState.Failed
        assertEquals(RetryState.Exhausted, failed.retry)
        assertEquals(0, f.scheduler.pending)
        assertEquals(listOf(1_000L, 2_500L, 5_000L), f.scheduler.allDelays)
    }

    @Test
    fun aDecodingFailureIsNeverRetriedAutomatically() {
        val f = Fixture().start().ready()
        f.engine.emitFailure(PlaybackFailure.Decode("codec"))
        val failed = f.session.state as PlaybackState.Failed
        assertEquals(RetryState.None, failed.retry)
        assertEquals(0, f.scheduler.pending)
    }

    @Test
    fun aManualRetryRestoresTheBudget() {
        val f = Fixture().start()
        repeat(4) {
            f.session.onStreamFailed(IOException("boom"))
            if (f.scheduler.pending > 0) f.scheduler.fire()
        }
        assertEquals(4, f.host.streams.size)
        f.session.retry()
        assertEquals(5, f.host.streams.size)
        f.session.onStreamFailed(IOException("boom"))
        assertEquals(1, f.scheduler.pending)
    }

    @Test
    fun theUserCanStopTheAutomaticRetry() {
        val f = Fixture().start()
        f.session.onStreamFailed(IOException("boom"))
        f.session.stopAutoRetry()
        val failed = f.session.state as PlaybackState.Failed
        assertEquals(RetryState.None, failed.retry)
        assertEquals(0, f.scheduler.pending)
    }

    @Test
    fun aFailureWhileOfflineWaitsForTheNetworkInsteadOfBurningTheBudget() {
        val f = Fixture().start()
        f.host.online = false
        f.session.onStreamFailed(IOException("offline"))
        val failed = f.session.state as PlaybackState.Failed
        assertEquals(RetryState.WaitingNetwork, failed.retry)
        assertEquals(0, f.scheduler.pending)

        // 网回来了：下一次心跳接着试，预算一次没动
        f.host.online = true
        f.session.onTick()
        assertEquals(2, f.host.streams.size)
        assertEquals(PlaybackState.Preparing(0), f.session.state)
    }

    @Test
    fun openingTheMenuBeforeTheStreamArrivesKeepsTheSoundOff() {
        val f = Fixture().start()
        f.session.openMenu()
        f.session.onStreamReady(FakeStream)
        // 侧栏开着却听见片头在放，是旧实现最容易漏的一处
        assertFalse(f.engine.loads.single().playWhenReady)
        f.ready()
        assertEquals(PlaybackState.Playing(0, PauseReason.MENU, buffering = false), f.session.state)
    }

    @Test
    fun togglingFromAFailedStateRetries() {
        val f = Fixture().start()
        f.session.onStreamFailed(IOException("boom"))
        f.session.toggle()
        assertEquals(2, f.host.streams.size)
    }

    // ---------- 侧栏 ----------

    @Test
    fun openingTheMenuPausesAndClosingItResumes() {
        val f = Fixture().start().ready()
        f.session.openMenu()
        assertEquals(PlaybackState.Playing(0, PauseReason.MENU, buffering = false), f.session.state)
        f.session.closeMenu()
        assertEquals(PlaybackState.Playing(0, null, buffering = false), f.session.state)
        assertEquals(1, f.engine.plays)
    }

    @Test
    fun aManualPauseIsNotUndoneByClosingTheMenu() {
        val f = Fixture().start().ready()
        f.session.pause(PauseReason.USER)
        f.session.openMenu()
        f.session.closeMenu()
        assertEquals(PlaybackState.Playing(0, PauseReason.USER, buffering = false), f.session.state)
        assertEquals(0, f.engine.plays)
    }

    // ---------- 倍速 / 清晰度 / 画面 ----------

    @Test
    fun fastForwardIsTemporaryAndNeverPersisted() {
        val f = Fixture().start().ready()
        f.session.fastForward(true)
        assertEquals(2f, f.engine.speeds.last())
        assertEquals(1f, f.host.settings.speed)
        f.session.fastForward(false)
        assertEquals(1f, f.engine.speeds.last())
        assertEquals(1, f.host.notices.count { it == PlayerText.FAST_FORWARD })
    }

    @Test
    fun fastForwardStaysQuietWhenTheUserAlreadyWatchesAtDoubleSpeed() {
        val f = Fixture(speed = 2f).start().ready()
        f.session.fastForward(true)
        assertEquals(0, f.host.notices.count { it == PlayerText.FAST_FORWARD })
    }

    @Test
    fun theEffectiveSpeedIncludesTheTemporaryFastForward() {
        // 片尾预取按墙钟算窗口：按住右键快进时，剩余"墙钟时间"比设定倍速算出来的更短
        val f = Fixture(speed = 1f).start().ready()
        assertEquals(1f, f.session.effectiveSpeed)
        f.session.fastForward(true)
        assertEquals(PlayerKeys.FAST_FORWARD_SPEED, f.session.effectiveSpeed)
        f.session.fastForward(false)
        assertEquals(1f, f.session.effectiveSpeed)
    }

    @Test
    fun aPrefetchedStreamIsLoadedWithoutTheBufferingSpinner() {
        val f = Fixture().start().ready()
        f.session.onStreamReady(FakeStream, seamless = true)
        assertTrue(f.engine.loads.last().seamless)
    }

    @Test
    fun changingTheQualityReloadsFromTheCurrentPosition() {
        val f = Fixture().start().ready()
        f.engine.positionMs = 30_000
        f.session.setQuality(720)
        assertEquals(RecordingHost.StreamRequest("v0", 720), f.host.streams.last())
        assertEquals(720, f.host.settings.quality)
        f.session.onStreamReady(FakeStream)
        assertEquals(30_000L, f.engine.loads.last().positionMs)
    }

    /**
     * 换清晰度是新的观看意图：不继承上一档已经烧完的重试预算。
     *
     * 继承的话，烧完三次后换到 720P，新流首败就直接判 `Exhausted`——用户看到
     * 「已自动重试 3 次」，可新流一次都没重试过。
     */
    @Test
    fun changingTheQualityStartsAFreshRetryBudget() {
        val f = Fixture().start()
        repeat(3) {
            f.session.onStreamFailed(IOException("boom"))
            f.scheduler.fire()
        }
        f.session.onStreamFailed(IOException("boom"))
        assertEquals(RetryState.Exhausted, (f.session.state as PlaybackState.Failed).retry)

        f.session.setQuality(720)
        assertEquals(PlaybackState.Preparing(0), f.session.state)
        f.session.onStreamFailed(IOException("boom"))
        assertTrue((f.session.state as PlaybackState.Failed).retry is RetryState.Scheduled)
    }

    @Test
    fun changingTheFrameModeAppliesImmediatelyAndIsPersisted() {
        val f = Fixture().start().ready()
        f.session.setFrame(FrameMode.ZOOM)
        assertEquals(FrameMode.ZOOM, f.engine.frames.last())
        assertEquals(FrameMode.ZOOM, f.host.settings.frame)
    }

    @Test
    fun changingTheSpeedIsPersistedAndApplied() {
        val f = Fixture().start().ready()
        f.session.setSpeed(1.25f)
        assertEquals(1.25f, f.engine.speeds.last())
        assertEquals(1.25f, f.host.settings.speed)
    }

    // ---------- 生命周期 ----------

    @Test
    fun goingToTheBackgroundPausesAndSaves() {
        val f = Fixture().start().ready()
        f.engine.positionMs = 42_000
        f.session.onLifecyclePause()
        assertEquals(PlaybackState.Playing(0, PauseReason.LIFECYCLE, buffering = false), f.session.state)
        assertEquals(42_000L, f.host.saves.last().positionMs)
    }

    @Test
    fun goingToTheBackgroundBeforeTheStreamArrivesKeepsTheSoundOff() {
        // S7 才有人调 onLifecyclePause：取流还没回来就按 HOME，流回来时不能在后台带声音开播
        val f = Fixture().start()
        f.session.onLifecyclePause()
        f.session.onStreamReady(FakeStream)
        assertFalse(f.engine.loads.single().playWhenReady)
        f.ready()
        assertEquals(PlaybackState.Playing(0, PauseReason.LIFECYCLE, buffering = false), f.session.state)
        // 回到前台不自动续播：要用户自己按一下（旧行为同口径）
        assertEquals(0, f.engine.plays)
    }

    @Test
    fun goingToTheBackgroundWhileFailedDoesNotPoisonTheNextRetry() {
        // 出错时按 HOME：暂停原因必须保持"无"。若记成 LIFECYCLE，回前台按「重试」会装成暂停态，
        // 提示卡消失、画面不动——用户按了确认却什么都没发生
        val f = Fixture().start()
        f.session.onStreamFailed(IOException("boom"))
        f.session.onLifecyclePause()
        f.session.retry()
        f.session.onStreamReady(FakeStream)
        assertTrue(f.engine.loads.last().playWhenReady)
    }

    @Test
    fun goingToTheBackgroundCancelsTheSleepTimer() {
        // 旧行为：退到后台就不算这一次观看的定时了。不取消的话，回来一按播放就被过期的定时立刻停住
        val f = Fixture().start().ready()
        f.session.sleepFor(15)
        f.session.onLifecyclePause()
        assertNull(f.session.sleep.remainingMs)
    }

    @Test
    fun releasingSavesAndTearsTheEngineDown() {
        val f = Fixture().start().ready()
        f.engine.positionMs = 8_000
        f.session.release()
        assertEquals(8_000L, f.host.saves.last().positionMs)
        assertEquals(1, f.engine.releases)
        assertEquals(PlaybackState.Idle, f.session.state)
    }
}
