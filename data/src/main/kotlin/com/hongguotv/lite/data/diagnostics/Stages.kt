package com.hongguotv.lite.data.diagnostics

import com.hongguotv.core.port.DiagnosticsPort
import kotlinx.coroutines.CancellationException

/**
 * 一段打点的两种写法：同步与挂起。
 *
 * 只做三件事：记开始时间、成功记 ok、失败记 FAIL 后**原样抛出**。
 * **取消不记失败**：切页/退后台会取消在途请求，那不是上游出了问题；记成 FAIL 会让日志里
 * 每切一次页就多一条假故障。
 */
internal inline fun <T> DiagnosticsPort.stageTimed(
    stage: DiagnosticsPort.Stage,
    detail: String,
    block: () -> T,
): T {
    val started = System.nanoTime()
    return try {
        val result = block()
        this.stage(stage, true, "$detail ${elapsedMs(started)}ms")
        result
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        this.stage(stage, false, "$detail ${elapsedMs(started)}ms ${error.javaClass.simpleName}")
        throw error
    }
}

internal suspend inline fun <T> DiagnosticsPort.stageTimedSuspend(
    stage: DiagnosticsPort.Stage,
    detail: String,
    block: suspend () -> T,
): T {
    val started = System.nanoTime()
    return try {
        val result = block()
        this.stage(stage, true, "$detail ${elapsedMs(started)}ms")
        result
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        this.stage(stage, false, "$detail ${elapsedMs(started)}ms ${error.javaClass.simpleName}")
        throw error
    }
}

/** 与 `stageTimed` 的耗时口径一致；`HttpTransport` 自己打 CONNECT 段时复用。 */
internal fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
