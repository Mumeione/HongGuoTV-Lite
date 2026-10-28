package com.hongguotv.lite.uikit.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

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
        // 令牌基准值：这些数字是界面设计的硬约定，改动会改变所有页面的比例
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
            type.specLabel, type.specValue, type.compact, type.specValueDetail, type.seasonLine,
            type.body, type.group, type.subtitle, type.heading, type.display, type.pageTitle,
        )
        assertEquals("字号刻度必须单调递增", scale.sorted(), scale)
        assertTrue("页面主标题最大（搜索页 28）", type.pageTitle > type.display)
        assertTrue("详情片名比带返回的页头大", type.display > type.heading)
        assertTrue("侧栏项是最小的可读文字", type.railLabel <= type.caption)
        assertTrue("详情规格表的值比设置行大一档", type.specValueDetail > type.specValue)
    }

    @Test
    fun `two line buttons keep the note clearly smaller than the primary line`() {
        val type = Tokens.Default.type
        // 2026-10-24 验收：「继续播放」下面的「第 1 集」要明显小一档（0.8 → 0.7）
        assertTrue("副行比例上限 0.75", type.noteScale <= 0.75f)
        assertTrue("副行不能小到读不出", type.noteScale >= 0.6f)
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

    // ---------- 全局缩放（4K） ----------

    @Test
    fun `ui scale maps the 960dp design width to one and 4K to two`() {
        assertEquals(1f, UiScale.ofWidthDp(960f), 0.001f)
        assertEquals(2f, UiScale.ofWidthDp(1920f), 0.001f)
        assertEquals(1.33f, UiScale.ofWidthDp(1280f), 0.001f)
    }

    @Test
    fun `ui scale never shrinks below the design width and never exceeds two`() {
        assertEquals("720p 盒子按 960dp 报，不缩小", 1f, UiScale.ofWidthDp(720f), 0.001f)
        assertEquals("8K（3840dp）封顶 2 倍", 2f, UiScale.ofWidthDp(3840f), 0.001f)
        assertEquals("非法宽度退回 1 倍", 1f, UiScale.ofWidthDp(0f), 0.001f)
        assertEquals("NaN 退回 1 倍", 1f, UiScale.ofWidthDp(Float.NaN), 0.001f)
    }

    @Test
    fun `scaled tokens double every physical length`() {
        val big = Tokens.of(UiTier.NORMAL, scale = 2f)
        val base = Tokens.Default

        assertEquals(2f, big.scale, 0.001f)
        assertEquals("详情片名 26 → 52", base.type.display * 2f, big.type.display, 0.001f)
        assertEquals("侧栏 80 → 160", base.metrics.railWidth * 2, big.metrics.railWidth)
        assertEquals("页边距 40 → 80", base.metrics.pagePaddingH * 2, big.metrics.pagePaddingH)
        assertEquals("间距 14 → 28", base.space.l * 2, big.space.l)
        assertEquals("焦点框 3 → 6", base.shapes.focusStroke * 2, big.shapes.focusStroke)
        assertEquals("分集格 26 → 52", base.metrics.episodeCellHeight * 2, big.metrics.episodeCellHeight)
    }

    @Test
    fun `scaling leaves counts ratios and alphas untouched`() {
        val big = Tokens.of(UiTier.NORMAL, scale = 2f)

        assertEquals("列数上限是计数，不是长度", 5, big.metrics.gridMaxColumns)
        assertEquals(3, big.metrics.gridMinColumns)
        assertEquals("分集列数不变", 20, big.metrics.episodeColumns)
        assertEquals("封面比例不变", 0.7f, big.metrics.posterAspect, 0.0001f)
        assertEquals("副行比例不变", 0.7f, big.type.noteScale, 0.0001f)
        assertEquals("行距倍数不变", 1.6f, big.type.lineSpacing, 0.0001f)
        assertEquals("聚光透明度不变", 0.62f, big.metrics.focusDimAlpha, 0.0001f)
        assertEquals("禁用透明度不变", 0.4f, big.metrics.disabledAlpha, 0.0001f)
        assertEquals("胶囊哨兵值不变", 99, big.shapes.pill)
        assertEquals("颜色与缩放无关", Tokens.Default.colors, big.colors)
    }

    @Test
    fun `type scale stays ordered after scaling`() {
        val type = Tokens.of(UiTier.NORMAL, scale = 2f).type
        val ladder = listOf(
            type.corner, type.railLabel, type.micro, type.footnote, type.caption, type.cardStatus,
            type.specLabel, type.specValue, type.compact, type.specValueDetail, type.seasonLine,
            type.body, type.group, type.subtitle, type.heading, type.display, type.pageTitle,
        )
        assertEquals("等比放大后字号顺序不能乱", ladder.sorted(), ladder)
    }

    @Test
    fun `scale one is the historical token set`() {
        assertEquals(1f, Tokens.Default.scale, 0.001f)
        assertEquals(Tokens.Default, Tokens.of(UiTier.NORMAL, scale = 1f))
    }

    /**
     * 缩放是**逐字段手写**的（数据类有 70 多个尺寸），漏一个就是"4K 上某处还是小一号"，
     * 而这种漏只能靠肉眼在电视上发现。这里用反射兜底：新增字段若没进 `scaled()`，
     * 失败信息会点名字段——是物理长度就补上，是比例/计数就加进 skip 并写清理由。
     */
    @Test
    fun `every physical token field is scaled`() {
        val base = Tokens.Default
        val big = Tokens.of(UiTier.NORMAL, scale = 2f)

        // 不是"物理长度"的字段：计数、比例、透明度，以及胶囊的哨兵值
        val skipped = mapOf(
            "type" to setOf("lineSpacing", "noteScale"),
            "space" to emptySet(),
            "shapes" to setOf("pill"),
            "metrics" to setOf(
                "disabledAlpha", "focusDimAlpha", "dimAlpha",
                "introMaxLines", "episodeColumns", "gridMinColumns", "gridMaxColumns",
                "posterAspect", "sidebarWidthRatio", "jumpDigitsScale",
            ),
        )

        fun verify(owner: String, skip: Set<String>, small: Any, large: Any, minFields: Int) {
            val fields = small.javaClass.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            // 反射读不到字段时上面的断言会全过——先钉住"确实读到了这么多字段"
            assertTrue("$owner 只反射到 ${fields.size} 个字段，兜底测试要失效了", fields.size >= minFields)
            fields.forEach { field ->
                if (field.name in skip) return@forEach
                field.isAccessible = true
                val hint = "$owner.${field.name} 没乘 scale：物理长度要补进 scaled()，比例/计数加进 skip"
                when (val value = field.get(small)) {
                    is Int -> assertEquals(hint, value * 2, field.get(large) as Int)
                    is Float -> assertEquals(hint, value * 2f, field.get(large) as Float, 0.001f)
                }
            }
        }

        verify("type", skipped.getValue("type"), base.type, big.type, minFields = 17)
        verify("space", skipped.getValue("space"), base.space, big.space, minFields = 8)
        verify("shapes", skipped.getValue("shapes"), base.shapes, big.shapes, minFields = 9)
        verify("metrics", skipped.getValue("metrics"), base.metrics, big.metrics, minFields = 70)
    }
}
