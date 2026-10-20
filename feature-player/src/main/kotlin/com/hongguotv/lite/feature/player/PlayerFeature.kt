package com.hongguotv.lite.feature.player

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.MediaPort
import com.hongguotv.core.port.Navigator
import com.hongguotv.core.port.ProgressPort
import com.hongguotv.core.port.SettingsPort
import com.hongguotv.lite.uikit.ui.UiKit
import kotlinx.coroutines.CoroutineScope

/**
 * 播放页的入口（S6）。
 *
 * `app`（S7）按路由调 [show]；播放页是唯一的页面，换目标即销毁重建——
 * 与 `DetailFeature` 同一口径，页面之间不维护返回栈（返回语义在 `Navigator`）。
 *
 * **`MediaPort` 由 `app` 注入**：播放器只认识"打开一条可读流"，不知道 Range、CENC、moov 的存在，
 * 所以换取流实现（甚至换成直连 URL）不需要动这个模块。
 *
 * 按键必须从宿主的 `dispatchKeyEvent` 走 [onKeyEvent]：焦点系统会把方向键吃掉，
 * 而播放页在"没有可聚焦项"时正是靠这张按键表工作。
 */
class PlayerFeature(
    val ui: UiKit,
    val content: ContentPort,
    val progress: ProgressPort,
    val favorites: FavoritesPort,
    val settings: SettingsPort,
    val navigator: Navigator,
    val media: MediaPort,
    /** 主线程作用域，由 `app` 提供（`Dispatchers.Main`）。写盘/写设置的存活期比页面长。 */
    val scope: CoroutineScope,
) {

    private val container = FrameLayout(ui.context)
    private var page: PlayerPage? = null

    /** 容器。未调用 [show] 时是空的（S7 每次都先 `show` 再取视图）。 */
    fun view(): View = container

    /**
     * 起播。[seriesId] + [episodeIndex] + [positionMs] 由调用方给，
     * 落点**已按 `ResumePoint` 归一化**——播放器不再自己从 `Progress` 推一遍。
     */
    fun show(seriesId: String, episodeIndex: Int, positionMs: Long = 0) {
        page?.release()
        page = null
        container.removeAllViews()
        val created = PlayerPage(this, seriesId, episodeIndex, positionMs)
        page = created
        container.addView(
            created.view(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        created.build()
    }

    /** 宿主按键入口。返回 true 表示这次按键被播放页消费（不要再交给系统）。 */
    fun onKeyEvent(event: KeyEvent): Boolean = page?.onKeyEvent(event) ?: false

    /** 应用退到后台：暂停并保存（不销毁画面，回到前台仍停在原位）。 */
    fun pause() {
        page?.pause()
    }

    /** 回到前台：恢复心跳（定时停止与"等联网重试"都靠它）。**不自动续播**。 */
    fun resume() {
        page?.resume()
    }

    /** 离开播放页：取消失效请求、释放播放器、停掉定时与浮层。 */
    fun release() {
        page?.release()
        page = null
        container.removeAllViews()
    }
}
