package com.hongguotv.lite.uikit.focus

import android.util.Log
import android.view.View
import com.hongguotv.lite.uikit.internal.removeLayoutListenerIfAlive

/** 与 data 层 [com.hongguotv.lite.data.diagnostics.LogcatDiagnostics] 同名：`logcat -s HongGuoTV` 一把抓。 */
private const val TAG = "HongGuoTV"

/**
 * 一帧防抖：同一帧内提交的多次请求**只执行最后一次**，此前未执行的作废。
 *
 * 焦点"路过"某个视图再被改走时（框架重试、程序性重挂），中间态不该触发页面副作用——
 * 页面的信息栏更新挂在落定回调上，就是靠它把中间态滤掉。
 *
 * 用泛型执行器而不是 `Handler`：防抖本身是纯逻辑，要在 JVM 单测里钉死。
 */
internal class SettleDebounce(private val post: (Runnable) -> Unit) {

    private var generation = 0

    /** 提交一次请求；返回后 [action] 只会以"最后一次提交"的身份执行至多一次。 */
    fun submit(action: () -> Unit) {
        val generation = ++this.generation
        post(Runnable { if (generation == this.generation) action() })
    }
}

/**
 * 一页的焦点会话：把"声明焦点结构、绑定邻居、记住光标、恢复光标"这四件事串起来，
 * 让 feature 不用每页重写一遍（旧实现的 44 处 `nextFocus*` 就是每页重写的结果）。
 *
 * 用法：
 * 1. 页面拿到 [FocusSession]，用 [bind] 提交焦点结构（每次重建视图后重新提交）；
 * 2. 页面根视图的全局焦点变化回调里调 [onFocusChanged]（[com.hongguotv.lite.uikit.page.PageScaffold]
 *    已经接好了，feature 不需要自己接）；
 * 3. 需要把光标放回本页时调 [request]。
 *
 * 记忆按 [page] 隔离：目录页的"收藏"和"历史"互不影响。
 */
