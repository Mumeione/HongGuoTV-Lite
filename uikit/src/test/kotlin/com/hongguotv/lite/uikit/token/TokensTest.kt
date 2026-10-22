package com.hongguotv.lite.uikit.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {

    @Test
    fun `tiers differ only in motion and card limit`() {
        val normal = Tokens.of(UiTier.NORMAL)
        val lowRam = Tokens.of(UiTier.LOW_RAM)

        assertEquals("颜色必须两档一致，否则同一张截图对不上", normal.colors, lowRam.colors)
        assertEquals("字号必须两档一致", normal.type, lowRam.type)
        assertEquals("间距必须两档一致", normal.space, lowRam.space)
        assertEquals("尺寸必须两档一致", normal.metrics, lowRam.metrics)

        assertTrue(normal.motion.enabled)
        assertFalse("低内存档关闭焦点动效", lowRam.motion.enabled)
        assertTrue("低内存档降低单页卡片上限", lowRam.pageCardLimit < normal.pageCardLimit)
    }

    @Test
    fun `tier is chosen by the low ram flag not by resolution`() {
        assertEquals(UiTier.LOW_RAM, UiTier.of(isLowRam = true))
        assertEquals(UiTier.NORMAL, UiTier.of(isLowRam = false))
    }

    @Test
    fun `default tokens are the normal tier`() {
        assertEquals(UiTier.NORMAL, Tokens.Default.tier)
        assertEquals(Tokens.Default, Tokens.of(UiTier.NORMAL))
    }

    @Test
    fun `palette keeps a single accent color`() {
        val colors = Tokens.Default.colors
        assertEquals("强调色只有白色", 0xFFFFFFFF.toInt(), colors.focus)
        assertTrue("深色底上的字必须是浅色", colors.text != colors.background)
    }

    @Test
    fun `type scale is ordered from caption to display title`() {
        val type = Tokens.Default.type
        // 序即视觉层级：页头（heading）必须大于聚焦片名（preview），
        // 否则同一屏上会出现两个"主标题"（维护者实测"片名太大，标题太小"）。
        val scale = listOf(
            type.caption, type.label, type.compact, type.body,
            type.subtitle, type.title, type.preview, type.heading, type.display,
        )
        assertEquals("字号刻度必须单调递增", scale.sorted(), scale)
        assertTrue("页头要比聚焦片名大", type.heading > type.preview)
    }

    @Test
    fun `spacing scale is ordered`() {
        val space = Tokens.Default.space
        val scale = listOf(space.hairline, space.xxs, space.xs, space.s, space.m, space.l, space.xl, space.xxl)
        assertEquals(scale.sorted(), scale)
    }
}
