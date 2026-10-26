package com.hongguotv.lite.uikit.focus

/**
 * 焦点记忆：每个页面记一个 key，离开再回来时按它恢复。
 *
 * 只记 key 不记下标——下标在数据变化后指向别的东西，key 不会。
 * 页面之间的隔离靠 [page] 字符串（约定用页面标识 + 查询条件，例如 `"catalog:收藏"`）。
 */
class FocusMemory {
    private val pages = mutableMapOf<String, FocusKey>()

    fun remember(page: String, key: FocusKey?) {
        if (key == null) pages.remove(page) else pages[page] = key
    }

    fun recall(page: String): FocusKey? = pages[page]

    fun forget(page: String) {
        pages.remove(page)
    }

    fun clear() {
        pages.clear()
    }
}

/**
 * 焦点记忆的收录条件：**只有用户主动移动光标的那一步**才写进记忆。
 *
 * 旧焦点视图不可用（被移除、收起、禁用）时，框架会自己把焦点交给页面里第一个可聚焦项；
 * 冷启动的自动落焦同理。这些被动落焦不是用户的选择，记下来会让之后按记忆恢复时
 * 把光标钉在意外位置（列表重建后落到侧栏就是这么来的）。
 */
internal object FocusCapture {

    fun active(
        oldFocusShown: Boolean,
        oldFocusAttached: Boolean,
        oldFocusEnabled: Boolean,
    ): Boolean = oldFocusShown && oldFocusAttached && oldFocusEnabled
}

/**
 * 长按节流阀。遥控器在长按方向键时会以 ~50ms 的间隔重复事件，
 * 比人手移动更快，横穿一行会一次跳过好几项。
 *
 * 语义：**首次按下永远接受**（单击不能丢），重复事件仅在距上次接受超过 [minIntervalMs] 时接受。
 * 调用方在收到 `repeatCount == 0` 的事件时先 [reset]，再 [accept]。
 */
class KeyRepeatGate(private val minIntervalMs: Long) {
    init { require(minIntervalMs >= 0) { "节流间隔不能为负：$minIntervalMs" } }

    private var acceptedAt = Long.MIN_VALUE

    fun reset() {
        acceptedAt = Long.MIN_VALUE
    }

    fun accept(nowMs: Long): Boolean {
        if (acceptedAt != Long.MIN_VALUE && nowMs - acceptedAt < minIntervalMs) return false
        acceptedAt = nowMs
        return true
    }
}
