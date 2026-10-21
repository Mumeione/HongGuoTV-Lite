package com.hongguotv.lite.uikit.focus

/**
 * 落焦阶梯：**"进页时光标放哪"从隐式变成显式。**
 *
 * S9 之前的回退链是「本次目标 → 本页记忆 → 焦点图第一项」，而焦点图第一项永远是顶栏的 `nav:0`。
 * 于是搜索完成、切 tab、页面重建之后，光标总是被扔回顶栏「搜索」——维护者的原话是
 * 「无论干什么焦点都会返回顶部搜索按钮」。问题不在回退链的写法，而在**没有人声明"这一页该落在哪"**。
 *
 * 现在每一页给出自己的阶梯（例如搜索结果页 = 第一张结果卡 → 搜索框），顺序固定为：
 *
 *     本次显式目标 → 本页记忆 → 页面阶梯 → 焦点图首项（最后兜底，只是别让光标留在页外）
 *
 * 纯函数、不碰 `View`：阶梯顺序能被 JVM 单测直接钉住。
 */
object FocusStart {

    /** 候选顺序（可能含不存在的 key，由调用方过滤）。 */
    fun candidates(
        preferred: FocusKey?,
        remembered: FocusKey?,
        ladder: List<FocusKey>,
        order: List<FocusKey>,
    ): List<FocusKey> {
        val result = ArrayList<FocusKey>(4 + ladder.size + order.size)
        preferred?.let { result += it }
        remembered?.let { result += it }
        result += ladder
        result += order
        return result
    }

    /**
     * 取第一个**确实有视图**的候选。
     *
     * @param available 有对应视图的 key（`views.keys`）
     * @return 选中的 key；一个都没有时返回 null，调用方不做任何事（页面还在加载）
     */
    fun pick(
        preferred: FocusKey?,
        remembered: FocusKey?,
        ladder: List<FocusKey>,
        available: Set<FocusKey>,
        order: List<FocusKey>,
    ): FocusKey? = candidates(preferred, remembered, ladder, order).firstOrNull { it in available }
}
