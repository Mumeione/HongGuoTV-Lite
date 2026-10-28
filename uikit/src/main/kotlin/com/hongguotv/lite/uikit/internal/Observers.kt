package com.hongguotv.lite.uikit.internal

import android.view.ViewTreeObserver

/**
 * 摘监听器前先判 [ViewTreeObserver.isAlive]。
 *
 * 窗口正在拆（或视图已死）时 `removeOn*Listener` 会抛 `IllegalStateException`，
 * 而这个调用点往往在 detach/收尾路径上——那里抛出去就是崩溃。
 *
 * 另有一条更根本的原因：`ViewTreeObserver` 是**窗口级共享**的，页面被 `removeAllViews`
 * 摘掉之后监听器仍留在窗口的 observer 上（它持有页面整棵树）。所以"注册与注销必须成对"
 * 是硬约束，这三处（焦点会话、页面骨架、滚动补偿）统一走这里，规则只写一份。
 */
internal fun ViewTreeObserver.removeFocusListenerIfAlive(
    listener: ViewTreeObserver.OnGlobalFocusChangeListener,
) {
    if (isAlive) removeOnGlobalFocusChangeListener(listener)
}

internal fun ViewTreeObserver.removeLayoutListenerIfAlive(
    listener: ViewTreeObserver.OnGlobalLayoutListener,
) {
    if (isAlive) removeOnGlobalLayoutListener(listener)
}
