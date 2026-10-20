package com.hongguotv.lite.feature.detail

import android.view.View
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import com.hongguotv.lite.uikit.focus.FocusSections
import com.hongguotv.lite.uikit.focus.FocusSession
import com.hongguotv.lite.uikit.page.PageScaffold
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.ui.UiKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 一页的公共骨架：稳定树、页头、焦点会话、页面作用域。
 *
 * 生命周期：[DetailFeature] 换目标时销毁旧页（`release` 取消作用域）并新建新页；
 * 页内刷新只改文字与卡片内容，整棵树只在换页时重建。
 */
internal abstract class DetailPage(
    protected val host: DetailFeature,
    page: String,
) {

    protected val ui: UiKit = host.ui
    protected val tokens: Tokens = ui.tokens
    protected val scaffold: PageScaffold = ui.page()

    /** 页面作用域：换页/离开即取消。在途详情与季线请求都挂在这里。 */
    private val jobs = Job()
    protected val scope: CoroutineScope = CoroutineScope(host.scope.coroutineContext + jobs)

    protected val focus: FocusSession = scaffold.focusSession(page)

    fun view(): View = scaffold

    /** 搭树并装载首屏数据。只调用一次；各页在数据到手后自行提交焦点结构。 */
    abstract fun build()

    fun release() {
        jobs.cancel()
    }

    /**
     * 提交焦点结构。空的段（失败态的重试、单页时的分页行）整段丢弃；
     * 一个可聚焦项都没有时不动现有结构（页面还在加载）。
     */
    protected fun submitPlan(sections: List<FocusSection?>, views: Map<FocusKey, View>, preferred: FocusKey? = null) {
        val plan = FocusSections.plan(sections) ?: return
        focus.bind(plan, views)
        requestFocus(preferred)
    }

    private fun requestFocus(preferred: FocusKey?) {
        if (focus.request(preferred)) return
        // 视图还没挂上窗口时一定失败：等一次 post 再要，别让光标留在页外
        scaffold.post { focus.request(preferred) }
    }
}
