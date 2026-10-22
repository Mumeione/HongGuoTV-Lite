package com.hongguotv.lite.uikit.layout

/**
 * 网格几何：列数、卡宽、每行项数。纯算术，不碰 `View`。
 *
 * 旧实现把 6 与 `0.9f/6` 写死在渲染函数里，换个分辨率就得改代码。
 * 这里改成"由可用宽度除以目标卡宽得出列数"，上限仍是 6——保留现有六列海报的视觉，
 * 但那是一个令牌，不是一条算式里混着的魔法数。
 */
object GridGeometry {

    /**
     * 列数：夹在 [minColumns] 与 [maxColumns] 之间。可用宽度不足一列时取 [minColumns]。
     *
     * 按**四舍五入**取整而不是截断：B 方案内容区 800dp 上 5×149 + 4×14 = 801，
     * 只差 1dp——截断会掉到 4 列，与 mockup 的"一行 5 张"对不上；四舍五入取 5 列，
     * 实际卡宽 148，视觉等价。
     */
    fun columns(availableWidthPx: Int, targetCardWidthPx: Int, gapPx: Int, minColumns: Int, maxColumns: Int): Int {
        require(minColumns >= 1) { "最少列数至少为 1，实际 $minColumns" }
        require(maxColumns >= minColumns) { "最多列数不能小于最少列数（$maxColumns < $minColumns）" }
        require(targetCardWidthPx > 0) { "目标卡宽必须为正，实际 $targetCardWidthPx" }

        // 每列占 target + gap，最后一行末尾没有 gap。
        val perCard = targetCardWidthPx + gapPx
        if (perCard <= 0) return minColumns
        val fit = (availableWidthPx + gapPx).toFloat() / perCard
        return Math.round(fit).coerceIn(minColumns, maxColumns)
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

    /**
     * 聚光放大后卡片在**每一侧**超出自身边界的像素数。
     *
     * 缩放以中心为轴，所以每侧外扩 = 较长边 × (scale − 1) / 2。网格要按它留出内边距，
     * 否则首行/首列的焦点卡 3dp 白框会被自己的边界裁掉一条边（模拟器实测：
     * 只有右侧和下方看得见白框，左上被切平）。
     *
     * [scale] ≤ 1（低内存档关闭动效）时返回 0：不留无用的空白。
     */
    fun focusOverflow(cardWidthPx: Int, cardHeightPx: Int, scale: Float): Int {
        if (scale <= 1f) return 0
        val longer = maxOf(cardWidthPx, cardHeightPx)
        return kotlin.math.ceil(longer * (scale - 1f) / 2f).toInt()
    }
}
