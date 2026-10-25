package com.hongguotv.lite

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hongguotv.core.port.Navigator
import com.hongguotv.lite.feature.catalog.CatalogTab
import com.hongguotv.lite.nav.CatalogBack
import com.hongguotv.lite.nav.NavResult
import com.hongguotv.lite.nav.Route
import com.hongguotv.lite.nav.RouteStack
import com.hongguotv.lite.nav.backFromCatalog
import com.hongguotv.lite.uikit.focus.KeyRepeatGate

/**
 * 导航宿主：**唯一把六个页面和七个模块装在一起的地方**。
 *
 * 三件事，之外没有第四件：
 * 1. 拿 [RouteStack.top] 决定把哪个 feature 的根视图挂到窗口上（[render]）；
 * 2. 实现 [Navigator]——三个 feature 只会说"要去哪"，怎么去由这里定；
 * 3. 按键与生命周期：按键**先问播放页**，`onPause` 让播放器暂停，`onDestroy` 收摊。
 *
 * 三条 S6 留下的硬约束（改代码前先认这三条）：
 * - `dispatchKeyEvent` 必须先问 `player.onKeyEvent(event)`：焦点系统会把方向键吃掉，
 *   播放页在没有可聚焦项时正是靠那张按键表工作；
 * - 屏幕常亮由播放页自己管（`keepScreenOn` 挂在画面 View 上），宿主不碰 window flags；
 * - **离开播放页**（换所有者）时必须 `player.release()`（见 [release]），否则声音会跟着路由留在后台。
 */
class LiteActivity : Activity(), Navigator {

    private lateinit var graph: AppGraph
    private lateinit var root: FrameLayout

    /** 页面栈归宿主所有：`app` 是唯一的导航权威。 */
    private val stack = RouteStack(Route.Catalog)

    /**
     * 屏幕上已经画出来的那一路由——**渲染游标**，不是第二份导航状态：
     * 导航真值永远是 [stack.top]，所有 Navigator 方法都只经 [render] 改画面，
     * 两者由这里保持同步。它唯一的用途是回答"上一页是谁，该释放谁"。
     */
    private var painted: Route? = null

    /** 应用是否在前台。后台被路由推上来的播放页要立刻按住（不能让它在后台出声）。 */
    private var foreground = false

