package com.hongguotv.lite.feature.player

import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.port.MediaStream

/**
 * 播放页的状态归属（`ARCHITECTURE.md`「状态归属」）。**播放只有一个所有者**，就是这里。
 *
 * `pausedBy` 是这一版最关键的一处类型化：旧实现用
 * `requestedAutoplay + pausedForLifecycle + sleepStopped + playError` 四个布尔拼出"该不该续播"，
 * 于是"关掉菜单之后要不要接着播"这种问题永远说不清。现在它是**一个枚举**：
 * 关菜单只看 `pausedBy == MENU`，用户在菜单里手动暂停过就变成 `USER`，不会再被菜单"续"回去。
 */
sealed interface PlaybackState {
    /** 还没有开始（详情没到手、或者已经释放）。 */
    data object Idle : PlaybackState

    /** 正在换取流 / 等待解码器就绪。 */
    data class Preparing(val index: Int) : PlaybackState

    /** `pausedBy == null` 表示正在播。 */
    data class Playing(
        val index: Int,
        val pausedBy: PauseReason?,
        val buffering: Boolean,
    ) : PlaybackState

    /**
     * 本集结束且没有下一步。
     * [lastOfSeason] 决定文案说"本集已结束"还是"本季已结束"——差一集和差一季对用户是两件事。
     */
    data class Ended(val index: Int, val lastOfSeason: Boolean) : PlaybackState

    /** @param retry 自动重试的处境：决定文案与"要不要再等"。 */
    data class Failed(
        val index: Int,
        val failure: PlaybackFailure,
        val retry: RetryState,
    ) : PlaybackState
}

/**
 * 自动重试的处境。**四种说法对应四件不同的事**，压在"重试时间戳是否为 null"里
 * 就分不出"解码失败不该重试"和"重试三次都没成"——用户看到的是同一句"播放失败"。
 */
sealed interface RetryState {
    /** 不会自动重试：解码失败，或用户手动停了。 */
    data object None : RetryState

    /** 断网：**不倒计时**，联网后自己再试（旧行为）。 */
    data object WaitingNetwork : RetryState

    /** 已排期，[atMs] 是重试时刻。 */
    data class Scheduled(val atMs: Long) : RetryState

    /** 三次预算用完，只能手动重试或换清晰度。 */
    data object Exhausted : RetryState
}

/** 暂停的来由。决定"谁能把它恢复"。 */
enum class PauseReason {
    /** 用户按了暂停/确认键。 */
    USER,

    /** 侧栏打开时自动暂停；关侧栏时若仍是它，就接着播。 */
    MENU,

    /** 定时停止。只有用户按「继续播放」才会解。 */
    SLEEP,

    /** 应用退到后台。回到前台不自动续播（电视上一个自己开始播的页面是惊吓）。 */
    LIFECYCLE,
}

/**
 * 播放失败的分类。**分类决定要不要自动重试**：
 * - [Decode]：电视解不了这个编码/容器。再试三次还是解不了，换个清晰度才有用；
 * - [Unavailable]：取流失败、网络抖动、风控。等一会儿重试通常就好了。
 */
sealed interface PlaybackFailure {
    data class Decode(val detail: String? = null) : PlaybackFailure
    data class Unavailable(val cause: Throwable? = null) : PlaybackFailure
}

/** 引擎回调。引擎只报事实，不解释业务（要不要连播、要不要重试都在状态机里）。 */
interface EngineListener {
    /** 可播放了（每次装载后的第一次就绪）。 */
    fun onReady()

    /** 播放位置/缓冲态/播放态有变化。 */
    fun onChanged()

    fun onEnded()

    fun onFailed(failure: PlaybackFailure)
}

/**
 * 播放实现的接缝。**只用纯类型**（`Long`/`Float`/枚举/`MediaStream`），
 * media3 只出现在 `ExoPlayerEngine` 一个文件里——这是"换播放器不动状态机"的前提。
 */
interface PlaybackEngine {
    fun setListener(listener: EngineListener?)

    /** 装一条流。[stream] 的归属交给引擎：下一次装载或释放时由引擎关闭它。 */
    fun load(stream: MediaStream, positionMs: Long, playWhenReady: Boolean)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    fun setFrame(mode: FrameMode)

    /** 停止并丢弃当前媒体项（换集/换清晰度时用），画面回到黑屏。 */
    fun stop()

    fun release()

    val positionMs: Long
    val durationMs: Long
    val buffering: Boolean
    val ready: Boolean

    /**
     * 播放头停在片尾（media3 的 `STATE_ENDED`）。"从片尾再按播放要回到片头"靠它判断——
     * 这类状态下 [ready] 是 false，不能用 [ready] 当"已经看到最后"的判据。
     */
    val ended: Boolean
}
