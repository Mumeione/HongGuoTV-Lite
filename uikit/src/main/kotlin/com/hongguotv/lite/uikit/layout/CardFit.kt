package com.hongguotv.lite.uikit.layout

/**
 * 卡片封面高度的夹取：**首屏必须看得见完整卡片**（封面 + 两行标题 + 角标）。
 *
 * 为什么单独成一个纯函数：夹取是"视口高 - 文字块高"这种算术，掺进 `PosterCard` 的构造里就没法测了，
 * 而它的错误表现（标题被推到屏幕外）只能靠肉眼在电视上发现——那是最贵的一种验证方式。
 *
 * 三条口径：
 * 1. 视口够高时**保持比例**（`卡宽 / 比例`），不做任何事——收藏/历史页走这条；
 * 2. 视口不够时把封面夹到"视口 - 文字块"，让整卡落在首屏；
 * 3. 夹到 [minArtworkHeightPx] 为止，再小就看不清是哪部剧了，宁可让卡片略微超出视口。
 */
object CardFit {

    /**
     * @param cardWidthPx 封面宽度（已扣掉卡片左右内边距）
     * @param aspect 封面宽高比（宽 / 高）
     * @param cardHeightLimitPx 整张卡的高度上限；0 或负数表示不限制（还没量、或页面高度充足）
     * @param textBlockHeightPx 封面以下那块文字（标题两行 + 角标 + 内边距）的高度
     * @param minArtworkHeightPx 保底高度
     */
    fun artworkHeight(
        cardWidthPx: Int,
        aspect: Float,
        cardHeightLimitPx: Int,
        textBlockHeightPx: Int,
        minArtworkHeightPx: Int,
    ): Int {
        require(aspect > 0f) { "封面宽高比必须为正，实际 $aspect" }
        val natural = (cardWidthPx / aspect).toInt().coerceAtLeast(1)
        if (cardHeightLimitPx <= 0) return natural
        val room = cardHeightLimitPx - textBlockHeightPx
        if (room >= natural) return natural
        return room.coerceAtLeast(minArtworkHeightPx.coerceAtLeast(1))
    }
}
