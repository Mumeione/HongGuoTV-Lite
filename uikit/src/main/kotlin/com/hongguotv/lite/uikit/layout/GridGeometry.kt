package com.hongguotv.lite.uikit.layout

/**
 * 网格几何：列数、卡宽、每行项数。纯算术，不碰 `View`。
 *
 * 旧实现把 6 与 `0.9f/6` 写死在渲染函数里，换个分辨率就得改代码。
 * 这里改成"由可用宽度除以目标卡宽得出列数"，上限仍是 6——保留现有六列海报的视觉，
 * 但那是一个令牌，不是一条算式里混着的魔法数。
 */
object GridGeometry {

    /** 列数：夹在 [minColumns] 与 [maxColumns] 之间。可用宽度不足一列时取 [minColumns]。 */
    fun columns(availableWidthPx: Int, targetCardWidthPx: Int, gapPx: Int, minColumns: Int, maxColumns: Int): Int {
        require(minColumns >= 1) { "最少列数至少为 1，实际 $minColumns" }
        require(maxColumns >= minColumns) { "最多列数不能小于最少列数（$maxColumns < $minColumns）" }
        require(targetCardWidthPx > 0) { "目标卡宽必须为正，实际 $targetCardWidthPx" }

        // 每列占 target + gap，最后一行末尾没有 gap。
        val perCard = targetCardWidthPx + gapPx
        if (perCard <= 0) return minColumns
        val fit = (availableWidthPx + gapPx) / perCard
        return fit.coerceIn(minColumns, maxColumns)
    }

    /** 卡宽：可用宽度扣掉列间距后均分。结果至少为 1，避免宽度为 0 的视图。 */
    fun cardWidth(availableWidthPx: Int, columns: Int, gapPx: Int): Int {
        require(columns >= 1) { "列数至少为 1，实际 $columns" }
        val width = (availableWidthPx - gapPx * (columns - 1)) / columns
        return width.coerceAtLeast(1)
    }

    /** 每行的项数，末行可以不满。空列表返回空。 */
    fun rowsOf(itemCount: Int, columns: Int): List<Int> {
        require(columns >= 1) { "列数至少为 1，实际 $columns" }
        if (itemCount <= 0) return emptyList()
        val full = itemCount / columns
        val remainder = itemCount % columns
        val rows = ArrayList<Int>(full + if (remainder > 0) 1 else 0)
        repeat(full) { rows += columns }
        if (remainder > 0) rows += remainder
        return rows
    }
}
