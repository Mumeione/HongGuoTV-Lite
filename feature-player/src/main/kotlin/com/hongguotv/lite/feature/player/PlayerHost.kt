package com.hongguotv.lite.feature.player

import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.Settings

/**
 * 状态机的副作用出口。**状态机自己不碰 IO、不碰协程、不碰 `android.*`**，
 * 所有"要动外部世界"的事都从这几个方法出去，由页面实现：
 *
 * - 读（取流）用页面作用域：用户离开就取消，别让一条没人要的流继续下载；
 * - 写（进度 / 设置 / 跨季跳转）用宿主作用域：页面马上就要被销毁，挂在页面作用域会"还没写就被取消"
 *   （S5 踩过的坑，见交接文档第 8 条）。
 */
interface PlayerHost {

    /** 请页面去取流；结果通过 [PlaybackSession.onStreamReady] / [PlaybackSession.onStreamFailed] 回来。 */
    fun requestStream(vid: String, quality: Int)

    fun save(progress: Progress)

    /** 异步写设置（倍速/清晰度/画面）。 */
    fun applySettings(transform: (Settings) -> Settings)

    /** 跨季连播：换的是另一个 `series_id`，必须换路由。 */
    fun openPlayer(seriesId: String, episodeIndex: Int, positionMs: Long)

    fun keepScreenOn(on: Boolean)

    /** 一次性提示（Toast）。 */
    fun notify(text: String)

    /**
     * 当前是否连着网。**断网时不排自动重试**：否则三次预算会在八秒内被烧光，
     * 用户看到的却是"已自动重试 3 次"，而其实一次都没真正试成（旧实现同口径）。
     */
    fun isOnline(): Boolean
}

/** 延时执行。状态机只需要"等一会儿重试"，不需要知道是 Handler 还是别的。 */
fun interface Scheduler {
    fun after(delayMs: Long, action: () -> Unit): ScheduledTask
}

fun interface ScheduledTask {
    fun cancel()
}
