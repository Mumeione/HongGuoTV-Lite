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

    @Test
    fun theSidebarTitleCountsEpisodesFromOne() {
        assertEquals("某剧 · 第 9 集", PlayerText.title("某剧", 8))
    }

    /** B 方案：抽屉的状态行只报失败，不再重复右侧设置里能看到的东西。 */
    @Test
    fun theDrawerStatusOnlyReportsFailures() {
        assertEquals("", PlayerText.drawerStatus(PlaybackState.Idle))
        assertEquals("", PlayerText.drawerStatus(PlaybackState.Preparing(0)))
        assertEquals("", PlayerText.drawerStatus(PlaybackState.Playing(0, null, buffering = false)))
        assertEquals("", PlayerText.drawerStatus(PlaybackState.Ended(0, lastOfSeason = false)))
        assertEquals(
            "播放出错了，正在自动重试…",
            PlayerText.drawerStatus(PlaybackState.Failed(0, PlaybackFailure.Decode(), RetryState.Scheduled(1L))),
        )
        assertEquals(
            "网络已断开，联网后会自动重试",
            PlayerText.drawerStatus(PlaybackState.Failed(0, PlaybackFailure.Unavailable(null), RetryState.WaitingNetwork)),
        )
    }

    /** 画面顶部的操作提示是（键, 说明）成对的：键加粗白字，说明弱一档——顺序按 mockup 画面6。 */
    @Test
    fun theOperationHintIsStructuredKeyLabelPairs() {
        assertEquals(
            listOf(
                "左右" to "快退/快进",
                "长按→" to "2倍快进",
                "上下" to "切集",
                "确定" to "暂停/继续",
                "菜单" to "选集·播放设置",
            ),
            PlayerText.operationHints(),
        )
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
            PlayerText.SERIES_ENDED,
            PlayerText.notice(PlaybackState.Ended(0, lastOfSeason = true, seriesEnded = true)),
        )
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
