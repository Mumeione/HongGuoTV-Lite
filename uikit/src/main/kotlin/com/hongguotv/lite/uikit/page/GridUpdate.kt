package com.hongguotv.lite.uikit.page

/**
 * 网格刷新模式：同一批 id 只是换了顺序时**重排**，不重建视图树。
 *
 * 重建会在同一帧里把焦点卡从行容器摘掉再挂回去；框架在那段空隙里会做一次被动落焦，
 * 滚动位置也要重新夹取。顺序变化（正/倒序、聚合季线换页）只是位置变了，视图本身不必换。
 *
 * 只有集合、列数或卡宽真的变了才重建（卡宽变了，封面解码尺寸也就变了）。
 */
internal object GridUpdate {

    enum class Mode {
        /** id 序列与布局都没变：只重绑卡片内容。 */
        UPDATE,

        /** 同一批 id 只是换了顺序：保留行与卡片，只调整子 View 顺序。 */
        REORDER,

        /** 集合、列数或卡宽变了：必须重建。 */
        REBUILD,
    }

    fun mode(
        lastIds: List<String>,
        ids: List<String>,
        lastColumns: Int,
        columns: Int,
        lastCardWidth: Int,
        cardWidth: Int,
        hasCards: Boolean,
    ): Mode {
        if (!hasCards) return Mode.REBUILD
        if (columns != lastColumns || cardWidth != lastCardWidth) return Mode.REBUILD
        if (ids == lastIds) return Mode.UPDATE
        if (ids.size == lastIds.size && ids.toSet() == lastIds.toSet()) return Mode.REORDER
        return Mode.REBUILD
    }
}
