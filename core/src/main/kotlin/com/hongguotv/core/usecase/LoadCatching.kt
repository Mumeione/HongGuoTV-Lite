package com.hongguotv.core.usecase

import java.util.concurrent.CancellationException

/**
 * 页面加载兜底：把「端口契约只承诺 [UpstreamError][com.hongguotv.core.port.UpstreamError]，
 * 但脏本地数据会从仓库 `require` / 解析层抛别的异常」的缺口收在一个地方。
 *
 * 取消不是失败：[CancellationException] 原样抛出，不破坏结构化并发；
 * 其余 [Exception] 交给 [onFailure]（页面记入失败态或静默），返回 null，绝不冒出 `launch` 崩进程。
 *
 * 用 JDK 的 CancellationException（core 不依赖协程库）：JVM 上它与
 * `kotlinx.coroutines.CancellationException` 是同一个类（typealias），catch 得到。
 * （V1.0.0 审计 P1-3；此前六个调用点各写一份 try/catch，按"不要各写一份"纪律收口。）
 */
suspend inline fun <T> loadCatching(
    crossinline onFailure: (Throwable) -> Unit = {},
    crossinline block: suspend () -> T,
): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
        null
    }
