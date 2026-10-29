package com.hongguotv.lite.uikit.text

import com.hongguotv.core.model.FavoriteLimits
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.port.LocalError
import com.hongguotv.core.port.UpstreamError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 全应用共用的文案派生：用户看到什么，就在这些断言里。 */
class TvTextTest {

    @Test
    fun seasonUsesChineseNumbersLikeTheOfficialTitles() {
        assertEquals("第七季", TvText.season(7))
        assertEquals("第十季", TvText.season(10))
        assertEquals("第十三季", TvText.season(13))
        assertEquals("第二十季", TvText.season(20))
        assertEquals("第二十一季", TvText.season(21))
        assertEquals("第四十季", TvText.season(40))
        // 单季剧不写季号
        assertEquals("", TvText.season(0))
        // 超过 99 季直接用阿拉伯数字
        assertEquals("第100季", TvText.season(100))
    }

    @Test
    fun clockIsPaddedAndNeverNegative() {
        assertEquals("00:00", TvText.clock(0))
        assertEquals("00:09", TvText.clock(9_400))
        assertEquals("12:34", TvText.clock(754_000))
        assertEquals("00:00", TvText.clock(-5_000))
        assertEquals("120:00", TvText.clock(7_200_000))
    }

    @Test
    fun progressCountsEpisodesFromOne() {
        assertEquals("第 9 集 · 12:34", TvText.progress(index = 8, positionMs = 754_000))
        assertEquals("第 1 集 · 00:00", TvText.progress(index = 0, positionMs = 0))
    }

    /** B 方案：卡片状态条写「第九季 第20集」——季与集之间一个空格，不带时间。 */
    @Test
    fun cardProgressSeparatesSeasonAndEpisodeWithASpace() {
        assertEquals("第九季 第20集", TvText.cardProgress(9, index = 19))
        assertEquals("第8集", TvText.cardProgress(0, index = 7))
    }

    @Test
    fun everyFailureTypeHasItsOwnSentence() {
        assertEquals("连不上内容网站，请检查网络后重试。", TvText.error(UpstreamError.Network(IOException("x"))))
        assertEquals("服务暂时不可用（503），请稍后重试。", TvText.error(UpstreamError.HttpStatus(503)))
        assertEquals("请求被限制（403），请稍后再试。", TvText.error(UpstreamError.RiskControl(403)))
        assertEquals("内容结构发生变化，暂时无法显示。", TvText.error(UpstreamError.Shape("data.video_data")))
        assertEquals("内容解析失败，请稍后重试。", TvText.error(UpstreamError.Decrypt("key")))
        assertEquals("内容加载失败，请稍后重试。", TvText.error(IllegalStateException("boom")))
        assertEquals("读取本机数据失败，请重试。", TvText.error(LocalError.Storage(IOException("disk"))))
    }

    /** 风控空体必须单独说"被限制"，不能和"结构变了/没有内容"混成一句。 */
    @Test
    fun riskControlEmptyBodyIsItsOwnSentence() {
        assertEquals("请求被临时限制，请稍后重试。", TvText.error(UpstreamError.EmptyBody()))
        assertNotEquals(TvText.error(UpstreamError.EmptyBody()), TvText.error(UpstreamError.Shape("data")))
    }

    /** 播放档位的写法：`1×` 不能写成 `1.0×`，清晰度带 P，画面模式三档各有名字。 */
    @Test
    fun playbackOptionsReadLikeTheOldApp() {
        assertEquals("1×", TvText.speed(1f))
        assertEquals("1.25×", TvText.speed(1.25f))
        assertEquals("2×", TvText.speed(2f))
        assertEquals("1080P", TvText.quality(1080))
        assertEquals("完整画面", TvText.frame(FrameMode.FIT))
        assertEquals("等比铺满", TvText.frame(FrameMode.ZOOM))
        assertEquals("拉伸铺满", TvText.frame(FrameMode.FILL))
    }

    /** 定时停止的三种说法：分钟数向上取整（还剩 30 秒时说"1 分钟后"）。 */
    @Test
    fun sleepTimerRoundsUpAndFallsBackToOff() {
        assertEquals("15 分钟后", TvText.sleepTimer(remainingMs = 15 * 60_000L, episodesLeft = 0))
        assertEquals("1 分钟后", TvText.sleepTimer(remainingMs = 30_000L, episodesLeft = 0))
        assertEquals("再播完 3 集", TvText.sleepTimer(remainingMs = null, episodesLeft = 3))
        assertEquals("关闭", TvText.sleepTimer(remainingMs = null, episodesLeft = 0))
    }

    /**
     * 收藏满 100 的提示：**必须说清会替换掉哪一部**——写入口按 `addedAt` 淘汰最旧的一条，
     * 不给一句话，用户就是在不知情下丢收藏。
     */
    @Test
    fun theCapacityNoticeNamesTheOneThatWillBeReplaced() {
        assertEquals("收藏已满 100 部，将替换《测试书》", TvText.favoriteCapacityNotice("测试书"))
        // 上限只读 core 的那一个常量：改数不用改文案
        assertEquals(100, FavoriteLimits.CAPACITY)
    }

    /** Android 12+ 的文字 Toast 最多两行：剧名截到 12 字 + 「…」，整句不超过 30 字。 */
    @Test
    fun theCapacityNoticeNeverGrowsPastTwoLines() {
        val long = "这是一个特别特别长的剧名足足有二十多个字那么长"
        val text = TvText.favoriteCapacityNotice(long)
        assertEquals("收藏已满 100 部，将替换《${long.take(12)}…》", text)
        assertTrue("$text 超过 30 字", text.length <= 30)
    }
}
