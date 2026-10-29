package com.hongguotv.lite.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面尺寸算术的三个外部行为：不放大、等比缩进目标框、2 的幂降采样。
 *
 * 这些都是"真机上看得见"的性质——竖版海报被切掉一半、卡片糊成马赛克、
 * 老电视一次分配几十 MB 崩掉，根因都在这里。
 */
class ArtworkSizingTest {

    @Test
    fun `源图比目标小就不放大`() {
        val target = ArtworkSizing.targetSize(sourceWidth = 120, sourceHeight = 160, maxWidth = 300, maxHeight = 400)
        assertEquals(ArtworkSizing.Size(120, 160), target)
    }

    @Test
    fun `竖版海报等比缩进目标框 不裁切`() {
        // 1000×2000 塞进 300×300：高度先到顶，宽度跟着只到 150
        val target = ArtworkSizing.targetSize(1000, 2000, 300, 300)
        assertEquals(ArtworkSizing.Size(150, 300), target)
    }

    @Test
    fun `横版图同样按短边受限`() {
        val target = ArtworkSizing.targetSize(2000, 1000, 300, 400)
        assertEquals(ArtworkSizing.Size(300, 150), target)
    }

    @Test
    fun `极端长宽比也不会缩成零宽`() {
        val target = ArtworkSizing.targetSize(4000, 40, 400, 400)
        assertEquals(400, target.width)
        assertTrue(target.height >= 1)
    }

    @Test
    fun `尺寸缺失时退回 1x1 而不是 0`() {
        assertEquals(ArtworkSizing.Size(1, 1), ArtworkSizing.targetSize(0, 100, 300, 300))
        assertEquals(ArtworkSizing.Size(1, 1), ArtworkSizing.targetSize(100, 100, 0, 300))
        assertEquals(ArtworkSizing.Size(1, 1), ArtworkSizing.targetSize(-5, -5, -1, -1))
    }

    @Test
    fun `降采样取 2 的幂且解码后仍不小于目标`() {
        // 1000×2000 → 目标 150×300：8 会小到 125（不够宽），所以是 4
        val target = ArtworkSizing.targetSize(1000, 2000, 150, 300)
        assertEquals(4, ArtworkSizing.sampleSize(1000, 2000, target))
    }

    @Test
    fun `刚好同尺寸时不降采样`() {
        val target = ArtworkSizing.targetSize(300, 450, 300, 450)
        assertEquals(1, ArtworkSizing.sampleSize(300, 450, target))
    }

    @Test
    fun `原图比目标小也不降采样`() {
        assertEquals(1, ArtworkSizing.sampleSize(120, 160, ArtworkSizing.Size(300, 400)))
    }

    @Test
    fun `源尺寸坏掉时采样率退回 1`() {
        assertEquals(1, ArtworkSizing.sampleSize(0, 0, ArtworkSizing.Size(100, 100)))
    }

    @Test
    fun `解码预算按像素数判断`() {
        assertFalse(ArtworkSizing.exceedsDecodeBudget(ArtworkSizing.Size(1000, 1000), 1_000_000))
        assertTrue(ArtworkSizing.exceedsDecodeBudget(ArtworkSizing.Size(1001, 1000), 1_000_000))
    }

    // ---------- 缓存键的尺寸归桶（2026-10-28）----------

    @Test
    fun `缓存尺寸向上归到 64 的整桶`() {
        // 1080p 卡宽 298、封面高 426 → 一个桶里共用同一张图
        assertEquals(ArtworkSizing.Size(320, 448), ArtworkSizing.cacheSize(298, 426))
        // 两个相差 2px 的请求必须是同一个键：布局抖动不该换图
        assertEquals(ArtworkSizing.cacheSize(298, 426), ArtworkSizing.cacheSize(300, 425))
    }

    @Test
    fun `刚好落在桶边界时不再加一桶`() {
        assertEquals(ArtworkSizing.Size(320, 320), ArtworkSizing.cacheSize(320, 320))
        assertEquals(ArtworkSizing.Size(384, 384), ArtworkSizing.cacheSize(321, 321))
    }

    @Test
    fun `尺寸缺失或为负时退回一个最小桶`() {
        assertEquals(ArtworkSizing.Size(64, 64), ArtworkSizing.cacheSize(0, 0))
        assertEquals(ArtworkSizing.Size(64, 64), ArtworkSizing.cacheSize(-10, -1))
    }
}
