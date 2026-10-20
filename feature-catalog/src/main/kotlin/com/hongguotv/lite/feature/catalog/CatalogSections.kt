package com.hongguotv.lite.feature.catalog

import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusPlan
import com.hongguotv.lite.uikit.focus.FocusSection

/**
 * 焦点段的组装。**空段整段丢弃**——`FocusSection` 不接受空列表，空段会变成
 * "按进去出不来的隐形区域"（S3 的坑 4）。
 *
 * 单独成对象是为了让"顺序对不对、空段丢没丢"能被单测直接断言，不用起 Activity。
 */
internal object CatalogSections {

    /** `prefix:0 … prefix:n-1`。 */
    fun keys(prefix: String, count: Int): List<FocusKey> = List(count) { FocusKey("$prefix:$it") }

    fun section(keys: List<FocusKey>, columns: Int = keys.size): FocusSection? =
        if (keys.isEmpty()) null else FocusSection(keys, columns.coerceAtLeast(1))

    /** 从上到下拼成焦点图；一个可聚焦项都没有时返回 null（页面还没内容）。 */
    fun plan(sections: List<FocusSection?>): FocusPlan? {
        val present = sections.filterNotNull()
        return if (present.isEmpty()) null else FocusPlan(present)
    }
}

/**
 * 两步确认：第一次按下只进入确认态，同一个键再按才真的执行。
 *
 * S4 不用模态框（那是 uikit 还没交付的积木），破坏性操作靠这个：焦点移开即撤销，
 * 手滑多按一次不会删掉进度。
 */
internal class TwoStepConfirm {

    private var armed: String? = null

    fun isArmed(key: String): Boolean = armed == key

    /** @return true = 本次按下就是确认，调用方可以执行了 */
    fun press(key: String): Boolean {
        if (armed == key) {
            armed = null
            return true
        }
        armed = key
        return false
    }

    fun disarm() {
        armed = null
    }
}
