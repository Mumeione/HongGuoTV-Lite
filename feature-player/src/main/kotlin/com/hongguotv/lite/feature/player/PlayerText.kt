package com.hongguotv.lite.feature.player

import com.hongguotv.core.model.Settings
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.lite.uikit.text.TvText

/**
 * 播放页的文案派生。纯函数（不 import `android.*`），时间/倍速/清晰度/画面这类跨页面口径
 * 全部转调 `uikit` 的 [TvText]——播放器是第三个消费者，这里再拼一遍就会出现
 * "设置页写 1080P、播放器写 1080p"这种分裂。
 */
/** 抽屉右列的一行：左名称、右当前值（空 = 这一行是动作，不是设置）。 */
data class PlayerSettingLine(val label: String, val value: String)

object PlayerText {

    /**
     * 抽屉的状态行（B 方案）：**只报失败**——成功与否看画面，正在播什么看右侧设置行，
     * 状态行重复这些只会占地方（维护者口径）。
     */
    fun drawerStatus(state: PlaybackState): String =
        if (state is PlaybackState.Failed) notice(state).orEmpty() else ""

    /** 侧栏标题：`剧名 · 第 9 集`。 */
    fun title(seriesTitle: String, index: Int): String = "$seriesTitle · 第 ${index + 1} 集"

    /** 选集范围：`第 1—100 集 / 共 300 集`。空区间不给文字（页面据此不显示翻组行）。 */
    fun range(range: IntRange, total: Int): String =
        if (range.isEmpty()) "" else "第 ${range.first + 1}—${range.last + 1} 集 / 共 $total 集"

    /**
     * 播放设置五行：**名称与值分开**（2026-10-24 验收口径）。
     *
     * 原来写成一整串「倍速 · 1×」，名称和当前档位同一个字号同一个颜色，抽屉打开后
     * 一眼看不出"哪部分是现在的设置"。现在左列名称退一档、右列值亮一档，
     * 用户扫一眼就知道当前是什么档。
     */
    fun settingsRows(settings: Settings, sleep: String): List<PlayerSettingLine> = listOf(
        PlayerSettingLine("倍速", TvText.speed(settings.speed)),
        PlayerSettingLine("清晰度", TvText.quality(settings.quality)),
        PlayerSettingLine("画面", TvText.frame(settings.frame)),
        PlayerSettingLine("定时停止", sleep),
        // 动作不是设置：没有"当前值"，整行只有名称
        PlayerSettingLine("从头播放", ""),
    )

    /** 跳集页的提示行。 */
    fun jumpHint(digits: String): String =
        if (digits.isEmpty()) "数字键直接输入集数" else "定位到第 $digits 集"

    /** 跳集越界/非法时的就地提示。范围一定要写出来，否则用户不知道能输到几。 */
    fun jumpRejected(total: Int): String = "请输入 1—$total 之间的集数"

    /** 失败文案：解码问题与取流问题是两件事，说的办法也不同。 */
    fun failure(failure: PlaybackFailure): String = when (failure) {
        is PlaybackFailure.Decode -> "电视无法解码当前视频，可尝试更低清晰度。"
        is PlaybackFailure.Unavailable -> {
            val cause = failure.cause
            if (cause is UpstreamError) TvText.error(cause) else "播放失败，可重试或切换清晰度。"
        }
    }

    /**
     * 提示卡。**只有需要用户做选择的时刻才出现**（出错 / 结束 / 定时停止），
     * 正常播放时它必须是 null——否则画面底下常驻一块无关的板子。
     */
    fun notice(state: PlaybackState): String? = when (state) {
        is PlaybackState.Failed -> when (state.retry) {
            is RetryState.Scheduled -> "播放出错了，正在自动重试…"
            RetryState.WaitingNetwork -> OFFLINE
            RetryState.Exhausted -> RETRY_EXHAUSTED
            RetryState.None -> failure(state.failure)
        }

        is PlaybackState.Ended -> when {
            state.seriesEnded -> SERIES_ENDED
            state.lastOfSeason -> SEASON_ENDED
            else -> EPISODE_ENDED
        }
        is PlaybackState.Playing -> if (state.pausedBy == PauseReason.SLEEP) SLEEP_STOPPED else null
        else -> null
    }

    /** 失败卡下面那行说明。解码失败解释"为什么换清晰度有用"；其余要分清"你的网"与"上游的渠道"。 */
    fun failureHint(failure: PlaybackFailure): String? = when {
        failure is PlaybackFailure.Decode -> DECODE_HINT
        // 200 空体 / 风控码：上游根本没下发播放地址。这与"网络抖动"是两件事，
        // 让人反复重启路由器只会浪费十分钟。
        failure is PlaybackFailure.Unavailable && failure.cause.isChannelDown() -> CHANNEL_HINT
        else -> NETWORK_HINT
    }

    /** 上游"有响应但不下发地址"这两类失败：换清晰度、换时段都可能不一样，但重试最快。 */
    private fun Throwable?.isChannelDown(): Boolean = this is UpstreamError.EmptyBody || this is UpstreamError.RiskControl

    /** 自动重试 3 次仍失败时的说明。 */
    const val RETRY_EXHAUSTED: String = "已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"

    /** 断网时不倒计时：联网后自己会接着试（旧行为）。 */
    const val OFFLINE: String = "网络已断开，联网后会自动重试"

    const val SLEEP_STOPPED: String = "定时停止已生效 · 观看位置已保存"

    const val EPISODE_ENDED: String = "本集已结束"

    const val SEASON_ENDED: String = "本季已结束"

    /** 最后一季的最后一集：没有"下一集"也没有"下一季"，这一句要说明白。 */
    const val SERIES_ENDED: String = "全剧已播完，暂无最新集"

    const val FAST_FORWARD: String = "2 倍速快进"

    /**
     * 播放画面**顶部**的常驻操作提示（B 方案，mockup 画面6）：（键, 说明）成对，
     * 页面把它画成"键白粗 + 说明弱一档"。每一段与 `PlayerKeys` 的命令一一对应：
     * 左右 10 秒、长按右 2 倍速、上下切集、确定暂停/继续、菜单选集与设置。
     */
    fun operationHints(): List<Pair<String, String>> = listOf(
        "左右" to "快退/快进",
        // 键名列写"长按右"而不是"长按→"：箭头在遥控器上没有对应物，中文"右"读一遍就懂
        "长按右" to "2倍快进",
        "上下" to "切集",
        "确定" to "暂停/继续",
        "菜单" to "选集·播放设置",
    )

    private const val DECODE_HINT: String = "低清晰度更容易解码，代价是画面变糊。"
    private const val NETWORK_HINT: String = "网络抖动时先重试通常就够。"

    /** 上游没下发播放地址时的说明。不写"网络"二字——这时候用户的网是好的。 */
    const val CHANNEL_HINT: String = "播放渠道暂时没有下发地址，过一会儿再试。"
}
