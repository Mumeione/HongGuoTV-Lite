package com.hongguotv.lite.feature.player

import com.hongguotv.core.port.MediaPort
import com.hongguotv.core.port.MediaStream
import com.hongguotv.core.usecase.PrefetchPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 片尾预取的编排：**决定**交给 `PrefetchPolicy`，**归属**交给 `PreparedStreamSlot`，
 * 这里只做"把状态喂进去、按决定去开流、失败退避"这一层。
 *
 * 单独成类有两个理由：
 * - `PlayerPage` 已经承担按键、渲染、取流、生命周期四件事，再塞进预取会继续长胖；
 * - 它可以用假 `MediaPort` + `Dispatchers.Unconfined` 在 JVM 上把"预取成功被领走、
 *   失败退避、换目标丢弃、暂停取消"全部断言，不需要真机与真网络。
 */
internal class EpisodePrefetcher(
    private val scope: CoroutineScope,
    private val media: MediaPort,
    private val clock: () -> Long,
    private val slot: PreparedStreamSlot = PreparedStreamSlot(clock),
) {

    /**
     * 一次判定的输入里"播放器当前是什么样"。预取策略本身不读引擎，页面把读数搬进来。
     */
    data class Conditions(
        val positionMs: Long,
        val durationMs: Long,
        val bufferedPositionMs: Long,
        val speed: Float,
        val playing: Boolean,
        val autoNext: Boolean,
        val lowRam: Boolean,
        val online: Boolean,
        val metered: Boolean,
    )

    private var target: String? = null
    private var job: Job? = null
    private var attempts = 0
    private var attemptedAt = 0L
    private var readySince = 0L

    /** 顺畅播放的起点：刚起播先不预取，免得和首屏抢带宽。 */
    fun onSessionState(state: PlaybackState) {
        if (state !is PlaybackState.Playing) {
            readySince = 0L
            return
        }
        if (state.pausedBy == null && !state.buffering && readySince == 0L) {
            readySince = clock()
        }
    }

    /**
     * 每秒一次。**同一目标**的失败在窗口内按策略退避重试；
     * 换集 / 换清晰度 / 没有下一集则连旧流一起作废。
     */
    fun tick(nextVid: String?, quality: Int, conditions: Conditions) {
        val key = nextVid?.let { "$it:$quality" }
        if (key != target) {
            clear()
            target = key
        }
        if (nextVid == null || key == null) return

        val now = clock()
        val decision = PrefetchPolicy.decide(
            PrefetchPolicy.Input(
                positionMs = conditions.positionMs,
                durationMs = conditions.durationMs,
                speed = conditions.speed,
                playing = conditions.playing,
                autoNext = conditions.autoNext,
                hasNext = true,
                lowRam = conditions.lowRam,
                online = conditions.online,
                metered = conditions.metered,
                readyForMs = if (readySince == 0L) 0L else now - readySince,
                bufferedAheadMs = conditions.bufferedPositionMs - conditions.positionMs,
                attempts = attempts,
                sinceLastAttemptMs = if (attemptedAt == 0L) Long.MAX_VALUE else now - attemptedAt,
                alreadyPrepared = slot.isFresh(key),
                inFlight = job?.isActive == true,
            ),
        )
        when (decision) {
            PrefetchPolicy.Decision.Fetch -> start(key, nextVid, quality)
            is PrefetchPolicy.Decision.Skip -> when (decision.reason) {
                PrefetchPolicy.Reason.NOT_PLAYING,
                PrefetchPolicy.Reason.AUTO_NEXT_OFF,
                PrefetchPolicy.Reason.NO_NEXT_EPISODE,
                PrefetchPolicy.Reason.LOW_RAM,
                PrefetchPolicy.Reason.METERED -> clear()
                else -> Unit
            }
        }
    }

    /**
     * 领取预备流。目标对不上（换集 / 换清晰度）就先扔掉旧的；
     * 领到之后槽位清空，流的归宿转交给播放器。
     */
    fun take(vid: String, quality: Int): MediaStream? {
        val key = "$vid:$quality"
        job?.cancel()
        job = null
        if (target != key) clear()
        val stream = slot.take(key)
        if (stream != null) {
            target = null
            attempts = 0
            attemptedAt = 0L
        }
        return stream
    }

    /** 暂停、退后台、换路由、出错、目标作废：预备流一条都不留。 */
    fun clear() {
        job?.cancel()
        job = null
        slot.clear()
        target = null
        attempts = 0
        attemptedAt = 0L
    }

    /** 预取失败静默：片尾仍会走正常取流，不把用户拽进错误界面。 */
    private fun start(key: String, vid: String, quality: Int) {
        attempts++
        attemptedAt = clock()
        job = scope.launch {
            val stream = try {
                media.open(vid, quality)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!isActive || target != key) {
                stream?.close()
                return@launch
            }
            if (stream != null) slot.offer(key, stream)
            job = null
        }
    }
}