    // ---------- 启动 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        graph = AppGraph(this, this)
        root = FrameLayout(this).apply { setBackgroundColor(graph.ui.tokens.colors.background) }
        setContentView(root)
        // 首屏 = 目录页：`CatalogFeature` 构造时已经读过启动页偏好，`view()` 自己会搭出那一页
        render(Route.Catalog)
    }

    /**
     * 全屏（沉浸 + 隐藏导航栏）。用 `systemUiVisibility` 而不是 API 30 的
     * `WindowInsetsController`：后者在 API 23 的机器上直接崩。
     */
    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ---------- 导航（Navigator 的实现） ----------

    override fun toCatalog(tab: Int) {
        val requested = CatalogTab.of(tab)
        // 已经在目录页：只切页，不重画（重画会把刚建好的页和它的封面请求一起作废）
        if (stack.resetToCatalog() == NavResult.IGNORED) {
            graph.catalog.show(requested)
            return
        }
        render(Route.Catalog, catalogTab = requested)
    }

    override fun toDetail(seriesId: String, resumeRecording: Boolean) {
        if (stack.openDetail(seriesId, resumeRecording) == NavResult.MOVED) render(stack.top)
    }

    override fun toSeries(seriesKey: String) {
        if (stack.openSeries(seasonKey = seriesKey) == NavResult.MOVED) render(stack.top)
    }

    override fun toPlayer(seriesId: String, episodeIndex: Int, positionMs: Long) {
        if (stack.openPlayer(seriesId, episodeIndex, positionMs) == NavResult.MOVED) render(stack.top)
    }

    override fun back() {
        when (stack.back()) {
            NavResult.MOVED -> render(stack.top)
            NavResult.EXIT -> leaveApp()
            NavResult.IGNORED -> Unit
        }
    }

    /**
     * 栈底再按返回：不在搜索页就先回搜索页（旧 `goBack()` 的行为），
     * 已经在搜索页才真的退出应用。
     */
    private fun leaveApp() {
        when (backFromCatalog(graph.catalog.currentTab == CatalogTab.SEARCH)) {
            CatalogBack.TO_SEARCH -> render(Route.Catalog, force = true, catalogTab = CatalogTab.SEARCH)
            CatalogBack.EXIT -> finish()
        }
    }

    // ---------- 渲染 ----------

    /**
     * 把 [route] 对应的页面挂到窗口上。
     *
     * **换所有者才算"离开上一页"**：详情页与系列页同属 `DetailFeature`（它自己内部换页），
     * 它们之间切换不该走"释放再重建"。
     *
     * [catalogTab] 只在"进目录页时要点名看哪一页"时给（启动之外的 `toCatalog` / 回搜索页）。
     * 必须在这里给、不能先 `show` 再 `render`：`render` 会作废旧页的封面请求（代际号 +1），
     * 先建页再作废的话，那一页的封面就全被丢掉了。
     */
    private fun render(route: Route, force: Boolean = false, catalogTab: CatalogTab? = null) {
        val previous = painted
        if (!force && previous == route && catalogTab == null) return
        painted = route
        if (previous != null && (force || ownerOf(previous) !== ownerOf(route))) {
            release(previous)
            // 旧页的封面请求全部作废：翻页之后没人要它们了（内存缓存留着，回来时秒出）
            graph.artwork.cancelPage(clearMemory = false)
        }
        when (route) {
            Route.Catalog -> {
                catalogTab?.let { graph.catalog.show(it) }
                attach(graph.catalog.view())
            }
            is Route.Detail -> {
                graph.detail.showDetail(route.seriesId, route.autoplay)
                attach(graph.detail.view())
            }
            is Route.Series -> {
                graph.detail.showSeries(route.seasonKey)
                attach(graph.detail.view())
            }
            is Route.Player -> {
                graph.player.show(route.seriesId, route.episodeIndex, route.positionMs)
                attach(graph.player.view())
                // 在后台被路由推上来的播放页：立刻按住，流回来也不能开播
                if (!foreground) graph.player.pause()
            }
        }
    }

    private fun ownerOf(route: Route): Any = when (route) {
        Route.Catalog -> graph.catalog
        is Route.Detail, is Route.Series -> graph.detail
        is Route.Player -> graph.player
    }

    private fun release(route: Route) {
        when (route) {
            Route.Catalog -> graph.catalog.release()
            is Route.Detail, is Route.Series -> graph.detail.release()
            is Route.Player -> graph.player.release()
        }
    }

    /**
     * 挂根视图。**已经在窗口上的容器不再动一次**：`removeAllViews` 会把界面上整个子树摘下来
     * 再挂回去，焦点会因此丢掉（页内换页时容器本来就没换过）。
     */
    private fun attach(view: View) {
        if (view.parent === root) return
        root.removeAllViews()
        root.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    // ---------- 按键 ----------

    /**
     * 长按方向键的重复闸门（原仓库 `KeyRepeatGate(110)` 口径）：系统连发约 50ms 一格，
     * 焦点格（详情分集、播放选集、目录网格）会"一按飞过一行"。首次按下不受影响，
     * 只有长按的连发被节流到 [FOCUS_REPEAT_GATE_MS] 一格。
     */
    private val focusRepeatGate = KeyRepeatGate(FOCUS_REPEAT_GATE_MS)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 播放页先看：焦点系统会把方向键吃掉，而播放页在"没有可聚焦项"时正是靠按键表工作
        if (painted is Route.Player && graph.player.onKeyEvent(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in MOVE_KEY_CODES) {
            // 首次按下重置闸门：换一个方向按，上一轮的节流节奏不该带过来
            if (event.repeatCount == 0) focusRepeatGate.reset()
            else if (!focusRepeatGate.accept(SystemClock.uptimeMillis())) return true
        }
        // 目录页接 MENU（B 方案：历史页"管理走 MENU"）；只认无重复的按下，长按不连发
        if (painted is Route.Catalog &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            event.keyCode == KeyEvent.KEYCODE_MENU &&
            graph.catalog.onMenuKey()
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 返回键。走到这里说明焦点系统与输入法都没消费它——所以"搜索框打字时先收键盘"
     * 是系统给的，不需要自己判断。清单里的 `enableOnBackInvokedCallback="false"` 保证
     * 新系统上也仍然走这个方法。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        back()
    }

    // ---------- 生命周期 ----------

    override fun onResume() {
        super.onResume()
        foreground = true
        // 恢复心跳（定时停止、等联网重试）。**不自动续播**：用户回来自己按一下。
        graph.player.resume()
    }

    override fun onPause() {
        // 先落旗再暂停：`pause()` 之后若还有协程把播放页推上来，`render` 会据此按住它
        foreground = false
        super.onPause()
        // 退到后台：暂停 + 保存进度。画面与位置保留，回前台还是原位（不在 onStop 释放，见规格）
        graph.player.pause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::graph.isInitialized) graph.artwork.trim(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val top = painted ?: return
        // 播放页不重建：media3 自己处理画面尺寸，重建会把播放打回起播点
        if (top is Route.Player) return
        render(top, force = true)
    }

    override fun onDestroy() {
        if (::graph.isInitialized) {
            graph.player.release()
            graph.close()
        }
        super.onDestroy()
    }

    private companion object {
        /** 长按方向键的重复间隔（原仓库详情页 `KeyRepeatGate(110)` 口径）。 */
        const val FOCUS_REPEAT_GATE_MS = 110L

        /** 需要节流的四个方向键（遥控器导航键）。 */
        val MOVE_KEY_CODES = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
