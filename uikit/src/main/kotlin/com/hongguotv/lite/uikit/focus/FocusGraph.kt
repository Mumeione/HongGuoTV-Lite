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
 * 侧栏导航项的 key 前缀（`nav:0 … nav:n-1`）。
 *
 * 页面拼焦点图、FocusSession 过滤"侧栏不进记忆"都用这一份：
 * 前缀散在六处手拼时，改一次格式要动三个模块，漏一处就是光标落到图外。
 */
const val NAV_KEY_PREFIX = "nav:"

/** 第 [index] 个导航项的焦点 key。 */
fun navKey(index: Int): FocusKey = FocusKey("$NAV_KEY_PREFIX$index")

/**
 * 一个焦点段：内部按 [columns] 分列，从上到下排。
 *
 * 两种常见形态：
 * - 水平一行（顶栏、筛选、搜索动作）：`columns` 是这一行**视觉上的格子数**，只有一行。
 * - 网格（卡片区）：`columns` 是实际列数，末行可以不满。
 *
 * **槽位可以为空**（`null`）：禁用的按钮仍然占着它的位置（「上一组」在第一页不可用、
 * 但「下一组」仍在第二列）。只放可聚焦项、把禁用项从列表里删掉，会让"从翻组键往下"
 * 按错误的列号映射到别的卡上——维护者实测的"跳位置"就是这个。
 *
 * **空段不允许存在**：空段会成为"按进去出不来"的隐形区域。调用方应整段省略。
 */
data class FocusSection(
    val slots: List<FocusKey?>,
    val columns: Int,
    /**
     * 整段"往下/往上"的显式出口（只作用于本段的末行/首行）。
     *
     * 用在**控件宽度与网格不一致**的行上：搜索页的片库胶囊只占屏幕左边一小段，
     * 按列号比例映射会让第二、三个胶囊落到「搜索」按钮上；用户按下的意图是"去搜索框"。
     * 与列号无关的出口只有页面知道，所以由页面指名。
     */
    val down: FocusKey? = null,
    /**
     * 本段的左右两侧有**长条翻组键**侧翼（[FocusPlan.leftFlank]/[rightFlank]）：
     * 行首按左、行尾按右都落到侧翼，不再夹住自己或进导航侧栏。
     * 只有被侧翼包夹的网格段声明它（分集格、季线卡）；动作列这类竖排段保持夹住自己。
     */
    val flanked: Boolean = false,
) {
    init {
        require(slots.any { it != null }) { "焦点段不能为空：调用方应整段省略，而不是传空列表" }
        require(columns >= 1) { "焦点段的列数至少为 1，实际 $columns" }
    }

    /** 可聚焦项（不含空槽）。 */
    val keys: List<FocusKey> = slots.filterNotNull()

    val rows: Int get() = (slots.size + columns - 1) / columns

    /** 某一行的槽位下标区间。行越界时返回空区间。 */
    fun rowRange(row: Int): IntRange {
        val start = row * columns
        val end = minOf(slots.size, start + columns) - 1
        return if (start <= end) start..end else IntRange.EMPTY
    }

    /** 这一行里离 [column] 最近的可用项：先看本格，再左右各退一格。 */
    fun nearestInRow(row: Int, column: Int): FocusKey? {
        val range = rowRange(row)
        if (range.isEmpty()) return null
        val wanted = range.first + column
        // 目标列可能落在末行之外（末行不满）：搜索半径要覆盖到整行的宽度
        for (distance in 0..columns) {
            listOf(wanted - distance, wanted + distance).forEach { index ->
                if (index in range) slots[index]?.let { return it }
            }
        }
        return null
    }

    /** 同一行里向左的下一个可用项。 */
    fun previousInRow(row: Int, column: Int): FocusKey? {
        val range = rowRange(row)
        if (range.isEmpty()) return null
        for (index in (range.first + column - 1) downTo range.first) {
            slots.getOrNull(index)?.let { return it }
        }
        return null
    }

    /** 同一行里向右的下一个可用项。 */
    fun nextInRow(row: Int, column: Int): FocusKey? {
        val range = rowRange(row)
        if (range.isEmpty()) return null
        for (index in (range.first + column + 1)..range.last) {
            slots.getOrNull(index)?.let { return it }
        }
        return null
    }

    /** 从行的左缘（或右缘）找第一个可用项。 */
    fun edgeOfRow(row: Int, fromStart: Boolean): FocusKey? {
        val range = rowRange(row)
        if (range.isEmpty()) return null
        val indices = if (fromStart) range else range.reversed()
        return indices.firstNotNullOfOrNull { slots.getOrNull(it) }
    }
}

/**
 * 一页的焦点结构：从上到下的若干段 + 一条可选的**侧段**（B 方案的左侧栏）。
 *
 * 两种形态并存：
 * - [sections] 纵向堆叠：段内左右走、段间上下走（原有语义）；
 * - [side] 是竖排的侧栏（图标栏）：段内上下走，**左右与内容互通**——
 *   侧栏按右进第一段内容（按竖直位置选行），内容行首按左回侧栏。
 *   没有侧段时一切行为与旧版完全一致。
 *
 * [sideAnchor] 是"内容往左回侧栏时优先落在哪一项"：目录页的内容通常只有一行，
 * 按竖直位置映射会落到侧栏中段（用户按左键只是想回自己那一页的 tab），
 * 页面因此指名当前 tab。没给或不在侧段里时退回按竖直位置映射。
 *
 * **空段不允许存在**：空段会成为"按进去出不来"的隐形区域。调用方应整段省略。
 */
