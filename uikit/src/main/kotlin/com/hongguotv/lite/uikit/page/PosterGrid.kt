package com.hongguotv.lite.uikit.page

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.layout.GridGeometry
import com.hongguotv.lite.uikit.ui.CardItem
import com.hongguotv.lite.uikit.ui.PosterCard
import com.hongguotv.lite.uikit.ui.UiKit

/** 一次渲染的结果：页面拿它去组装焦点段，uikit 不替 feature 决定"上方是什么"。 */
data class GridRendered(
    /** 焦点 key，按展示顺序。 */
    val keys: List<FocusKey>,
    /** key → 卡片视图，交给 `FocusBinder.bind`。 */
    val views: Map<FocusKey, View>,
    val columns: Int,
)

/**
 * 海报网格：自适应列数 + **按 id 复用卡片视图**。
 *
 * 复用是焦点稳定的前提：同一页数据刷新（收藏检查完成、进度更新）时，
 * 只要 id 序列与列数没变，就只更新文字与角标、**不动视图树**，光标不会因为"整片重建"丢掉。
 * 只有序列/列数变化（翻页、筛选、换分辨率）才重排，重排后由页面用焦点会话把光标要回来。
 *
 * 卡宽决定封面解码尺寸，所以卡宽变了就整体重建——这是唯一必须重建的情形。
 */
