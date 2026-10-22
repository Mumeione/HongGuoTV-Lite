package com.hongguotv.lite.uikit.focus

/**
 * 焦点图：把"按键之后光标落到哪"从 44 处手写的 `nextFocus*Id` 收成一份声明加一个纯函数。
 *
 * 为什么用语义 key 而不是 View id：旧实现每次重建页面都给 View 换新 id，
 * 上一轮存下的 `nextFocus*Id` 全部指向已销毁的 View，系统只能回退到几何猜测——
 * 「从详情返回光标跑到第一张」就是这么来的。key 跟着数据走，重建多少次都还是同一个。
 *
 * 这个文件不引用 `android.*`：焦点关系必须能被 JVM 单测覆盖。
 */

/** 焦点项的语义标识。约定形态：`"段:标识"`，例如 `"card:7421"`、`"nav:1"`。 */
@JvmInline
value class FocusKey(val value: String)

/**
 * 一个焦点段：内部按 [columns] 分列，从上到下排。
 *
 * 两种常见形态：
 * - 水平一行（顶栏、筛选、搜索动作）：`columns = keys.size`，只有一行。
 * - 网格（卡片区）：`columns` 是实际列数，末行可以不满。
 *
 * **空段不允许存在**：空段会成为"按进去出不来"的隐形区域。调用方应整段省略。
 */
data class FocusSection(val keys: List<FocusKey>, val columns: Int) {
    init {
        require(keys.isNotEmpty()) { "焦点段不能为空：调用方应整段省略，而不是传空列表" }
        require(columns >= 1) { "焦点段的列数至少为 1，实际 $columns" }
    }

    val rows: Int get() = (keys.size + columns - 1) / columns

    /** 某一行的 key 下标区间。行越界时返回空区间。 */
    fun rowRange(row: Int): IntRange {
        val start = row * columns
        val end = minOf(keys.size, start + columns) - 1
        return if (start <= end) start..end else IntRange.EMPTY
    }
}

/**
 * 一页的焦点结构：从上到下的若干段 + 一条可选的**侧段**（B 方案的左侧栏）。
 *
 * 两种形态并存：
 * - [sections] 纵向堆叠：段内左右走、段间上下走（原有语义）；
 * - [side] 是竖排的侧栏（图标栏）：段内上下走，**左右与内容互通**——
 *   侧栏按右进第一段内容（按竖直位置选行），内容行首按左回侧栏（按竖直位置选项）。
 *   没有侧段时一切行为与旧版完全一致。
 *
 * **空段不允许存在**：空段会成为"按进去出不来"的隐形区域。调用方应整段省略。
 */
data class FocusPlan(val sections: List<FocusSection>, val side: FocusSection? = null) {
    init { require(sections.isNotEmpty()) { "焦点图至少需要一段" } }
}

/**
 * 四个方向的落点。**一律非空**：走到页面边上时落点是自己（夹住），
 * 不是"交给系统猜"。旧实现里"按了没反应"与"跳到奇怪的地方"都来自这个区别。
 */
data class Neighbors(val left: FocusKey, val right: FocusKey, val up: FocusKey, val down: FocusKey)

