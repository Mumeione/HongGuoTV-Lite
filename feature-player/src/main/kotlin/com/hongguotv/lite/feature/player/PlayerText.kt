package com.hongguotv.lite.feature.player

import com.hongguotv.core.model.Settings
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.lite.uikit.text.TvText

/**
 * 播放页的文案派生。纯函数（不 import `android.*`），时间/倍速/清晰度/画面这类跨页面口径
 * 全部转调 `uikit` 的 [TvText]——播放器是第三个消费者，这里再拼一遍就会出现
 * "设置页写 1080P、播放器写 1080p"这种分裂。
 */
object PlayerText {

    /** 侧栏顶部那行：`正在播放 · 12:34 / 45:00 · 1080P · 1.25×`，有定时再追加一段。 */
    fun status(
        state: PlaybackState,
        positionMs: Long,
        durationMs: Long,
        quality: Int,
        speed: Float,
        sleep: String? = null,
    ): String {
        val parts = mutableListOf(stateLabel(state))
        if (state is PlaybackState.Playing || state is PlaybackState.Ended) {
            parts += "${TvText.clock(positionMs)} / ${TvText.clock(durationMs)}"
        }
        parts += TvText.quality(quality)
        parts += TvText.speed(speed)
        if (sleep != null) parts += "定时 $sleep"
        return parts.joinToString(" · ")
    }

    private fun stateLabel(state: PlaybackState): String = when (state) {
        PlaybackState.Idle -> "准备中"
        is PlaybackState.Preparing -> "正在获取播放地址…"
        is PlaybackState.Playing -> when {
            state.pausedBy == PauseReason.SLEEP -> "定时停止"
            state.pausedBy != null -> "已暂停"
            state.buffering -> "正在缓冲…"
            else -> "正在播放"
        }
        is PlaybackState.Ended -> "本集已结束"
        is PlaybackState.Failed -> "播放出错了"
    }

    /** 侧栏标题：`剧名 · 第 9 集`。 */
    fun title(seriesTitle: String, index: Int): String = "$seriesTitle · 第 ${index + 1} 集"

    /** 选集范围：`第 1—100 集 / 共 300 集`。空区间不给文字（页面据此不显示翻组行）。 */
    fun range(range: IntRange, total: Int): String =
        if (range.isEmpty()) "" else "第 ${range.first + 1}—${range.last + 1} 集 / 共 $total 集"

    /** 播放设置五行。标签直接反映当前值——用户要把侧栏当"状态"看，而不是"入口"看。 */
    fun settingsRows(settings: Settings, sleep: String): List<String> = listOf(
        "倍速 · ${TvText.speed(settings.speed)}",
        "清晰度 · ${TvText.quality(settings.quality)}",
        "画面 · ${TvText.frame(settings.frame)}",
        "定时停止 · $sleep",
        "从头播放",
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

        is PlaybackState.Ended -> if (state.lastOfSeason) SEASON_ENDED else EPISODE_ENDED
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

    const val FAST_FORWARD: String = "2 倍速快进"

    /**
     * 播放画面底部的常驻操作提示（维护者口径：底部要写清怎么操作）。
     * 每一段与 `PlayerKeys` 的命令一一对应：左右 10 秒、长按→ 2 倍速、上下切集、
     * 确定暂停/继续、菜单选集与设置、返回退出。
     */
    const val OPERATION_HINT: String =
        "左右 快退/快进　长按→ 2倍快进　上下 切集　确定 暂停/继续　菜单 选集·播放设置　返回 退出"

    private const val DECODE_HINT: String = "低清晰度更容易解码，代价是画面变糊。"
    private const val NETWORK_HINT: String = "网络抖动时先重试通常就够。"

    /** 上游没下发播放地址时的说明。不写"网络"二字——这时候用户的网是好的。 */
    const val CHANNEL_HINT: String = "播放渠道暂时没有下发地址，过一会儿再试。"
}
