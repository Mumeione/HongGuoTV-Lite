package com.hongguotv.lite.uikit.focus

import kotlin.math.abs

/**
 * 视觉方向导航（2026-10-24 验收口径）：**屏幕上的上下左右，就是焦点移动的上下左右**。
 *
 * 焦点图（[FocusGraph]）描述的是"段内怎么走、段间怎么跨"，它对"返回键在左上角、继续播放
 * 在右上角"这种布局是无能为力的：两段之间只有上下关系，按右键只能夹住自己。维护者实测
 * "返回按右键应该去继续播放"就是这条缝。
 *
 * 生产落点只走 [pickVisual]：显式出口 → 45° 视锥内最近的内容项 → 焦点图的语义落点。
 * 几何只在锥内挑，**不再回退到"全方向最近"**——那条回退会让行首按左掉进侧栏、
 * 返回按下扎进分集格，跑到屏幕另一头。锥内确实没有时，焦点图本来就有正确答案
 * （侧栏锚点、侧翼长条或自身），没有答案才夹住自己。
 *
 * 只依赖 [FocusRect]，不依赖 `android.view.View`，单测可以直接喂矩形。
 */
data class FocusRect(
    val key: FocusKey,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

enum class FocusDirection { LEFT, RIGHT, UP, DOWN }

/**
 * 视觉方向绑定的**显式出口**：声明"这些 key 按某个方向固定落到哪"。
 *
 * 两种已知用法：
 * - 详情两页动作列按左：左边是封面与简介（不可聚焦），按矩形算出来的最近项却是侧栏；
 * - 季线页季卡按上 / 「排序」按下：按上会落到排序键或右侧动作列，取决于列位置。
 *
 * 出口只在目标确实在屏上时生效；目标不在（返回键被收起、季线还没到）仍按几何算，
 * 不会把光标扔到不存在的视图上。
 */
class FocusExits private constructor(
    private val targets: Map<FocusDirection, Map<FocusKey, FocusKey>>,
) {

    /** [key] 在该方向上的固定落点；没声明返回 null（按几何算）。 */
    fun target(key: FocusKey, direction: FocusDirection): FocusKey? =
        targets[direction]?.get(key)

    /** 按左的固定落点；没声明返回 null。 */
    fun left(key: FocusKey): FocusKey? = target(key, FocusDirection.LEFT)

    /** 合并两份声明（同方向同 key 以后者为准），用于"左出口 + 上下出口"共存。 */
    operator fun plus(other: FocusExits): FocusExits {
        val merged = HashMap<FocusDirection, Map<FocusKey, FocusKey>>(targets)
        other.targets.forEach { (direction, rows) -> merged[direction] = merged[direction].orEmpty() + rows }
        return FocusExits(merged)
    }

    companion object {
        val NONE = FocusExits(emptyMap())

        /** [keys] 按左一律回 [target]（详情两页动作列 → 页头返回）。 */
        fun left(target: FocusKey, keys: List<FocusKey>): FocusExits =
            build(FocusDirection.LEFT, target, keys)

        /** [keys] 按上一律回 [target]（季卡 → 「排序」）。 */
        fun up(target: FocusKey, keys: List<FocusKey>): FocusExits =
            build(FocusDirection.UP, target, keys)

        /** [keys] 按下一律回 [target]（「排序」→ 第一张季卡）。 */
        fun down(target: FocusKey, keys: List<FocusKey>): FocusExits =
            build(FocusDirection.DOWN, target, keys)

        /** [keys] 按右一律回 [target]（单键行右缘夹住自己）。 */
        fun right(target: FocusKey, keys: List<FocusKey>): FocusExits =
            build(FocusDirection.RIGHT, target, keys)

        /** [keys] 各自按左夹住自己（网格行首没有左侧长条时）。 */
        fun clampLeft(keys: List<FocusKey>): FocusExits = clamp(FocusDirection.LEFT, keys)

        /** [keys] 各自按右夹住自己（网格行尾没有右侧长条时）。 */
        fun clampRight(keys: List<FocusKey>): FocusExits = clamp(FocusDirection.RIGHT, keys)

        private fun clamp(direction: FocusDirection, keys: List<FocusKey>): FocusExits =
            keys.fold(NONE) { exits, key -> exits + build(direction, key, listOf(key)) }

        private fun build(direction: FocusDirection, target: FocusKey, keys: List<FocusKey>): FocusExits =
            if (keys.isEmpty()) NONE else FocusExits(mapOf(direction to keys.associateWith { target }))
    }
}

object VisualFocus {

    /** 候选是否落在该方向的 45° 视锥里（用矩形中心算，与打分同一套坐标）。 */
    internal fun inCone(self: FocusRect, other: FocusRect, direction: FocusDirection): Boolean {
        val dx = abs(other.centerX - self.centerX)
        val dy = abs(other.centerY - self.centerY)
        return when (direction) {
            FocusDirection.LEFT, FocusDirection.RIGHT -> dy <= dx
            FocusDirection.UP, FocusDirection.DOWN -> dx <= dy
        }
    }

    /**
     * 视觉绑定的一条方向落点：**显式出口 → 内容区视锥几何 → 焦点图语义落点**。
     *
     * 侧栏（[sideKeys]）不参与内容项的几何候选：分集格/季卡按左时不会因为"最近的项是
     * 设置按钮"而掉进左侧栏。内容区在 45° 视锥里没有候选时不再退回"全方向最近"——
     * 那种远跳正是"返回按左跑到搜索、按下一头扎进分集格"的来源；改为回到 [semantic]
     * （焦点图算出的侧栏锚点、侧翼或自身）。页头按左因此进当前 tab，行缘则夹住自己。
     *
     * [all] 是屏上真实可见的矩形；出口与语义目标都必须真的在屏上，否则继续按几何算。
     */
    fun pickVisual(
        self: FocusRect,
        all: List<FocusRect>,
        direction: FocusDirection,
        exits: FocusExits = FocusExits.NONE,
        sideKeys: Set<FocusKey> = emptySet(),
        semantic: FocusKey? = null,
    ): FocusKey? {
        exits.target(self.key, direction)?.takeIf { target -> all.any { it.key == target } }?.let { return it }
        val candidates = if (self.key in sideKeys) all else all.filter { it.key !in sideKeys }
        coneNeighbor(self, candidates, direction)?.let { return it }
        return semantic?.takeIf { target -> all.any { it.key == target } }
    }

    /**
     * 只在该方向的 45° 视锥里挑最近候选；锥内没有就返回 null。
     *
     * 视锥把"正前方"排在"斜对角"前：详情页「返回」的斜下方紧挨着第一行集数格，
     * 但视觉上的右边是动作列。锥内没有候选时不回退到全方向最近，由 [pickVisual]
     * 改走焦点图的语义落点。
     */
    fun coneNeighbor(
        self: FocusRect,
        candidates: List<FocusRect>,
        direction: FocusDirection,
    ): FocusKey? =
        candidates.asSequence()
            .filter { it.key != self.key }
            .mapNotNull { other ->
                score(self, other, direction)?.let { Scored(other.key, it, inCone(self, other, direction)) }
            }
            .filter { it.inCone }
            .minByOrNull { it.score }
            ?.key

    /** 候选的打分结果：key、方向分、是否在视锥内。 */
    private data class Scored(val key: FocusKey, val score: Int, val inCone: Boolean)

    /**
     * 方向分：越小越优先；不在该方向上返回 null。
     *
     * - **轴向距离**（两个矩形之间的空档）占大头：紧挨着的项先被选中；
     * - **错位**：有重叠就按中心差的一半算（同一行/同一列最优先），没有重叠就按空档 ×4
     *   ——长条翻组键是竖条，和它同高的行应该先落上去；
     * - 完全错开的候选加一笔固定惩罚：宁可去远处但同一行的项，也不跳到一个斜下方很近的项。
     */
    fun score(self: FocusRect, other: FocusRect, direction: FocusDirection): Int? {
        val forward = when (direction) {
            FocusDirection.RIGHT -> other.centerX - self.centerX
            FocusDirection.LEFT -> self.centerX - other.centerX
            FocusDirection.DOWN -> other.centerY - self.centerY
            FocusDirection.UP -> self.centerY - other.centerY
        }
        if (forward <= 0) return null

        val horizontal = direction == FocusDirection.LEFT || direction == FocusDirection.RIGHT
        val axial = when (direction) {
            FocusDirection.RIGHT -> (other.left - self.right).coerceAtLeast(0)
            FocusDirection.LEFT -> (self.left - other.right).coerceAtLeast(0)
            FocusDirection.DOWN -> (other.top - self.bottom).coerceAtLeast(0)
            FocusDirection.UP -> (self.top - other.bottom).coerceAtLeast(0)
        }
        val overlap = if (horizontal) {
            minOf(self.bottom, other.bottom) - maxOf(self.top, other.top)
        } else {
            minOf(self.right, other.right) - maxOf(self.left, other.left)
        }
        val perpendicular = if (overlap > 0) {
            if (horizontal) abs(other.centerY - self.centerY) / 2 else abs(other.centerX - self.centerX) / 2
        } else {
            val gap = if (horizontal) {
                maxOf(self.top, other.top) - minOf(self.bottom, other.bottom)
            } else {
                maxOf(self.left, other.left) - minOf(self.right, other.right)
            }
            gap.coerceAtLeast(0) * 4 + NO_OVERLAP_PENALTY
        }
        return axial * 2 + perpendicular
    }

    /**
     * 完全错开的候选的固定惩罚。
     *
     * 取 400：在 1080p 上约等于 200px 的轴向距离，足以让"同一行的近邻"赢过
     * "斜下方稍近一点"的项，又不会把正常的跨行移动挡掉。
     */
    const val NO_OVERLAP_PENALTY = 400
}
