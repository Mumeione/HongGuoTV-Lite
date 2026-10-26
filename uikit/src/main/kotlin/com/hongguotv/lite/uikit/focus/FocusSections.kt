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

    fun section(
        keys: List<FocusKey>,
        columns: Int = keys.size,
        down: FocusKey? = null,
        up: FocusKey? = null,
        flanked: Boolean = false,
    ): FocusSection? =
        if (keys.isEmpty()) null else FocusSection(keys, columns.coerceAtLeast(1), down = down, up = up, flanked = flanked)

    /**
     * 带空槽的一段：禁用的按钮**保留位置**、不参与聚焦（季线页第一页的「上一组」）。
     *
     * 与 [section] 的区别只有这一点：空槽会占掉它那一列，让"从翻组键往下"仍然按
     * 真实列号映射到对应的卡，而不是因为少了一项就整体左移。
     */
    fun slots(slots: List<FocusKey?>): FocusSection? =
        if (slots.all { it == null }) null else FocusSection(slots, slots.size)

    /**
     * 从上到下拼成焦点图；一个可聚焦项都没有时返回 null（页面还没内容）。
     *
     * [side] 是左侧栏，[sideAnchor] 是"内容往左回侧栏时落在哪一项"（通常是当前 tab）。
     * 目录页的内容只有一行卡，不给锚点就会落到侧栏中段——用户按左键想回自己的 tab，
     * 却被带去了别的页面（模拟器实测）。
     */
    fun plan(
        sections: List<FocusSection?>,
        side: FocusSection? = null,
        sideAnchor: FocusKey? = null,
        leftFlank: FocusSection? = null,
        rightFlank: FocusSection? = null,
    ): FocusPlan? {
        val present = sections.filterNotNull()
        if (present.isEmpty()) return null
        return FocusPlan(present, side, sideAnchor, leftFlank, rightFlank)
    }
}
