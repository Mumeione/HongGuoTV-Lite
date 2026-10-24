package com.hongguotv.lite.uikit.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {

    @Test
    fun `tiers differ only in page card limit`() {
        val normal = Tokens.of(UiTier.NORMAL)
        val lowRam = Tokens.of(UiTier.LOW_RAM)

        assertEquals("颜色必须两档一致，否则同一张截图对不上", normal.colors, lowRam.colors)
        assertEquals("字号必须两档一致", normal.type, lowRam.type)
        assertEquals("间距必须两档一致", normal.space, lowRam.space)
        assertEquals("尺寸必须两档一致", normal.metrics, lowRam.metrics)

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
    fun `palette matches the B spec`() {
        val colors = Tokens.Default.colors
        assertEquals("强调色只有白色", 0xFFFFFFFF.toInt(), colors.focus)
        assertTrue("深色底上的字必须是浅色", colors.text != colors.background)
        // B 方案令牌表（docs/specs/2026-10-21-ui-b-redesign.md）钉死的值
        assertEquals(0xFF0B0F15.toInt(), colors.background)
        assertEquals(0xFF171E28.toInt(), colors.surface)
        assertEquals(0xFF242F3D.toInt(), colors.watched)
        assertEquals(0xFF1F2836.toInt(), colors.line)
        assertEquals(0xFFF3F4F6.toInt(), colors.text)
        assertEquals(0xFF8B97A8.toInt(), colors.muted)
        assertEquals(0xFF5E6A7A.toInt(), colors.dim)
        assertEquals(0xFF0B0F15.toInt(), colors.onFocus)
        assertEquals(0xFFE8A0A0.toInt(), colors.danger)
        assertEquals("侧栏与信息栏的底", 0xFF0F151D.toInt(), colors.chrome)
    }

    @Test
    fun `type scale is ordered from corner to page title`() {
        val type = Tokens.Default.type
        val scale = listOf(
            type.corner, type.railLabel, type.micro, type.footnote, type.caption, type.cardStatus,
            type.specValue, type.compact, type.seasonLine, type.body, type.group, type.subtitle,
            type.heading, type.display, type.pageTitle,
        )
        assertEquals("字号刻度必须单调递增", scale.sorted(), scale)
        assertTrue("页面主标题最大（搜索页 28）", type.pageTitle > type.display)
        assertTrue("详情片名比带返回的页头大", type.display > type.heading)
        assertTrue("侧栏项是最小的可读文字", type.railLabel <= type.caption)
    }

    @Test
    fun `card sizes step down from catalog to history to season`() {
        val metrics = Tokens.Default.metrics
        assertTrue("季卡（88）必须窄于历史卡（120）", metrics.seasonCardWidth < metrics.historyCardWidth)
        assertTrue("历史卡（120）必须窄于目录卡（149）", metrics.historyCardWidth < metrics.catalogCardWidth)
        assertTrue("历史信息条（26）矮于目录信息条（34）", metrics.historyStripHeight < metrics.catalogStripHeight)
        assertTrue("季卡封面（126）矮于历史封面（152）", metrics.seasonCoverHeight < metrics.historyCoverHeight)
    }

    @Test
    fun `spotlight dims resting cards but unavailable cards stay dimmer`() {
        val metrics = Tokens.Default.metrics
        assertTrue("聚光静默档在 0 与 1 之间", metrics.focusDimAlpha in 0f..1f)
        assertTrue("未上线（不可用）要比聚光静默更暗", metrics.dimAlpha < metrics.focusDimAlpha)
    }

    @Test
    fun `rail is a narrow icon column`() {
        val metrics = Tokens.Default.metrics
        assertTrue("导航项（56）要放进侧栏（80）", metrics.railItemWidth < metrics.railWidth)
        assertTrue("品牌图标与导航项视觉等大", metrics.railBrandIcon < metrics.railWidth)
        assertTrue("侧栏占屏幕的一小条", metrics.railWidth <= 80)
    }

    @Test
    fun `pills are capsules in two heights`() {
        val tokens = Tokens.Default
        assertEquals("胶囊圆角 99 = 完全胶囊", 99, tokens.shapes.pill)
        assertTrue("小号胶囊（32）矮于常规胶囊（40）", tokens.metrics.pillSmallHeight < tokens.metrics.pillHeight)
        assertTrue("小号胶囊内边距更紧", tokens.metrics.pillSmallPaddingH < tokens.metrics.pillPaddingH)
    }

    @Test
    fun `spacing scale is ordered`() {
        val space = Tokens.Default.space
        val scale = listOf(space.hairline, space.xxs, space.xs, space.s, space.m, space.l, space.xl, space.xxl)
        assertEquals(scale.sorted(), scale)
    }
}
