package com.hongguotv.lite.feature.catalog

import com.hongguotv.core.model.ContentFilter
import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.HistoryEntry
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.Series
import com.hongguotv.core.model.UpdateFailure
import com.hongguotv.core.model.UpdateManifest
import com.hongguotv.core.model.UpdateState
import com.hongguotv.core.usecase.CheckFailure
import com.hongguotv.core.usecase.FavoriteCheckReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收藏角标、检查状态行、设置档位。 */
class CatalogLabelsTest {

    private fun entry(
        season: Int = 5,
        latestSeason: Int = 5,
        latestEpisodes: Int = 0,
        status: FavoriteStatus = FavoriteStatus.UP_TO_DATE,
    ) = FavoriteEntry(
        series = Series(id = "1", title = "测试书", season = season),
        addedAt = 0L,
        status = status,
        seriesKey = "测试书",
        latestSeason = latestSeason,
        latestEpisodes = latestEpisodes,
    )

    private fun progress(seriesId: String, updatedAt: Long, index: Int = 0) = Progress(
        seriesId = seriesId,
        episodeVid = "v-$seriesId",
        episodeIndex = index,
        positionMs = 1_000L,
        durationMs = 2_000L,
        completed = false,
        updatedAt = updatedAt,
    )

    // ---------- 角标 ----------

    @Test
    fun theBadgeStatesWhereTheUpdatesAre() {
        // 多季剧：季与集写成一个短语（"更新到第九季第80集"）
        assertEquals("更新到第十三季第80集", FavoriteLabels.updateBadge(entry(season = 5, latestSeason = 13, latestEpisodes = 80)))
        assertTrue(FavoriteLabels.attention(entry(season = 5, latestSeason = 13)))
    }

    @Test
    fun theBadgeNeverSaysUpToDate() {
        // 维护者口径："已是最新"没有信息量，卡片直接说现在更新到哪里
        assertEquals("更新到第五季第56集", FavoriteLabels.updateBadge(entry(latestSeason = 5, latestEpisodes = 56)))
        assertFalse(FavoriteLabels.attention(entry(latestSeason = 5, latestEpisodes = 56)))
    }

    @Test
    fun aSingleSeasonShowStatesOnlyTheEpisodeCount() {
        // 单季短剧没有季号：只说更新到多少集
        val single = entry(season = 0, latestSeason = 0, latestEpisodes = 524)
        assertEquals("更新到第524集", FavoriteLabels.updateBadge(single))
        assertTrue(FavoriteLabels.attention(single.copy(status = FavoriteStatus.NEW_SEASON)))
    }

    @Test
    fun seasonWithoutEpisodeCountStillStatesTheSeason() {
        assertEquals("更新到第三季", FavoriteLabels.updateBadge(entry(season = 0, latestSeason = 3)))
    }

    @Test
    fun nothingCheckedYetFallsBackToAHonestLabel() {
        // "还没检查出来"才兜底：季号/集数都是 0（刚从别的设备同步过来、季线也没取到）。
        // 已知季号时角标照旧说"更新到第几季"，检查状态不参与——卡片只说事实，不报流程状态。
        val unknown = entry(status = FavoriteStatus.UNCHECKED, latestSeason = 0, latestEpisodes = 0)
        assertEquals("尚未检查更新", FavoriteLabels.updateBadge(unknown))
        assertEquals("检查失败", FavoriteLabels.updateBadge(unknown.copy(status = FavoriteStatus.FAILED)))
    }

    @Test
    fun theCardBadgeSplitsIntoTwoLinesWhenBothSeasonAndEpisodeAreKnown() {
        // 产品口径：卡片角标拆两行——
        // 「更新到第X季」一行、「第X集」一行，单行放不下完整一句话会被截断
        assertEquals(
            "更新到第九季\n第352集",
            FavoriteLabels.updateBadgeLines(entry(latestSeason = 9, latestEpisodes = 352)),
        )
        // 拆不出两行的保持一行：只有季号、只有集数、兜底句
        assertEquals("更新到第三季", FavoriteLabels.updateBadgeLines(entry(latestSeason = 3, latestEpisodes = 0)))
        assertEquals("更新到第524集", FavoriteLabels.updateBadgeLines(entry(season = 0, latestSeason = 0, latestEpisodes = 524)))
        assertEquals("尚未检查更新", FavoriteLabels.updateBadgeLines(entry(latestSeason = 0, latestEpisodes = 0)))
        // 信息栏仍用单行版（宽度够）：两行文案只给卡片
        assertEquals(
            "更新到第九季第352集",
            FavoriteLabels.updateBadge(entry(latestSeason = 9, latestEpisodes = 352)),
        )
    }

