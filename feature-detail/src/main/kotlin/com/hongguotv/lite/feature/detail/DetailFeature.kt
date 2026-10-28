package com.hongguotv.lite.feature.detail

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.Navigator
import com.hongguotv.core.port.ProgressPort
import com.hongguotv.core.port.SettingsPort
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.ui.UiKit
import kotlinx.coroutines.CoroutineScope

/**
 * 详情类两页的入口（S5）：剧集详情（一季的集数清单）与收藏系列页（季线）。
 *
 * `app`（S7）按路由调 [showDetail] / [showSeries]；两页互斥，换目标即销毁重建——
 * 与目录切页同一口径，页面之间不维护自己的返回栈（返回语义在 `Navigator` 那边）。
 *
 * 状态归属：详情与季线都是端口背后的事实（上游 / 本地库），页面只在内存里留
 * "当前第几组集数、简介展开没展开"。依赖只到 `core.port` 与 `uikit`。
 */
class DetailFeature(
    val ui: UiKit,
    val content: ContentPort,
    val progress: ProgressPort,
    val favorites: FavoritesPort,
    val settings: SettingsPort,
    val navigator: Navigator,
    /** 主线程作用域，由 `app` 提供（`Dispatchers.Main`）。feature 不自建全局作用域。 */
    val scope: CoroutineScope,
) {

    private val container = FrameLayout(ui.context)
    private var page: DetailPage? = null

    /**
     * 每个详情类页面最后一次的光标 key（按页面标识隔离）。
     *
     * 页面换目标即销毁重建，焦点记忆跟着页面实例一起没了；但"从详情返回再进来"、
     * "翻到第二组季再返回"这些场景要求光标还在原处。与 `CatalogFeature.lastFocus` 同型：
     * **记忆归 feature，不归页面**。
     */
    internal val lastFocus: MutableMap<String, FocusKey> = HashMap()

    /** 容器。未调用 `show*` 时是空的（S7 每次都先 `show*` 再取视图）。 */
    fun view(): View = container

    /**
     * 剧集详情。[resumeRecording] 为 true（从历史 / 「继续看」进来）时，加载完直接起播，
     * 不渲染详情页——用户点"继续播放"要的是画面，不是又一层页面。
     */
    fun showDetail(seriesId: String, resumeRecording: Boolean = false) {
        swap { EpisodePage(this, seriesId, resumeRecording) }
    }

    /** 收藏系列页：按季键读季线。 */
    fun showSeries(seriesKey: String) {
        swap { SeasonPage(this, seriesKey) }
    }

    /** 离开详情（返回上一页或宿主销毁）：取消在途请求。 */
    fun release() {
        page?.release()
        page = null
    }

    // ---------- 进程重建后的状态恢复 ----------

    /**
     * 序列化跨进程死亡的状态：每个详情类页面最后的光标 key。
     * 详情页本身不序列化——它是一次性导航目标，进程死亡后回目录页；
     * 但"返回目录再进同一部剧，光标还在原处"的记忆要活下来。
     *
     * pageKey（`detail:<id>`）与光标 key（`detail:play`）都含冒号，
     * 不能拼成一个字符串——用嵌套 Bundle：pageKey 做 key、光标值做 value。
     */
    fun saveState(): Bundle = Bundle().apply {
        val focusKeys = Bundle()
        lastFocus.forEach { (pageKey, key) -> focusKeys.putString(pageKey, key.value) }
        putBundle(KEY_LAST_FOCUS, focusKeys)
    }

    /** 恢复 [saveState] 存下的状态；null（冷启动）或脏数据一律静默跳过。 */
    fun restoreState(state: Bundle?) {
        val focusKeys = state?.getBundle(KEY_LAST_FOCUS) ?: return
        focusKeys.keySet().forEach { pageKey ->
            focusKeys.getString(pageKey)?.let { lastFocus[pageKey] = FocusKey(it) }
        }
    }

    private companion object {
        const val KEY_LAST_FOCUS = "lastFocus"
    }

    private fun swap(create: () -> DetailPage) {
        page?.release()
        page = null
        container.removeAllViews()
        val created = create()
        page = created
        container.addView(
            created.view(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        created.build()
    }
}
