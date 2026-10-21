package com.hongguotv.core.usecase

/**
 * 分集清单的分页算术：列数 × 行数决定一页放多少集。
 *
 * 为什么参数化而不是写死一组：详情页一行 20 格、一页 100 集（遥控器"翻页"比"上下移动"贵得多，
 * 一次给满一页，选集时少翻好几次——旧版实测口径）；播放侧栏屏幕被画面占着，一行 10 格、
 * 一页 50 集（维护者口径）才不挤。算术只有一份，两个页面各取一套参数，
 * 否则必然出现两套边界。
 *
 * 放在 core 而不是页面里：详情页与播放侧栏（S6）要用同一份算术。
 */
class EpisodePages(val columns: Int, val rows: Int) {

    val perPage: Int get() = columns * rows

    /** 至少一页：没有集数时也返回 1，页面不必为"0 页"写分支。 */
    fun pages(total: Int): Int {
        val safe = total.coerceAtLeast(0)
        return maxOf(1, (safe + perPage - 1) / perPage)
    }

    /** 第 [page] 页（0 起）覆盖的下标区间；越界页夹到最后一页。空清单返回空区间。 */
    fun page(total: Int, page: Int): IntRange {
        val safe = total.coerceAtLeast(0)
        val start = page.coerceIn(0, pages(safe) - 1) * perPage
        return if (start >= safe) IntRange.EMPTY else start until minOf(start + perPage, safe)
    }

    /** 下标落在哪一页（续播定位、聚焦时用）。 */
    fun pageOf(index: Int): Int = index.coerceAtLeast(0) / perPage

    companion object {
        /** 剧集详情页：一行 20 格，一页 5 行 = 100 集。 */
        val DETAIL: EpisodePages = EpisodePages(columns = 20, rows = 5)

        /** 播放侧栏：一行 10 格，一页 5 行 = 50 集（画面要留地方，格不能太密）。 */
        val SIDEBAR: EpisodePages = EpisodePages(columns = 10, rows = 5)
    }
}