@SuppressLint("ViewConstructor")
class PosterGrid internal constructor(
    private val ui: UiKit,
    private val section: String,
) : LinearLayout(ui.context) {

    private val tokens = ui.tokens
    private val cards = LinkedHashMap<String, PosterCard>()
    private var lastIds: List<String> = emptyList()
    private var lastColumns = 0
    private var lastCardWidth = 0

    /** 聚焦回调：目录页用它更新预览区。 */
    var onCardFocused: ((CardItem) -> Unit)? = null

    /** 点击回调：feature 决定点进去干什么。 */
    var onCardActivated: ((CardItem) -> Unit)? = null

    /** 是否允许封面落盘：只有收藏/历史页值得占电视存储。 */
    var persistCovers: Boolean = false

    /**
     * 聚光（B 方案）：非焦点卡压到 [com.hongguotv.lite.uikit.token.Metrics.focusDimAlpha]，
     * 焦点卡全亮——"屏幕上只有那一张卡是亮的"。搜索结果、季线这类"内容本身是主角"的网格
     * 不开（结果卡全亮，未上线项用 [CardItem.dim] 表达）；「继续看」、历史这类
     * "背景墙"网格开。
     */
    var spotlight: Boolean = false

    /**
     * 整张卡的高度上限（像素，0 = 不限制）。首屏只放得下一行卡的页面（搜索结果）给这个值，
     * 卡片据此夹短封面，保证标题与角标也在屏内。
     *
     * 变**不**触发重建：卡片就地改封面高度（[PosterCard.heightLimit]），焦点不受影响——
     * 首屏量到真实视口时正好会改一次，那时重建会把光标弄丢。
     */
    var cardHeightLimit: Int = 0

    /** 目标卡宽覆盖（dp）：历史页两行 6 列这类"产品口径"网格用；0 = 令牌默认（149）。 */
    var cardWidthTargetDp: Int = 0

    /** 列间距覆盖（dp）：历史页 12；0 = 令牌默认（14）。 */
    var columnGapDp: Int = 0

    /** 行间距覆盖（dp）：历史页 20；0 = 令牌默认（14）。 */
    var rowGapDp: Int = 0

    /** 信息条高度覆盖（dp）：历史卡 26；0 = 目录卡默认（34）。 */
    var stripHeightDp: Int = 0

    /** 封面高度覆盖（dp）：历史卡 152；0 = 按 0.7 比例派生。 */
    var coverHeightDp: Int = 0

    init {
        orientation = VERTICAL
        // 焦点卡放大 1.06 会超出网格边界：不留出溢出量、又让网格裁子视图的话，
        // 首行/首列的 3dp 白框会被切平（只剩右/下两侧看得到）。
        // `clipToPadding` 也必须关掉——它会把整棵子树裁到 padding 框内，白框照样没。
        clipChildren = false
        clipToPadding = false
    }

    fun keyOf(id: String): FocusKey = FocusKey("$section:$id")

    fun render(items: List<CardItem>): GridRendered {
        val limited = items.take(tokens.pageCardLimit)
        val ids = limited.map { it.id }
        val gap = dp(if (columnGapDp > 0) columnGapDp else tokens.metrics.cardGap)
        val rowGap = dp(if (rowGapDp > 0) rowGapDp else tokens.metrics.cardGap)
        val target = dp(if (cardWidthTargetDp > 0) cardWidthTargetDp else tokens.metrics.gridTargetCardWidth)
        val available0 = ui.availableWidth(width)
        val columns = GridGeometry.columns(
            availableWidthPx = available0,
            targetCardWidthPx = target,
            gapPx = gap,
            minColumns = tokens.metrics.gridMinColumns,
            maxColumns = tokens.metrics.gridMaxColumns,
        )
        // 先按"未扣留白"的卡宽估算放大外扩，再把留白扣掉重算卡宽：
        // 留白取决于卡高、卡高取决于卡宽，一步收敛（外扩只与较长边有关，量级是几 dp）
        val cardWidth0 = GridGeometry.cardWidth(available0, columns, gap)
        val cover0 = if (coverHeightDp > 0) {
            dp(coverHeightDp)
        } else {
            (cardWidth0 / tokens.metrics.posterAspect).toInt()
        }
        val strip = dp(if (stripHeightDp > 0) stripHeightDp else tokens.metrics.catalogStripHeight)
        val scale = if (tokens.motion.enabled) tokens.motion.focusScale else 1f
        // focusOverflow 的入参和返回值都是像素，不要再换算一次
        val overflow = GridGeometry.focusOverflow(cardWidth0, cover0 + strip, scale)
        applyFocusPadding(overflow)

        val available = (ui.availableWidth(width) - paddingLeft - paddingRight).coerceAtLeast(1)
        val cardWidth = GridGeometry.cardWidth(available, columns, gap)

        val reusable = ids == lastIds && columns == lastColumns && cardWidth == lastCardWidth && cards.isNotEmpty()
        if (reusable) {
            limited.forEach { item ->
                cards[item.id]?.let { card ->
                    card.heightLimit(cardLimit())
                    card.persistCover(persistCovers)
                    card.bind(item)
                }
            }
        } else {
            rebuild(limited, columns, cardWidth, gap, rowGap)
            lastIds = ids
            lastColumns = columns
            lastCardWidth = cardWidth
        }
        applySpotlight()

        val views = LinkedHashMap<FocusKey, View>(limited.size)
        limited.forEach { item -> cards[item.id]?.let { views[keyOf(item.id)] = it } }
        return GridRendered(keys = ids.map(::keyOf), views = views, columns = columns)
    }

    /** 焦点外扩的留白：只在数值变化时改，避免每帧触发一次布局。 */
    private fun applyFocusPadding(pad: Int) {
        if (paddingLeft == pad && paddingTop == pad && paddingRight == pad && paddingBottom == pad) return
        setPadding(pad, pad, pad, pad)
    }

    /** 高度上限扣掉上下留白：卡片连同白框要落在视口里，不能把留白算成可用高度。 */
    private fun cardLimit(): Int =
        if (cardHeightLimit <= 0) 0 else (cardHeightLimit - paddingTop - paddingBottom).coerceAtLeast(0)

    /** 把"哪张卡正亮着"同步给全部卡。焦点落到本网格之外时（全暗）由下一次 render 修正。 */
    private fun applySpotlight() {
        if (!spotlight) {
            cards.values.forEach { it.dimmed = false }
            return
        }
        val focusedId = cards.entries.firstOrNull { it.value.isFocused }?.key
        cards.forEach { (id, card) -> card.dimmed = id != focusedId }
    }

    private fun rebuild(limited: List<CardItem>, columns: Int, cardWidth: Int, gap: Int, rowGap: Int) {
        // 重建会换掉一批 View 实例：先把"哪一张正被聚焦"记下来，建完立刻要回去。
        // 不这么做的话，框架会把焦点交给页面里第一个可聚焦项（顶栏），而那次变化会被
        // 焦点记忆记下来——「切完 tab / 搜索完成后光标跑到顶上」就是这么来的。
        val focusedId = cards.entries.firstOrNull { it.value.isFocused }?.key
        if (cardWidth != lastCardWidth) cards.clear()
        val keep = limited.mapTo(HashSet(limited.size)) { it.id }
        val iterator = cards.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().key !in keep) iterator.remove()

        removeAllViews()
        val rowCounts = GridGeometry.rowsOf(limited.size, columns)
        var cursor = 0
        rowCounts.forEachIndexed { rowIndex, count ->
            val row = ui.rowTop()
            repeat(count) { indexInRow ->
                val item = limited[cursor++]
                val card = cards.getOrPut(item.id) { newCard(cardWidth) }
                card.persistCover(persistCovers)
                card.bind(item)
                row.addView(
                    card,
                    LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (indexInRow < count - 1) rightMargin = gap
                    },
                )
            }
            addView(
                row,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    // 行距不只是排版：放大后的卡片上下各外扩几 dp，行距为 0 时相邻两张的白框会叠在一起
                    if (rowIndex < rowCounts.lastIndex) bottomMargin = rowGap
                },
            )
        }
        // 同一张卡还在就回到它；否则交给页面的焦点阶梯（它会在 render 结束后请求）
        if (focusedId != null) cards[focusedId]?.requestFocus()
    }

    private fun newCard(widthPx: Int): PosterCard = PosterCard(
        ui = ui,
        widthPx = widthPx,
        maxHeightPx = cardLimit(),
        stripHeightDp = stripHeightDp,
        coverHeightDp = coverHeightDp,
    ).apply {
        onFocused = { item ->
            applySpotlight()
            onCardFocused?.invoke(item)
        }
        onActivated = { item -> onCardActivated?.invoke(item) }
    }
}
