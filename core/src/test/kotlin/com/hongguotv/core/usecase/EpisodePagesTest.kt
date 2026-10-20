package com.hongguotv.core.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePagesTest {

    @Test
    fun aHundredEpisodesFitExactlyOnePage() {
        assertEquals(20, EpisodePages.COLUMNS)
        assertEquals(5, EpisodePages.ROWS)
        assertEquals(100, EpisodePages.PER_PAGE)

        assertEquals(1, EpisodePages.pages(1))
        assertEquals(1, EpisodePages.pages(100))
        assertEquals(2, EpisodePages.pages(101))
        assertEquals(3, EpisodePages.pages(201))
    }

    /** 没有集数时也至少一页：页面不必为"0 页"写分支。 */
    @Test
    fun emptyCatalogStillReportsOnePage() {
        assertEquals(1, EpisodePages.pages(0))
        assertTrue(EpisodePages.page(0, 0).isEmpty())
    }

    @Test
    fun pageRangeCoversTheWholeCatalogWithoutOverlap() {
        val first = EpisodePages.page(250, 0)
        val second = EpisodePages.page(250, 1)
        val third = EpisodePages.page(250, 2)

        assertEquals(0..99, first)
        assertEquals(100..199, second)
        assertEquals(200..249, third)
        assertEquals(100, second.count())
    }

    /** 越界页夹到最后一页：遥控器连按「下一组」不该落到空页上。 */
    @Test
    fun outOfRangePageIsClampedToTheLastPage() {
        assertEquals(200..249, EpisodePages.page(250, 9))
        assertEquals(0..99, EpisodePages.page(250, -3))
    }

    @Test
    fun pageOfLocatesTheEpisodeAndNeverGoesNegative() {
        assertEquals(0, EpisodePages.pageOf(0))
        assertEquals(0, EpisodePages.pageOf(99))
        assertEquals(1, EpisodePages.pageOf(100))
        assertEquals(2, EpisodePages.pageOf(205))
        assertEquals(0, EpisodePages.pageOf(-5))
    }
}