    // ---------- 状态行 ----------

    @Test
    fun idleStatusExplainsWhyNothingIsHappening() {
        assertEquals("收藏后可检查有没有新内容", FavoriteLabels.idleStatus(favoriteCount = 0, remaining = 0))
        assertEquals("最近 12 小时检查过 · 可手动刷新", FavoriteLabels.idleStatus(favoriteCount = 3, remaining = 0))
        assertEquals("「检查更新」看有没有新一季或新集", FavoriteLabels.idleStatus(favoriteCount = 3, remaining = 3))
        // 自动检查被 6 小时门槛挡住：说"刚查过"而不是"没有要查的"，剩下的部数一起说
        assertEquals("刚查过 · 还有 7 部待查", FavoriteLabels.blockedStatus(remaining = 7))
        assertEquals("最近 6 小时检查过 · 可手动刷新", FavoriteLabels.blockedStatus(remaining = 0))
    }

    @Test
    fun progressTextShowsPositionAndTrimsTheTitle() {
        val text = FavoriteLabels.progressText(index = 2, total = 12, title = "这是一个特别长的剧名需要截断")
        assertTrue(text.startsWith("正在检查 3/12 · "))
        assertEquals("正在检查 3/12 · ".length + 14, text.length)
    }

    @Test
    fun summaryCoversEveryOutcome() {
        assertEquals("检查失败 · 稍后联网再试", FavoriteLabels.summary(FavoriteCheckReport(0, 3, 0, 0, true)))
        assertEquals("检查完成 · 2 部有新一季", FavoriteLabels.summary(FavoriteCheckReport(5, 0, 2, 0, false)))
        // 单季短剧每日加集：没有新季也要说出来
        assertEquals("检查完成 · 3 部有新集", FavoriteLabels.summary(FavoriteCheckReport(5, 0, 0, 3, false)))
        assertEquals(
            "检查完成 · 1 部有新一季 · 2 部有新集",
            FavoriteLabels.summary(FavoriteCheckReport(5, 0, 1, 2, false)),
        )
        assertEquals("检查完成 · 6 部已是最新 · 1 部失败", FavoriteLabels.summary(FavoriteCheckReport(6, 1, 0, 0, false)))
        assertEquals("检查完成 · 已是最新", FavoriteLabels.summary(FavoriteCheckReport(1, 0, 0, 0, false)))
        assertEquals("检查完成 · 4 部都已是最新", FavoriteLabels.summary(FavoriteCheckReport(4, 0, 0, 0, false)))
        // 连续失败中止：不能报成"检查完成"
        assertTrue(FavoriteLabels.summary(FavoriteCheckReport(2, 3, 1, 0, true)).startsWith("检查中断"))
    }

    @Test
    fun summaryNeverSwallowsFailuresWhenSomethingWasFound() {
        // 2 部有新季 + 5 部失败：只说前半句会让用户以为全查成功了
        assertEquals(
            "检查完成 · 1 部有新一季 · 1 部有新集 · 2 部失败",
            FavoriteLabels.summary(FavoriteCheckReport(checked = 5, failed = 2, newSeasons = 1, newEpisodes = 1, stoppedEarly = false)),
        )
        // 只有失败、没有发现：仍要说"几部已是最新"
        assertEquals(
            "检查完成 · 3 部已是最新 · 1 部失败",
            FavoriteLabels.summary(FavoriteCheckReport(checked = 3, failed = 1, newSeasons = 0, newEpisodes = 0, stoppedEarly = false)),
        )
    }

    @Test
    fun summarySaysHowManyAreLeftWhenTheRunWasCapped() {
        // 自动检查每轮最多 40 部：剩下的必须说出来，否则像"只查了这些"。
        // 写法是"本次 40/100"而不是"还有 60 部待查"——两个数一起给，用户才知道总量。
        assertEquals(
            "检查完成 · 2 部有新一季 · 本次 40/100",
            FavoriteLabels.summary(FavoriteCheckReport(40, 0, 2, 0, false, null, remaining = 60)),
        )
    }

