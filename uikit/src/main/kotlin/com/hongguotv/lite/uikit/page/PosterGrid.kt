package com.hongguotv.lite.uikit.page

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hongguotv.core.port.ArtworkSpec
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.layout.CardFit
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
 * 同一批 id 只是换了顺序（正/倒序、聚合季线换页）时保留行容器、就地重排卡片；
 * 只有集合/列数/卡宽变化（翻页、筛选、换分辨率）才重建，重建后由页面用焦点会话把光标要回来。
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

    /**
     * 本网格**最多画几张**（0 = 令牌默认 `pageCardLimit`）。
     *
     * 搜索结果按"两行 10 张"的产品口径用 10：上游一页就是 10 条，翻页交给 PagerRow，
     * 不把 30 张堆在同一屏里。
     */
    var itemLimit: Int = 0

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
    }

    fun keyOf(id: String): FocusKey = FocusKey("$section:$id")

    /**
     * 焦点 key → 本网格里的卡片 id；不是本网格的 key 返回 null。
     *
     * key 的格式由本类拥有，页面不该自己 `removePrefix("section:")`：三页各写一遍的话，
     * 改一次格式要同时改三处，漏一处就是信息栏静默不更新。
     */
    fun cardIdOf(key: FocusKey): String? {
        val prefix = "$section:"
        return if (key.value.startsWith(prefix)) key.value.removePrefix(prefix) else null
    }

    fun render(items: List<CardItem>): GridRendered {
        val limited = items.take(if (itemLimit > 0) itemLimit else tokens.pageCardLimit)
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
        // 卡宽按"整行可用宽度"算：焦点不再放大，卡片不会越出网格边界，
        // 也就不需要为溢出留内边距（留白会让卡宽白掉几 dp）
        val available = (ui.availableWidth(width) - paddingLeft - paddingRight).coerceAtLeast(1)
        val cardWidth = GridGeometry.cardWidth(available, columns, gap)

        // 可用高度是给整个网格的：两行 10 张要先扣行距再均分，否则第二行被切掉
        val rows = GridGeometry.rowsOf(limited.size, columns).size
        val limit = GridGeometry.cardHeightForRows(cardHeightLimit, rows, rowGap)

        when (GridUpdate.mode(lastIds, ids, lastColumns, columns, lastCardWidth, cardWidth, cards.isNotEmpty())) {
            GridUpdate.Mode.UPDATE -> bindCards(limited, limit)
            GridUpdate.Mode.REORDER -> reorder(limited, columns, cardWidth, gap, rowGap, limit)
            GridUpdate.Mode.REBUILD -> rebuild(limited, columns, cardWidth, gap, rowGap, limit)
        }
        lastIds = ids
        lastColumns = columns
        lastCardWidth = cardWidth
        applySpotlight()

        val views = LinkedHashMap<FocusKey, View>(limited.size)
        limited.forEach { item -> cards[item.id]?.let { views[keyOf(item.id)] = it } }
        return GridRendered(keys = ids.map(::keyOf), views = views, columns = columns)
    }

    /**
     * 预热一批封面：按本网格**当前渲染规格**取图进内存，不贴图。
     *
     * 用途是翻页预热：下一页的数据已经在内存时，先把封面取回来，翻过去直接命中。
     * 尺寸必须与卡片自己的请求同键（卡宽 + 按内容算出的封面高），否则预热等于白做。
     */
    fun prefetchCovers(items: List<CardItem>, limit: Int = 0) {
        val port = ui.artwork ?: return
        if (lastCardWidth <= 0 || lastColumns <= 0) return
        val targets = items.take(if (limit > 0) limit else tokens.pageCardLimit)
        if (targets.isEmpty()) return
        // 行数按**目标页**自己的条目数算：末页只有一行时封面比两行页更高，键也就不同
        val rowGap = dp(if (rowGapDp > 0) rowGapDp else tokens.metrics.cardGap)
        val rows = GridGeometry.rowsOf(targets.size, lastColumns).size
        val cardLimit = GridGeometry.cardHeightForRows(cardHeightLimit, rows, rowGap)
        targets.forEach { item ->
            if (item.coverUrl.isBlank()) return@forEach
            port.prefetch(
                item.coverUrl,
                ArtworkSpec(
                    widthPx = lastCardWidth,
                    heightPx = coverHeight(item, cardLimit),
                    priority = ArtworkSpec.PRIORITY_PREFETCH,
                ),
            )
        }
    }

    /** 与 PosterCard 的封面高度口径一致：预热键要和真正贴图时一样。 */
    private fun coverHeight(item: CardItem, cardLimit: Int): Int = CardFit.artworkHeight(
        cardWidthPx = lastCardWidth,
        aspect = tokens.metrics.posterAspect,
        cardHeightLimitPx = cardLimit,
        textBlockHeightPx = if (item.title.isEmpty()) {
            dp(if (stripHeightDp > 0) stripHeightDp else tokens.metrics.catalogStripHeight)
        } else {
            dp(tokens.metrics.seasonTitleHeight + tokens.metrics.seasonStatusHeight)
        },
        minArtworkHeightPx = dp(tokens.metrics.artworkMinHeight),
        explicitHeightPx = if (coverHeightDp > 0) dp(coverHeightDp) else 0,
    )

    /** 把"哪张卡正亮着"同步给全部卡。焦点落到本网格之外时（全暗）由下一次 render 修正。 */
    private fun applySpotlight() {
        if (!spotlight) {
            cards.values.forEach { it.dimmed = false }
            return
        }
        val focusedId = cards.entries.firstOrNull { it.value.isFocused }?.key
        cards.forEach { (id, card) -> card.dimmed = id != focusedId }
    }

    /** 序列与布局都没变：只重绑文字与角标，视图树完全不动。 */
    private fun bindCards(limited: List<CardItem>, limit: Int) {
        limited.forEach { item -> cards[item.id]?.let { applyCard(it, item, limit) } }
    }

    /** 卡片内容与尺寸的统一更新点：重绑 / 重排 / 重建三条刷新路径都走这里。 */
    private fun applyCard(card: PosterCard, item: CardItem, limit: Int) {
        card.heightLimit(limit)
        card.persistCover(persistCovers)
        card.bind(item)
    }

    /**
     * 同一批卡片只换顺序：保留行容器，把卡片按新顺序重新挂回去。
     *
     * 不走 [rebuild]：它会先 `removeAllViews()`，焦点卡被摘掉的那一瞬间框架会做一次
     * 被动落焦（通常是侧栏），滚动位置也可能被重新夹取。这里的重排不改变内容高度，
     * 行容器与行距沿用原样，滚动位置自然保持。
     */
    private fun reorder(limited: List<CardItem>, columns: Int, cardWidth: Int, gap: Int, rowGap: Int, limit: Int) {
        val focusedId = cards.entries.firstOrNull { it.value.isFocused }?.key
        val rowCounts = GridGeometry.rowsOf(limited.size, columns)
        val rows = (0 until childCount).mapNotNull { getChildAt(it) as? LinearLayout }
        if (rows.size != rowCounts.size) {
            rebuild(limited, columns, cardWidth, gap, rowGap, limit)
            return
        }
        // 卡片先全部脱离旧行，再按新顺序放回去；同一帧内完成，不会出现空帧
        cards.values.forEach { card -> (card.parent as? ViewGroup)?.removeView(card) }
        rows.forEach { it.removeAllViews() }
        var cursor = 0
        rowCounts.forEachIndexed { rowIndex, count ->
            val row = rows[rowIndex]
            repeat(count) { indexInRow ->
                val item = limited[cursor++]
                val card = cards[item.id] ?: newCard(cardWidth, limit)
                applyCard(card, item, limit)
                row.addView(
                    card,
                    LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (indexInRow < count - 1) rightMargin = gap
                    },
                )
                cards[item.id] = card
            }
            (row.layoutParams as? LayoutParams)?.bottomMargin = if (rowIndex < rowCounts.lastIndex) rowGap else 0
        }
        // 重排后回到原来那张卡上；找不到（例如被新数据替换）就交给页面阶梯
        if (focusedId != null) cards[focusedId]?.requestFocus()
    }

    /**
     * 重建整片网格。
     *
     * 顺序变化已被 [reorder] 接走，这里只在集合/列数/卡宽变化时发生。
     * 保留下来的卡片会先记下父滚动容器的位置，重建完再恢复——避免滚动条在中途回到顶部。
     */
    private fun rebuild(limited: List<CardItem>, columns: Int, cardWidth: Int, gap: Int, rowGap: Int, limit: Int) {
        // 重建会换掉一批 View 实例：先把"哪一张正被聚焦"记下来，建完立刻要回去。
        // 不这么做的话，框架会把焦点交给页面里第一个可聚焦项（顶栏），而那次变化会被
        // 焦点记忆记下来——「切完 tab / 搜索完成后光标跑到顶上」就是这么来的。
        val focusedId = cards.entries.firstOrNull { it.value.isFocused }?.key
        val scroll = ancestorScroll()
        val scrollY = scroll?.scrollY
        if (cardWidth != lastCardWidth) cards.clear()
        val keep = limited.mapTo(HashSet(limited.size)) { it.id }
        val iterator = cards.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().key !in keep) iterator.remove()

        removeAllViews()
        // removeAllViews 只摘到"行"这一层：保留下来的卡片的 parent 还是刚被摘掉的行容器，
        // 直接往新行里 addView 会抛"child already has a parent"——同 id 集合**重排**（季线聚合
        // 到手、排序开关切换）时正好整批保留，必崩。先把卡片从旧行里摘干净再建新行。
        cards.values.forEach { card -> (card.parent as? ViewGroup)?.removeView(card) }
        val rowCounts = GridGeometry.rowsOf(limited.size, columns)
        var cursor = 0
        rowCounts.forEachIndexed { rowIndex, count ->
            val row = ui.rowTop()
            repeat(count) { indexInRow ->
                val item = limited[cursor++]
                val card = cards.getOrPut(item.id) { newCard(cardWidth, limit) }
                applyCard(card, item, limit)
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
                    // 行距是纯排版：卡片不缩放，行与行之间不会重叠
                    if (rowIndex < rowCounts.lastIndex) bottomMargin = rowGap
                },
            )
        }
        // 同一张卡还在就回到它；否则交给页面的焦点阶梯（它会在 render 结束后请求）
        scroll?.let { view ->
            if (scrollY != null && view.scrollY != scrollY) view.scrollTo(0, scrollY)
        }
        if (focusedId != null) cards[focusedId]?.requestFocus()
    }

    /** 本网格所在的滚动容器（目录页在 ContentPane/scrollBody 里）：重建前后要对齐滚动位置。 */
    private fun ancestorScroll(): ScrollView? {
        var view: View? = parent as? View
        while (view != null) {
            if (view is ScrollView) return view
            view = view.parent as? View
        }
        return null
    }

    private fun newCard(widthPx: Int, limit: Int): PosterCard = PosterCard(
        ui = ui,
        widthPx = widthPx,
        maxHeightPx = limit,
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
