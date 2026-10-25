package com.hongguotv.core.usecase

/**
 * 片尾预取：在下一集真正开播前，把它的取流与文件头准备好。
 *
 * 只做决定、不碰 IO——页面按 [Decision.Fetch] 去打开流，播放状态机完全不知道预取的存在。
 * 这样"什么时候该预取"能脱机断言，真机上只剩"打开一条流"这一件简单事。
 *
 * 窗口按**墙钟时间**算（`(时长 - 位置) / 倍速`），不按媒体时间：
 * 2 倍速看最后 40 秒，用户 20 秒后就会撞到片尾；按媒体时间算会来不及。
 *
 * 取流接口会随机回空体（见 `Project Memory`），所以窗口内允许有限次重试：
 * 3 秒一次、最多 3 次，刚好覆盖最后 10 秒。
 */
object PrefetchPolicy {

    /**
     * 剩余墙钟时间不超过这么多就动手。
     *
     * **不能照搬长剧的 30 秒**：短剧一集约 1 分钟，30 秒会让预取提前到剧集中段，
     * 既抢当前集的带宽，也让"预取"变成"提前半集下载"。
     */
    const val WINDOW_MS = 10_000L

    /** 起播后至少播这么久才预取：刚起播就预取会和首屏抢带宽。 */
    const val MIN_READY_MS = 3_000L

    /** 当前集至少缓冲出这么多余量才预取；短剧片尾的缓冲本来就不多。 */
    const val MIN_BUFFER_MS = 2_000L

    const val MAX_ATTEMPTS = 3
    const val RETRY_INTERVAL_MS = 3_000L

    sealed interface Decision {
        /** 去打开下一集的流。 */
        data object Fetch : Decision

        /** 这一轮不预取；[reason] 用于诊断与测试，不进入界面文案。 */
        data class Skip(val reason: Reason) : Decision
    }

    enum class Reason {
        NOT_PLAYING,
        AUTO_NEXT_OFF,
        NO_NEXT_EPISODE,
        ALREADY_PREPARED,
        IN_FLIGHT,
        LOW_RAM,
        OFFLINE,
        METERED,
        UNKNOWN_TIMING,
        TOO_EARLY,
        ATTEMPTS_EXHAUSTED,
        NOT_READY_LONG_ENOUGH,
        LOW_BUFFER,
        RETRY_BACKOFF,
    }

    /**
     * @param positionMs 当前播放位置。
     * @param durationMs 本集总时长；0 表示还不知道（media3 未就绪）。
     * @param speed 实际生效倍速（含按住右键的临时快进）。
     * @param playing 用户没有暂停（侧栏/定时/后台都算暂停）。
     * @param autoNext 设置里的自动连播。
     * @param hasNext 同一季还有下一集；跨季由导航层处理，不在这里预取。
     * @param lowRam 低内存设备不预取（预取流会占住一份连接与前缀缓存）。
     * @param metered 计费网络不预取。
     * @param readyForMs 当前这一集已经顺畅播放了多久。
     * @param bufferedAheadMs 当前位置之后已缓冲的时长。
     * @param attempts 这一集（这一目标）已经尝试过几次。
     * @param sinceLastAttemptMs 距上一次尝试过去了多久；没试过传 [Long.MAX_VALUE]。
     * @param alreadyPrepared 预备流已经就绪。
     * @param inFlight 有一次预取正在取。
     */
    data class Input(
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float,
        val playing: Boolean,
        val autoNext: Boolean,
        val hasNext: Boolean,
        val lowRam: Boolean,
        val online: Boolean,
        val metered: Boolean,
        val readyForMs: Long,
        val bufferedAheadMs: Long,
        val attempts: Int,
        val sinceLastAttemptMs: Long,
        val alreadyPrepared: Boolean,
        val inFlight: Boolean,
    )

    fun decide(input: Input): Decision {
        if (!input.playing) return Decision.Skip(Reason.NOT_PLAYING)
        if (!input.autoNext) return Decision.Skip(Reason.AUTO_NEXT_OFF)
        if (!input.hasNext) return Decision.Skip(Reason.NO_NEXT_EPISODE)
        // 已经备好 / 正在备：后面的网络条件再变也不必干预
        if (input.alreadyPrepared) return Decision.Skip(Reason.ALREADY_PREPARED)
        if (input.inFlight) return Decision.Skip(Reason.IN_FLIGHT)
        if (input.lowRam) return Decision.Skip(Reason.LOW_RAM)
        if (!input.online) return Decision.Skip(Reason.OFFLINE)
        if (input.metered) return Decision.Skip(Reason.METERED)
        if (input.durationMs <= 0 || input.positionMs < 0 || input.positionMs >= input.durationMs ||
            !input.speed.isFinite() || input.speed <= 0f
        ) {
            return Decision.Skip(Reason.UNKNOWN_TIMING)
        }
        val remainingWallMs = (input.durationMs - input.positionMs) / input.speed
        if (remainingWallMs > WINDOW_MS) return Decision.Skip(Reason.TOO_EARLY)
        if (input.attempts >= MAX_ATTEMPTS) return Decision.Skip(Reason.ATTEMPTS_EXHAUSTED)
        if (input.readyForMs < MIN_READY_MS) return Decision.Skip(Reason.NOT_READY_LONG_ENOUGH)
        if (input.bufferedAheadMs < MIN_BUFFER_MS) return Decision.Skip(Reason.LOW_BUFFER)
        if (input.attempts > 0 && input.sinceLastAttemptMs < RETRY_INTERVAL_MS) {
            return Decision.Skip(Reason.RETRY_BACKOFF)
        }
        return Decision.Fetch
    }
}