    @Test
    fun riskControlPointsAtReRegisterInsteadOfRetry() {
        // 被风控时"重试"是死路：文案要说唯一有效的动作，按钮也要跟着换
        val report = FavoriteCheckReport(0, 3, 0, 0, true, CheckFailure.RISK_CONTROL)
        assertEquals("设备被拒绝 · 按「重新注册」", FavoriteLabels.summary(report))
        assertEquals("重新注册", FavoriteLabels.checkAction(CheckFailure.RISK_CONTROL))
    }

    @Test
    fun networkFailureSaysNetworkNotCheckFailure() {
        assertEquals(
            "网络异常 · 请稍后重试",
            FavoriteLabels.summary(FavoriteCheckReport(0, 1, 0, 0, false, CheckFailure.NETWORK)),
        )
        // 检查到一半断网：已检查的部数照报，不然用户以为一部都没查
        assertEquals(
            "网络异常 · 已检查 2 部 · 稍后重试",
            FavoriteLabels.summary(FavoriteCheckReport(2, 1, 0, 0, false, CheckFailure.NETWORK)),
        )
        // 断网只需要再试一次：按钮变成「重试」，按下去就是手动重试
        assertEquals("重试", FavoriteLabels.checkAction(CheckFailure.NETWORK))
        assertEquals("检查更新", FavoriteLabels.checkAction(CheckFailure.OTHER))
        assertEquals("检查更新", FavoriteLabels.checkAction(null))
    }

    // ---------- 系列进度 ----------
    // 「系列最近一条记录」的规则已上提到 core 的 SeasonProgress（S5），用例在 core 的 SeasonProgressTest；
    // 这里只留"页面把这条记录写成什么字"的部分。

    @Test
    fun aFinishedSeasonReadsAsFinishedInsteadOfZeroSeconds() {
        val finished = HistoryEntry(
            series = Series("1", "测试书"),
            progress = progress("1", updatedAt = 0L).copy(completed = true),
        )
        // 看完的一季停在"第1集 · 00:01"既没用也不准确
        assertEquals("整剧已看完", CatalogCards.history(finished).badge)
        // B 方案：历史卡不带时间（详情在底部信息栏里），季与集之间一个空格
        assertEquals(
            "第1集",
            CatalogCards.history(finished.copy(progress = finished.progress.copy(completed = false))).badge,
        )
    }

    @Test
    fun cardsCarryNoTitleAndEachPageStatesItsOwnStatus() {
        // 卡片下方不重复片名（维护者口径）：片名由底部信息栏的大字说
        val series = Series("1", "测试书第九季", season = 9, cover = "http://c/1.jpg")
        assertTrue(CatalogCards.search(series).title.isEmpty())

        // B 方案：历史卡只写「第九季 第20集」——不带时间，季与集之间一个空格
        val entry = HistoryEntry(series, progress("1", updatedAt = 0L, index = 19))
        assertEquals("第九季 第20集", CatalogCards.history(entry).badge)
    }

    @Test
    fun searchResultsStateTheEpisodeCountInsteadOfTheFavoriteBadge() {
        // B 方案（mockup 画面2）：结果卡写集数；未上线写「未上线」；收藏状态在信息栏里说
        val released = Series("1", "剧第九季", season = 9, episodeCount = 352)
        val unreleased = Series("2", "剧第十季", season = 10, released = false, episodeCount = 0)
        assertEquals("全 352 集", CatalogCards.search(released).badge)
        assertEquals("未上线", CatalogCards.search(unreleased).badge)
        assertEquals("未上线的卡保持暗档", true, CatalogCards.search(unreleased).dim)
    }

    // ---------- 设置档位 ----------

    @Test
    fun everyCycleWrapsBackToTheFirstOption() {
        assertEquals(720, SettingRows.next(480, SettingRows.qualities))
        assertEquals(1080, SettingRows.next(720, SettingRows.qualities))
        assertEquals(480, SettingRows.next(1080, SettingRows.qualities))

        assertEquals(1.25f, SettingRows.next(1f, SettingRows.speeds))
        assertEquals(0.75f, SettingRows.next(2f, SettingRows.speeds))

        assertEquals(FrameMode.ZOOM, SettingRows.next(FrameMode.FIT, SettingRows.frames))
        assertEquals(FrameMode.FIT, SettingRows.next(FrameMode.FILL, SettingRows.frames))

        assertEquals(ContentFilter.SHORT, SettingRows.next(ContentFilter.ALL, SettingRows.filters))
        assertEquals(ContentFilter.ALL, SettingRows.next(ContentFilter.COMIC, SettingRows.filters))
    }

