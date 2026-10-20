package com.hongguotv.lite.feature.player

import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.Settings
import com.hongguotv.core.port.UpstreamError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放页文案：用户看到的每一句。 */
class PlayerTextTest {

    private fun status(state: PlaybackState, sleep: String? = null) = PlayerText.status(
        state = state,
        positionMs = 754_000,
        durationMs = 2_700_000,
        quality = 1080,
        speed = 1.25f,
        sleep = sleep,
    )

    @Test
    fun everyStateHasItsOwnSentence() {
        assertEquals("准备中 · 1080P · 1.25×", status(PlaybackState.Idle))
        assertEquals("正在获取播放地址… · 1080P · 1.25×", status(PlaybackState.Preparing(0)))
        assertEquals("正在播放 · 12:34 / 45:00 · 1080P · 1.25×", status(PlaybackState.Playing(0, null, buffering = false)))
        assertEquals("正在缓冲… · 12:34 / 45:00 · 1080P · 1.25×", status(PlaybackState.Playing(0, null, buffering = true)))
        assertEquals("已暂停 · 12:34 / 45:00 · 1080P · 1.25×", status(PlaybackState.Playing(0, PauseReason.USER, false)))
        assertEquals("定时停止 · 12:34 / 45:00 · 1080P · 1.25×", status(PlaybackState.Playing(0, PauseReason.SLEEP, false)))
        assertEquals("本集已结束 · 12:34 / 45:00 · 1080P · 1.25×", status(PlaybackState.Ended(0, lastOfSeason = false)))
        assertEquals("播放出错了 · 1080P · 1.25×", status(PlaybackState.Failed(0, PlaybackFailure.Decode(), RetryState.None)))
    }

    @Test
    fun theSleepCountdownIsAppendedWhenSet() {
        val state = PlaybackState.Playing(0, null, buffering = false)
        assertEquals("正在播放 · 12:34 / 45:00 · 1080P · 1.25× · 定时 15 分钟后", status(state, sleep = "15 分钟后"))
    }

    @Test
    fun theSidebarTitleCountsEpisodesFromOne() {
        assertEquals("某剧 · 第 9 集", PlayerText.title("某剧", 8))
    }

    @Test
    fun theEpisodeRangeShowsBothTheGroupAndTheTotal() {
        assertEquals("第 1—3 集 / 共 3 集", PlayerText.range(0..2, 3))
        assertEquals("第 201—300 集 / 共 300 集", PlayerText.range(200..299, 300))
        assertEquals("", PlayerText.range(IntRange.EMPTY, 0))
    }

    @Test
    fun theSettingsRowsShowTheCurrentValues() {
        val settings = Settings(quality = 720, speed = 1.5f, frame = FrameMode.ZOOM)
        assertEquals(
            listOf("倍速 · 1.5×", "清晰度 · 720P", "画面 · 等比铺满", "定时停止 · 关闭", "从头播放"),
            PlayerText.settingsRows(settings, sleep = "关闭"),
        )
    }

    @Test
    fun theJumpHintsExplainWhatToTypeAndRejectOutOfRange() {
        assertEquals("数字键直接输入集数", PlayerText.jumpHint(""))
        assertEquals("定位到第 12 集", PlayerText.jumpHint("12"))
        assertEquals("请输入 1—30 之间的集数", PlayerText.jumpRejected(30))
    }

    @Test
    fun failuresAreSplitIntoDecodingAndEverythingElse() {
        assertEquals("电视无法解码当前视频，可尝试更低清晰度。", PlayerText.failure(PlaybackFailure.Decode("codec")))
        // 取流失败沿用全局的失败文案（按错误类型说，不匹配中文字符串）
        assertEquals(
            "请求被限制（403），请稍后再试。",
            PlayerText.failure(PlaybackFailure.Unavailable(UpstreamError.RiskControl(403))),
        )
        assertEquals("播放失败，可重试或切换清晰度。", PlayerText.failure(PlaybackFailure.Unavailable(null)))
    }

    @Test
    fun theNoticeOnlyAppearsWhenTheUserHasToDecideSomething() {
        assertNull(PlayerText.notice(PlaybackState.Playing(0, null, buffering = false)))
        assertNull(PlayerText.notice(PlaybackState.Preparing(0)))
        assertEquals(PlayerText.SLEEP_STOPPED, PlayerText.notice(PlaybackState.Playing(0, PauseReason.SLEEP, false)))
        assertEquals(PlayerText.EPISODE_ENDED, PlayerText.notice(PlaybackState.Ended(0, lastOfSeason = false)))
        assertEquals(PlayerText.SEASON_ENDED, PlayerText.notice(PlaybackState.Ended(0, lastOfSeason = true)))
        assertEquals(
            "播放出错了，正在自动重试…",
            PlayerText.notice(PlaybackState.Failed(0, PlaybackFailure.Decode(), RetryState.Scheduled(1L))),
        )
        assertEquals(
            "网络已断开，联网后会自动重试",
            PlayerText.notice(PlaybackState.Failed(0, PlaybackFailure.Unavailable(null), RetryState.WaitingNetwork)),
        )
        // 预算用完与"解码失败"说的不是同一句话：前者要用户换清晰度或手动重试
        assertEquals(
            PlayerText.RETRY_EXHAUSTED,
            PlayerText.notice(PlaybackState.Failed(0, PlaybackFailure.Unavailable(null), RetryState.Exhausted)),
        )
        assertEquals(
            "电视无法解码当前视频，可尝试更低清晰度。",
            PlayerText.notice(PlaybackState.Failed(0, PlaybackFailure.Decode(), RetryState.None)),
        )
    }

    @Test
    fun theFailureHintExplainsWhyChangingTheQualityHelps() {
        assertTrue(PlayerText.failureHint(PlaybackFailure.Decode()).orEmpty().contains("低清晰度"))
        assertTrue(PlayerText.failureHint(PlaybackFailure.Unavailable(null)).orEmpty().contains("重试"))
    }
}
