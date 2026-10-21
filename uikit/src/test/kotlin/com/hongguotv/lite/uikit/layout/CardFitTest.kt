package com.hongguotv.lite.uikit.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片封面高度夹取。错误表现是"标题被推到首屏外"，只在电视上肉眼可见——
 * 所以这段算术必须在 JVM 里钉住。
 */
class CardFitTest {

    @Test
    fun `no limit keeps the poster ratio`() {
        assertEquals(374, CardFit.artworkHeight(262, 0.7f, 0, 120, 96))
    }

    @Test
    fun `roomy viewport keeps the poster ratio`() {
        assertEquals(374, CardFit.artworkHeight(262, 0.7f, 600, 120, 96))
    }

    @Test
    fun `exactly enough room keeps the poster ratio`() {
        assertEquals(374, CardFit.artworkHeight(262, 0.7f, 494, 120, 96))
    }

    @Test
    fun `tight viewport clamps the artwork so the text still fits`() {
        // 视口 400、文字块 120 → 封面最多 280；卡片整高正好回到 400
        assertEquals(280, CardFit.artworkHeight(262, 0.7f, 400, 120, 96))
    }

    @Test
    fun `never clamps below the floor`() {
        // 视口只够 20，但封面不能小到看不清：保底 96（卡片会略微超出视口）
        assertEquals(96, CardFit.artworkHeight(262, 0.7f, 140, 120, 96))
    }

    @Test
    fun `negative room still respects the floor`() {
        assertEquals(96, CardFit.artworkHeight(262, 0.7f, 100, 500, 96))
    }

    @Test
    fun `degenerate width does not produce a zero height`() {
        assertEquals(1, CardFit.artworkHeight(0, 0.7f, 0, 120, 96))
    }
}