    @Test
    fun aValueOutsideTheOptionsFallsBackToTheFirstOption() {
        // 盘上的值被改坏（例如 999P）时不要崩，也不要跳过一个档
        assertEquals(480, SettingRows.next(999, SettingRows.qualities))
        assertEquals(0.75f, SettingRows.next(3f, SettingRows.speeds))
    }

    @Test
    fun labelsMatchTheOldAppCopy() {
        assertEquals("1080P", SettingRows.qualityLabel(1080))
        assertEquals("1×", SettingRows.speedLabel(1f))
        assertEquals("1.25×", SettingRows.speedLabel(1.25f))
        assertEquals("完整画面", SettingRows.frameLabel(FrameMode.FIT))
        assertEquals("等比铺满", SettingRows.frameLabel(FrameMode.ZOOM))
        assertEquals("拉伸铺满", SettingRows.frameLabel(FrameMode.FILL))
        assertEquals("全部", SettingRows.filterLabel(ContentFilter.ALL))
        assertEquals("短剧", SettingRows.filterLabel(ContentFilter.SHORT))
        assertEquals("漫剧", SettingRows.filterLabel(ContentFilter.COMIC))
        assertEquals("倒序", SettingRows.sortLabel(ascending = false))
        assertEquals("正序", SettingRows.sortLabel(ascending = true))
        assertEquals("历史", SettingRows.tabLabel(2))
    }

    @Test
    fun updateLabelCoversEveryState() {
        val manifest = UpdateManifest(2, "1.1.0", "https://x/a.apk")
        assertEquals("自动检查新版本，也可手动检查", SettingRows.updateLabel(UpdateState.Idle))
        assertEquals("正在检查…", SettingRows.updateLabel(UpdateState.Checking))
        assertEquals("已是最新", SettingRows.updateLabel(UpdateState.UpToDate))
        // 按钮按下去就是下载，说明行不能还写"更新"（规格 §二.7 的文案口径）
        assertEquals("新版本 1.1.0 · 按确认下载", SettingRows.updateLabel(UpdateState.Available(manifest)))
        assertEquals("下载中 45%", SettingRows.updateLabel(UpdateState.Downloading(45)))
        assertEquals("下载中…", SettingRows.updateLabel(UpdateState.Downloading(-1)))
        assertEquals("按确认安装 1.1.0", SettingRows.updateLabel(UpdateState.ReadyToInstall("/cache/a.apk", "1.1.0")))
    }

    /** 失败文案按**类型**映射（ARCHITECTURE：运行期失败不传中文字符串）。 */
    @Test
    fun updateFailureLabelsAreMappedByType() {
        assertEquals("尚无可用版本", SettingRows.updateLabel(UpdateState.Failed(UpdateFailure.NONE)))
        assertEquals("检查失败，请稍后再试", SettingRows.updateLabel(UpdateState.Failed(UpdateFailure.NETWORK)))
        assertEquals("下载失败，请重试", SettingRows.updateLabel(UpdateState.Failed(UpdateFailure.DOWNLOAD)))
        assertEquals("下载不完整，请重试", SettingRows.updateLabel(UpdateState.Failed(UpdateFailure.INCOMPLETE)))
        assertEquals("安装包校验未通过，请稍后再试", SettingRows.updateLabel(UpdateState.Failed(UpdateFailure.VERIFICATION)))
    }

    // ---------- 设置页「检查更新」的按钮与 toast ----------

