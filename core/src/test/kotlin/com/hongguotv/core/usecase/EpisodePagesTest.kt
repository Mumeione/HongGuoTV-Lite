package com.hongguotv.core.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情页（20×5=100）与播放侧栏（10×5=50）两套分页口径共用同一份算术。 */
class EpisodePagesTest {

    private val detail = EpisodePages.DETAIL
    private val sidebar = EpisodePages.SIDEBAR

    @Test
    fun theTwoPagesHaveTheirOwnProductMetrics() {
        assertEquals(20, detail.columns)
        assertEquals(100, detail.perPage)
        // 侧栏：一行 10 格、一页 50 集（维护者口径，画面外只留一小块）
        assertEquals(10, sidebar.columns)
        assertEquals(50, sidebar.perPage)
    }

    @Test
    fun aFullPageOfEpisodesFitsExactlyOnePage() {
        assertEquals(1, detail.pages(1))
        assertEquals(1, detail.pages(100))
        assertEquals(2, detail.pages(101))
        assertEquals(3, detail.pages(201))

        assertEquals(1, sidebar.pages(50))
        assertEquals(2, sidebar.pages(51))
    }

    /** 没有集数时也至少一页：页面不必为"0 页"写分支。 */
    @Test
    fun emptyCatalogStillReportsOnePage() {
        assertEquals(1, detail.pages(0))
        assertTrue(detail.page(0, 0).isEmpty())
        assertEquals(1, sidebar.pages(0))
        assertTrue(sidebar.page(0, 0).isEmpty())
    }

    @Test
    fun pageRangeCoversTheWholeCatalogWithoutOverlap() {
        val first = detail.page(250, 0)
        val second = detail.page(250, 1)
        val third = detail.page(250, 2)

        assertEquals(0..99, first)
        assertEquals(100..199, second)
        assertEquals(200..249, third)
        assertEquals(100, second.count())
    }

    /** 越界页夹到最后一页：遥控器连按「下一组」不该落到空页上。 */
    @Test
    fun outOfRangePageIsClampedToTheLastPage() {
        assertEquals(200..249, detail.page(250, 9))
        assertEquals(0..99, detail.page(250, -3))
        assertEquals(150..199, sidebar.page(200, 7))
    }

    @Test
    fun pageOfLocatesTheEpisodeAndNeverGoesNegative() {
        assertEquals(0, detail.pageOf(0))
        assertEquals(0, detail.pageOf(99))
        assertEquals(1, detail.pageOf(100))
        assertEquals(2, detail.pageOf(205))
        assertEquals(0, detail.pageOf(-5))

        assertEquals(0, sidebar.pageOf(49))
        assertEquals(1, sidebar.pageOf(50))
    }
}
