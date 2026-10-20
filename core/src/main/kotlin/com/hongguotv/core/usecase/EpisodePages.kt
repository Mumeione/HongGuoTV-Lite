package com.hongguotv.core.usecase

/**
 * 分集清单的分页算术：电视一行放 20 格，一页 5 行共 100 集。
 *
 * 为什么是 100：遥控器上"翻页"比"上下移动"贵得多，一次给满一页，选集时少翻好几次（旧版实测口径）。
 * 放在 core 而不是页面里：详情页与播放侧栏（S6）要用同一份算术，各写一遍必然出现两套边界。
 */
object EpisodePages {

    const val COLUMNS = 20
    const val ROWS = 5
    const val PER_PAGE = COLUMNS * ROWS

    /** 至少一页：没有集数时也返回 1，页面不必为"0 页"写分支。 */
    fun pages(total: Int): Int {
        val safe = total.coerceAtLeast(0)
        return maxOf(1, (safe + PER_PAGE - 1) / PER_PAGE)
    }

    /** 第 [page] 页（0 起）覆盖的下标区间；越界页夹到最后一页。空清单返回空区间。 */
    fun page(total: Int, page: Int): IntRange {
        val safe = total.coerceAtLeast(0)
        val start = page.coerceIn(0, pages(safe) - 1) * PER_PAGE
        return if (start >= safe) IntRange.EMPTY else start until minOf(start + PER_PAGE, safe)
    }

    /** 下标落在哪一页（续播定位、聚焦时用）。 */
    fun pageOf(index: Int): Int = index.coerceAtLeast(0) / PER_PAGE
}
