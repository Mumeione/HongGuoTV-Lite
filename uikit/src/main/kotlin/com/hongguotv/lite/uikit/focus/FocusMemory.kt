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
