package com.hongguotv.lite.uikit.focus

import kotlin.math.abs

/**
 * 视觉方向导航（2026-10-24 验收口径）：**屏幕上的上下左右，就是焦点移动的上下左右**。
 *
 * 焦点图（[FocusGraph]）描述的是"段内怎么走、段间怎么跨"，它对"返回键在左上角、继续播放
 * 在右上角"这种布局是无能为力的：两段之间只有上下关系，按右键只能夹住自己。维护者实测
 * "返回按右键应该去继续播放"就是这条缝。
 *
 * 这里用真实的视图矩形算落点：先筛出该方向上的候选（中心在该方向、且确实更靠那边），
 * 再按"轴向距离 + 垂直/水平错位"排序。错位越小越优先，所以同一行的按钮会先于更远的行。
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

object VisualFocus {

    /** 在该方向上离 [self] 最近的候选；没有就返回 null（调用方夹住自己）。 */
    fun neighbor(self: FocusRect, candidates: List<FocusRect>, direction: FocusDirection): FocusKey? =
        candidates.asSequence()
            .filter { it.key != self.key }
            .mapNotNull { other -> score(self, other, direction)?.let { other.key to it } }
            .minByOrNull { it.second }
            ?.first

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