class FocusGraph private constructor(
    private val map: Map<FocusKey, Neighbors>,
    /** 按声明顺序展开的全部 key，用于"第一个焦点项"这类回退。 */
    val order: List<FocusKey>,
) {
    val size: Int get() = order.size

    fun contains(key: FocusKey): Boolean = map.containsKey(key)

    fun neighborsOf(key: FocusKey): Neighbors? = map[key]

    fun first(): FocusKey? = order.firstOrNull()

    /**
     * 焦点恢复的顺序：本次显式目标 → 本页上次的 key → 该页第一个焦点项。
     * 旧实现"恢复失败静默落回第 0 张"是缺陷，不在这里复现。
     */
    fun resolve(preferred: FocusKey? = null, remembered: FocusKey? = null): FocusKey? {
        if (preferred != null && contains(preferred)) return preferred
        if (remembered != null && contains(remembered)) return remembered
        return first()
    }

    companion object {
        fun of(plan: FocusPlan): FocusGraph {
            val total = plan.sections.sumOf { it.keys.size } + (plan.side?.keys?.size ?: 0)
            val map = LinkedHashMap<FocusKey, Neighbors>(total)
            val order = ArrayList<FocusKey>(total)

            // 侧段先入序：空页兜底落在导航上（与旧版"顶栏第一项"同一角色）。
            plan.side?.let { side ->
                order.addAll(side.keys)
                val firstContent = plan.sections.first()
                side.keys.forEachIndexed { index, key ->
                    map[key] = Neighbors(
                        left = key,
                        right = crossToRow(firstContent, index, side.keys.size, fromStart = true),
                        up = if (index > 0) side.keys[index - 1] else key,
                        down = if (index < side.keys.lastIndex) side.keys[index + 1] else key,
                    )
                }
            }

            plan.sections.forEachIndexed { sectionIndex, section ->
                val columns = section.columns
                order.addAll(section.keys)

                section.keys.forEachIndexed { index, key ->
                    val row = index / columns
                    val column = index % columns
                    val range = section.rowRange(row)

                    // 行首/行尾夹住自己：按左键不跨行，也不交给系统做几何猜测。
                    // 行首按左键的唯一出口是侧段（竖排在屏幕左缘）——按竖直位置选侧栏项。
                    val left = if (column > 0) {
                        section.keys[index - 1]
                    } else {
                        plan.side?.let { side -> crossToRow(side, row, section.rows, fromStart = true) } ?: key
                    }
                    val right = if (index < range.last) section.keys[index + 1] else key
                    val up = if (row > 0) {
                        nearestInRow(section, row - 1, column)
                    } else {
                        crossSection(plan, sectionIndex, -1, column, columns, key)
                    }
                    val down = if (row < section.rows - 1) {
                        nearestInRow(section, row + 1, column)
                    } else {
                        crossSection(plan, sectionIndex, 1, column, columns, key)
                    }
                    require(!map.containsKey(key)) {
                        "焦点 key 重复：${key.value}。同一个 key 不能出现在两个位置，否则焦点会指向两处"
                    }
                    map[key] = Neighbors(left = left, right = right, up = up, down = down)
                }
            }
            return FocusGraph(map, order)
        }

        /** 同一段内上下移动：目标行的同一列；该格不存在时取最近的一格（末行不满的情形）。 */
        private fun nearestInRow(section: FocusSection, row: Int, column: Int): FocusKey {
            val range = section.rowRange(row)
            val wanted = range.first + column
            return section.keys[minOf(wanted, range.last)]
        }

        /**
         * 跨段移动：取相邻段，按水平位置的比例映射到目标段的某一项。
         *
         * 目标段是多行网格时，只落到**靠近的那一行**——往下走落首行、往上走落末行。
         * 直接按整个段的项数取下标会把"往下一行"变成"往下一屏的中间"，这是旧实现里
         * 五段兜底链想修但没修干净的毛病。
         */
        private fun crossSection(
            plan: FocusPlan,
            sectionIndex: Int,
            direction: Int,
            column: Int,
            columns: Int,
            self: FocusKey,
        ): FocusKey {
            val targetIndex = sectionIndex + direction
            if (targetIndex !in plan.sections.indices) return self
            val target = plan.sections[targetIndex]
            val row = if (direction > 0) 0 else target.rows - 1
            val range = target.rowRange(row)

            // 中心对齐映射：把源列的中心位置换算成目标行的中心位置，再取整。
            // 直接按「列号 × 目标项数」缩放会让中间几项永远跳不到，中心对齐能让整行都被覆盖。
            val width = range.last - range.first + 1
            val center = (column + 0.5f) * width / columns - 0.5f
            val offset = center.toInt().coerceIn(0, width - 1)
            return target.keys[range.first + offset]
        }

        /**
         * 横向跨界（侧栏 ↔ 内容）：按**竖直位置**的比例映射到目标段的某一行，
         * 再取该行靠屏幕边缘的那一项（从左缘进入取行首）。
         * 与 [crossSection] 的列比例映射互为转置。
         */
        private fun crossToRow(target: FocusSection, sourceRow: Int, sourceRows: Int, fromStart: Boolean): FocusKey {
            val rows = target.rows
            val center = (sourceRow + 0.5f) * rows / sourceRows - 0.5f
            val row = center.toInt().coerceIn(0, rows - 1)
            val range = target.rowRange(row)
            return if (fromStart) target.keys[range.first] else target.keys[range.last]
        }
    }
}