data class FocusPlan(
    val sections: List<FocusSection>,
    val side: FocusSection? = null,
    val sideAnchor: FocusKey? = null,
    /**
     * 贴着 [sections] 里 `flanked` 段左右两侧的长条翻组键（分集格、季线卡两侧的 ‹ › 长条）。
     * 各是**单列竖条**：行首按左 / 行尾按右落到条上（按竖直行号映射），条上按右/左回到内容行缘。
     * 与 [side]（导航侧栏）互不干扰：侧栏接没被侧翼声明的段。
     */
    val leftFlank: FocusSection? = null,
    val rightFlank: FocusSection? = null,
) {
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
            val total = plan.sections.sumOf { it.keys.size } +
                (plan.side?.keys?.size ?: 0) +
                (plan.leftFlank?.keys?.size ?: 0) +
                (plan.rightFlank?.keys?.size ?: 0)
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

            // 侧翼长条（分集格/季线卡两侧的 ‹ › ）：与内容行缘互为出入，按竖直行号映射。
            // 只允许一段被包夹：出现两段时第二个会静默接错侧翼，直接拒绝。
            require(plan.sections.count { it.flanked } <= 1) {
                "侧翼长条只能包夹一段：当前声明了 ${plan.sections.count { it.flanked }} 个 flanked 段"
            }
            val flankedSection = plan.sections.firstOrNull { it.flanked }
            plan.leftFlank?.let { flank ->
                order.addAll(flank.keys)
                flank.keys.forEachIndexed { index, key ->
                    map[key] = Neighbors(
                        left = key,
                        right = flankedSection?.let { crossToRow(it, index, flank.keys.size, fromStart = true) } ?: key,
                        up = if (index > 0) flank.keys[index - 1] else key,
                        down = if (index < flank.keys.lastIndex) flank.keys[index + 1] else key,
                    )
                }
            }
            plan.rightFlank?.let { flank ->
                order.addAll(flank.keys)
                flank.keys.forEachIndexed { index, key ->
                    map[key] = Neighbors(
                        left = flankedSection?.let { crossToRow(it, index, flank.keys.size, fromStart = false) } ?: key,
                        right = key,
                        up = if (index > 0) flank.keys[index - 1] else key,
                        down = if (index < flank.keys.lastIndex) flank.keys[index + 1] else key,
                    )
                }
            }

            plan.sections.forEachIndexed { sectionIndex, section ->
                val columns = section.columns
                order.addAll(section.keys)

                section.slots.forEachIndexed { index, key ->
                    if (key == null) return@forEachIndexed
                    val row = index / columns
                    val column = index % columns

                    // 行首/行尾的去处：被侧翼包夹的段先找长条，否则夹住自己或进导航侧栏。
                    // 按左键不跨行，也不交给系统做几何猜测。
                    val left = if (column > 0) {
                        section.previousInRow(row, column) ?: leftNeighborOf(plan, section, row, key)
                    } else {
                        leftNeighborOf(plan, section, row, key)
                    }
                    val right = section.nextInRow(row, column)
                        ?: (if (section.flanked) plan.rightFlank?.let { crossToRow(it, row, section.rows, fromStart = false) } else null)
                        ?: key
                    val up = if (row > 0) {
                        section.nearestInRow(row - 1, column)
                            ?: crossSection(plan, sectionIndex, -1, column, columns, key)
                    } else {
                        crossSection(plan, sectionIndex, -1, column, columns, key)
                    }
                    val down = if (row < section.rows - 1) {
                        section.nearestInRow(row + 1, column)
                            ?: crossSection(plan, sectionIndex, 1, column, columns, key)
                    } else {
                        section.down ?: crossSection(plan, sectionIndex, 1, column, columns, key)
                    }
                    require(!map.containsKey(key)) {
                        "焦点 key 重复：${key.value}。同一个 key 不能出现在两个位置，否则焦点会指向两处"
                    }
                    map[key] = Neighbors(left = left, right = right, up = up, down = down)
                }
            }
            return FocusGraph(map, order)
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
            // 目标行可能带着空槽（禁用的翻组键）：取离这一列最近的可用项
            return target.nearestInRow(row, offset) ?: self
        }

        /**
         * 行首往左的去处（按优先级）：被侧翼包夹的段先落长条，否则进导航侧栏（页面指名的
         * 当前 tab 优先，其次按竖直位置映射），都没有才夹住自己。
         */
        private fun leftNeighborOf(plan: FocusPlan, section: FocusSection, row: Int, key: FocusKey): FocusKey {
            if (section.flanked) {
                plan.leftFlank?.let { flank -> return crossToRow(flank, row, section.rows, fromStart = true) }
                return key
            }
            plan.side?.let { side -> return leftIntoSide(plan, side, row, section.rows) }
            return key
        }

        /**
         * 内容行首往左的去处：页面指名的当前 tab 优先，其次按竖直位置映射。
         *
         * 指名只在"锚点确实在侧段里"时生效——页面传了一个已被移除的导航项时不能把光标扔到图外。
         */
        private fun leftIntoSide(plan: FocusPlan, side: FocusSection, row: Int, rows: Int): FocusKey {
            val anchor = plan.sideAnchor
            if (anchor != null && anchor in side.keys) return anchor
            return crossToRow(side, row, rows, fromStart = true)
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
            return target.edgeOfRow(row, fromStart) ?: target.keys.first()
        }
    }
}