    /** 同一个外观曾经对应四种行为：按钮必须说"按下去会发生什么"。 */
    @Test
    fun theUpdateButtonSaysWhatPressingItWillDo() {
        val manifest = UpdateManifest(2, "1.1.0", "https://x/a.apk")
        assertEquals("检查更新", SettingRows.updateAction(UpdateState.Idle))
        assertEquals("检查更新", SettingRows.updateAction(UpdateState.UpToDate))
        assertEquals("正在检查…", SettingRows.updateAction(UpdateState.Checking))
        assertEquals("下载新版", SettingRows.updateAction(UpdateState.Available(manifest)))
        // 百分比只留在说明行：定宽胶囊装不下「下载中 87%」
        assertEquals("下载中", SettingRows.updateAction(UpdateState.Downloading(87)))
        assertEquals("下载中", SettingRows.updateAction(UpdateState.Downloading(-1)))
        assertEquals("去安装", SettingRows.updateAction(UpdateState.ReadyToInstall("/cache/a.apk", "1.1.0")))
        // 检查类失败：还是得重新查一遍
        assertEquals("检查更新", SettingRows.updateAction(UpdateState.Failed(UpdateFailure.NONE)))
        assertEquals("检查更新", SettingRows.updateAction(UpdateState.Failed(UpdateFailure.NETWORK)))
        // 下载类失败：清单还在手上，按下去直接重下（否则要按三次才能重试一次下载）
        assertEquals("重试下载", SettingRows.updateAction(UpdateState.Failed(UpdateFailure.DOWNLOAD)))
        assertEquals("重试下载", SettingRows.updateAction(UpdateState.Failed(UpdateFailure.INCOMPLETE)))
        assertEquals("重试下载", SettingRows.updateAction(UpdateState.Failed(UpdateFailure.VERIFICATION)))
    }

    /** 胶囊是定宽的（100dp / 13.5sp）：文案超过 5 个字就会被截断，右侧说明也会被推着走。 */
    @Test
    fun everyUpdateButtonLabelFitsTheFixedWidthCapsule() {
        val manifest = UpdateManifest(2, "1.1.0", "https://x/a.apk")
        val states = listOf(
            UpdateState.Idle,
            UpdateState.Checking,
            UpdateState.UpToDate,
            UpdateState.Available(manifest),
            UpdateState.Downloading(46),
            UpdateState.ReadyToInstall("/cache/a.apk", "1.1.0"),
        ) + UpdateFailure.entries.map { UpdateState.Failed(it) }
        states.forEach { state ->
            val label = SettingRows.updateAction(state)
            assertTrue("$state → $label 超过 5 个字", label.length <= 5)
        }
    }

    /**
     * 手动检查的终止态才弹 toast：进行中弹会刷屏，自动检查（页面没建）不经过这条路径。
     * 按钮已经会变，为什么还要 toast——检查几百毫秒就回来，「正在检查…」一闪而过。
     */
    @Test
    fun onlyTheEndStatesOfAManualCheckToast() {
        val manifest = UpdateManifest(2, "1.1.0", "https://x/a.apk")
        assertNull(SettingRows.updateToast(UpdateState.Idle))
        assertNull(SettingRows.updateToast(UpdateState.Checking))
        assertNull(SettingRows.updateToast(UpdateState.Downloading(46)))
        assertEquals("已是最新", SettingRows.updateToast(UpdateState.UpToDate))
        assertEquals("发现新版本 1.1.0", SettingRows.updateToast(UpdateState.Available(manifest)))
        assertEquals("下载完成，按确认安装", SettingRows.updateToast(UpdateState.ReadyToInstall("/cache/a.apk", "1.1.0")))
        assertEquals("尚无可用版本", SettingRows.updateToast(UpdateState.Failed(UpdateFailure.NONE)))
        assertEquals("检查失败，请稍后再试", SettingRows.updateToast(UpdateState.Failed(UpdateFailure.NETWORK)))
        assertEquals("下载失败，请稍后再试", SettingRows.updateToast(UpdateState.Failed(UpdateFailure.DOWNLOAD)))
        assertEquals("下载失败，请稍后再试", SettingRows.updateToast(UpdateState.Failed(UpdateFailure.INCOMPLETE)))
        assertEquals("安装包校验未通过", SettingRows.updateToast(UpdateState.Failed(UpdateFailure.VERIFICATION)))
    }

    /** Android 12+ 的文字 Toast 最多两行（960dp 宽一行约 40 字）：整句控制在 30 字以内。 */
    @Test
    fun toastCopyStaysWithinTwoLines() {
        val manifest = UpdateManifest(2, "1.1.0", "https://x/a.apk")
        val states = listOf(
            UpdateState.UpToDate,
            UpdateState.Available(manifest),
            UpdateState.ReadyToInstall("/cache/a.apk", "1.1.0"),
        ) + UpdateFailure.entries.map { UpdateState.Failed(it) }
        states.forEach { state ->
            val text = SettingRows.updateToast(state)!!
            assertTrue("$state → $text 超过 30 字", text.length <= 30)
        }
    }
}
