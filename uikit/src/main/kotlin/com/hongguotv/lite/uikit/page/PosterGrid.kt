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

    init {
        orientation = VERTICAL
    }

    fun keyOf(id: String): FocusKey = FocusKey("$section:$id")

    fun render(items: List<CardItem>): GridRendered {
        val limited = items.take(tokens.pageCardLimit)
        val ids = limited.map { it.id }
        val gap = dp(tokens.metrics.cardGap)
        val available = ui.availableWidth(width)
        val columns = GridGeometry.columns(
            availableWidthPx = available,
            targetCardWidthPx = dp(tokens.metrics.gridTargetCardWidth),
            gapPx = gap,
            minColumns = tokens.metrics.gridMinColumns,
            maxColumns = tokens.metrics.gridMaxColumns,
        )
        val cardWidth = GridGeometry.cardWidth(available, columns, gap)

        val reusable = ids == lastIds && columns == lastColumns && cardWidth == lastCardWidth && cards.isNotEmpty()
        if (reusable) {
            limited.forEach { item ->
                cards[item.id]?.let { card ->
                    card.persistCover(persistCovers)
                    card.bind(item)
                }
            }
        } else {
            rebuild(limited, columns, cardWidth, gap)
            lastIds = ids
            lastColumns = columns
            lastCardWidth = cardWidth
        }

        val views = LinkedHashMap<FocusKey, View>(limited.size)
        limited.forEach { item -> cards[item.id]?.let { views[keyOf(item.id)] = it } }
        return GridRendered(keys = ids.map(::keyOf), views = views, columns = columns)
    }

    private fun rebuild(limited: List<CardItem>, columns: Int, cardWidth: Int, gap: Int) {
        if (cardWidth != lastCardWidth) cards.clear()
        val keep = limited.mapTo(HashSet(limited.size)) { it.id }
        val iterator = cards.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().key !in keep) iterator.remove()

        removeAllViews()
        val rowCounts = GridGeometry.rowsOf(limited.size, columns)
        var cursor = 0
        rowCounts.forEach { count ->
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
            addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun newCard(widthPx: Int): PosterCard = PosterCard(ui, widthPx).apply {
        onFocused = { item -> onCardFocused?.invoke(item) }
        onActivated = { item -> onCardActivated?.invoke(item) }
    }
}