class FocusSession(
    private val page: String,
    private val memory: FocusMemory = FocusMemory(),
    /**
     * 页面根视图。给了它、且 [bind] 声明 `visual = true` 时，物理按键的落点按**视图位置**
     * 重新绑定（见 [FocusBinder.bindVisual]）——视觉上的上下左右就是焦点移动的上下左右。
     * 不给时只走焦点图（JVM 单测用）。
     */
    private val root: View? = null,
    /**
     * 落定回调的执行器（一帧防抖）：页面骨架传 `scaffold::post`。
     * 不给时 [onSettled] 同步执行（无防抖）——调用方自己保证没有中间态。
     */
    post: ((Runnable) -> Unit)? = null,
) {

    private val debounce = post?.let(::SettleDebounce)

    private var graph: FocusGraph? = null
    private var views: Map<FocusKey, View> = emptyMap()
    private var keyById: Map<Int, FocusKey> = emptyMap()

    /** 视觉绑定的代际：同一帧里连续 bind 时只让最后一次生效（旧视图树已经拆了）。 */
    private var visualGeneration = 0

    /** 还没等到布局的那次视觉绑定的监听器：下一次 bind 前先摘掉，避免翻组时堆积。 */
    private var pendingLayout: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    /**
     * 焦点**落定**回调（一帧防抖）：焦点不再变化时才触发，[onFocusChanged] 的每次
     * "路过"都被滤掉。
     *
     * 页面的副作用（底部信息栏更新）挂这里而不是"卡片获得焦点"上：焦点从卡片 A 挪到
     * 卡片 B 再挪走的一次按键里，"获得焦点"会触发两次、信息栏停在 A 上——落定回调
     * 只说最终答案。
     *
     * [key] 是最终光标（本页焦点图里的项）；焦点落到图外（窗口切换等）时回调不触发。
     */
    var onSettled: ((FocusKey) -> Unit)? = null

    /** 本页当前的焦点结构。还没 [bind] 时为 null。 */
    val graphOrNull: FocusGraph? get() = graph

    /**
     * 提交焦点结构并绑定邻居关系。返回图本身，便于调用方查 key 是否存在。
     * @param views key → 视图；只有这里出现的 key 才能被真正聚焦。
     */
    fun bind(plan: FocusPlan, views: Map<FocusKey, View>, visual: Boolean = false): FocusGraph =
        bind(plan, views, visual, FocusExits.NONE)

    /**
     * 同上，另带视觉绑定的显式出口。
     *
     * 单独成重载而不是继续加默认参数：跨模块调用点只认旧的 `bind$default` 描述符，
     * 加默认参数会改变 JVM 签名，增量构建里没重编的模块会在运行时 `NoSuchMethodError`。
     *
     * @param visualExits 只在 [visual] 时生效（详情页动作列按左回页头返回）。
     */
    fun bind(
        plan: FocusPlan,
        views: Map<FocusKey, View>,
        visual: Boolean,
        visualExits: FocusExits,
    ): FocusGraph {
        val built = FocusGraph.of(plan)
        // FocusGraph 不引用 android.*（JVM 单测约束），脏数据留痕由这里补
        if (built.duplicates.isNotEmpty()) {
            Log.w(
                TAG,
                "focus.duplicateKeys " + built.duplicates.joinToString(",") { it.value } + "（先声明者保留）",
            )
        }
        FocusBinder.bind(built, views)
        graph = built
        this.views = views
        keyById = views.entries.associate { (key, view) -> view.id to key }
        // 重建/刷新时视图树没换焦点（光标本来就在原位），也就不会有焦点变化事件：
        // 页面的信息栏要在这里补说一次"现在停在哪"
        settle()
        if (visual) scheduleVisualBind(views, visualExits, built, plan.side?.keys.orEmpty().toSet())
        return built
    }

    /**
     * 布局完成后再按真实位置改写方向键落点。
     *
     * 不能同步做：`bind` 往往发生在视图刚 addView、还没 measure/layout 的时候，
     * 量到的矩形是 0；而且这一刻的宽度也不是最终宽度（比如 weight 还没分配）。
     */
    private fun scheduleVisualBind(
        bound: Map<FocusKey, View>,
        exits: FocusExits,
        graph: FocusGraph,
        sideKeys: Set<FocusKey>,
    ) {
        val anchor = root ?: return
        val generation = ++visualGeneration
        anchor.post {
            if (generation == visualGeneration) FocusBinder.bindVisual(anchor, bound, exits, graph, sideKeys)
        }
        // 首次建页时 post 可能跑在第一次 measure 之前：布局完成后再补一次。
        // 上一轮还没等到布局的监听器先摘掉，否则翻组时每次 bind 都会挂一个
        // （observer 可能已经死了，remove 会抛 IllegalStateException，见 helper）。
        pendingLayout?.let { pending -> anchor.viewTreeObserver.removeLayoutListenerIfAlive(pending) }
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                anchor.viewTreeObserver.removeLayoutListenerIfAlive(this)
                if (pendingLayout === this) pendingLayout = null
                if (generation == visualGeneration) FocusBinder.bindVisual(anchor, bound, exits, graph, sideKeys)
            }
        }
        pendingLayout = listener
        anchor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /**
     * 视图树里任意焦点变化都记到本页（由页面的全局焦点监听调用）。
     *
     * **只有用户主动移动才记**：旧焦点视图已经不可用（被移除、收起、禁用、脱离窗口）时，
     * 框架会自己把焦点交给页面里第一个可聚焦项——通常是侧栏。那次变化不是用户选的，
     * 记下来就会变成「任何操作之后光标都停在侧栏」：数据刷新把卡片重建一遍、空态把列表收起来，
     * 记忆就指向侧栏了。
     *
     * 「被移除」不能只看 `isShown`：卡片先从行容器里摘掉的那一瞬间 `isShown` 还可能为 true，
     * 所以同时看父容器与窗口令牌（[FocusCapture]）。
     *
     * 被动落焦仍然会走落定回调：信息栏要跟着屏幕上的最终光标走，只是不写进记忆。
     *
     * @param oldFocus 变化前的焦点视图。
     */
    fun onFocusChanged(oldFocus: View?, newFocus: View?) {
        val key = newFocus?.let { keyById[it.id] } ?: return
        val userMove = FocusCapture.active(
            oldFocusShown = oldFocus?.isShown == true,
            oldFocusAttached = oldFocus?.parent != null && oldFocus.windowToken != null,
            oldFocusEnabled = oldFocus?.isEnabled == true,
        )
        if (userMove) memory.remember(page, key)
        scheduleSettled(key)
    }

    /** 落定回调：有防抖器时过一帧，没有时同步执行。 */
    private fun scheduleSettled(key: FocusKey) {
        val callback = onSettled ?: return
        val debounce = debounce
        if (debounce == null) {
            callback(key)
        } else {
            debounce.submit { callback(key) }
        }
    }

    /**
     * 让当前光标重新"落定"一次（走防抖）。
     *
     * **程序性要焦点不产生焦点变化事件**：冷启动的首次 [request]、从详情页返回时把光标
     * 放回原来的卡、刷新后视图复用——这些情况下 `onSettled` 一次都不会触发，页面挂在上面的
     * 副作用（底部信息栏）就停在上一页的状态：实测「首页进来没有信息栏」「返回收藏页后
     * 信息栏整条消失」。
     *
     * 只在视图树里真的有一项持焦时回调；焦点还在页外（窗口切换）时什么都不做。
     */
    fun settle() {
        val key = views.entries.firstOrNull { it.value.isFocused }?.key ?: return
        scheduleSettled(key)
    }

    /** 本页上次的焦点 key。 */
    fun remembered(): FocusKey? = memory.recall(page)

    /**
     * 本页当前是否有视图持焦。
     *
     * 页面的"刷新不抢焦"判定读它（详情两页把规则写成纯函数 `shouldRequest`：
     * 显式要落点，或本页根本没人持焦，才 [request]）。这里只提供事实，不提供策略——
     * 策略一旦有第二个实现，就会出现"一处刷新抢焦、一处不抢"。
     */
    fun hasFocusInPage(): Boolean = views.any { it.value.isFocused }

    /**
     * 把光标放回本页：显式目标 → 本页记忆 → 页面阶梯（[ladder]）→ 焦点图首项。
     *
     * [ladder] 由页面声明（例如"第一张结果卡 → 搜索框"），见 [FocusStart]。
     * @return 是否成功（视图还没挂上窗口时会失败，调用方 `post` 一次即可）
     */
    fun request(preferred: FocusKey? = null, ladder: List<FocusKey> = emptyList()): Boolean {
        val currentGraph = graph ?: return false
        val ok = FocusBinder.request(
            graph = currentGraph,
            views = views,
            preferred = preferred,
            remembered = remembered(),
            ladder = ladder,
        )
        // 目标是"已经持焦"的那一项时 requestFocus 是空操作，不会有焦点变化事件：
        // 返回本页、切回 tab 都属于这种，补说一次落定，信息栏才不会停在别的页
        if (ok) settle()
        return ok
    }
}
