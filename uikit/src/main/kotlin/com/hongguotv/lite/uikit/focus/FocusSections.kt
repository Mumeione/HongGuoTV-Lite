package com.hongguotv.lite.uikit.focus

/**
 * 焦点段的组装。**空段整段丢弃**——[FocusSection] 不接受空列表，而空段会变成
 * "按进去出不来的隐形区域"（S3 定下的硬校验，S5 把它从某个 feature 里提到这里）。
 *
 * 单独成对象是为了让"顺序对不对、空段丢没丢"能被单测直接断言，不用起 Activity。
 */
object FocusSections {

    /** `prefix:0 … prefix:n-1`。 */
    fun keys(prefix: String, count: Int): List<FocusKey> = List(count) { FocusKey("$prefix:$it") }

    fun section(keys: List<FocusKey>, columns: Int = keys.size): FocusSection? =
        if (keys.isEmpty()) null else FocusSection(keys, columns.coerceAtLeast(1))

    /** 从上到下拼成焦点图；一个可聚焦项都没有时返回 null（页面还没内容）。[side] 是左侧栏（见 [FocusPlan]）。 */
    fun plan(sections: List<FocusSection?>, side: FocusSection? = null): FocusPlan? {
        val present = sections.filterNotNull()
        if (present.isEmpty()) return null
        return FocusPlan(present, side)
    }
}
