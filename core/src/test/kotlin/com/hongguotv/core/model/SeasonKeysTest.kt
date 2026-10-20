package com.hongguotv.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 季号解析规则是旧行为基准的逐条搬运，这些用例全部来自旧仓库的实测样本。 */
class SeasonKeysTest {

    @Test
    fun parsesChineseSeasonNumbers() {
        assertEquals(2, SeasonKeys.seasonNumber("凡人测试书第二季"))
        assertEquals(8, SeasonKeys.seasonNumber("凡人测试书第八季"))
        assertEquals(13, SeasonKeys.seasonNumber("第十三季"))
        // 「两」是中文数字
        assertEquals(2, SeasonKeys.seasonNumber("第两季"))
    }

    @Test
    fun parsesAdjacentDigitsBeforeTheSeasonWord() {
        // "40季" 直连形态，实测存在
        assertEquals(40, SeasonKeys.seasonNumber("之无灵证道40季"))
        // 全角数字
        assertEquals(3, SeasonKeys.seasonNumber("第３季"))
    }

    @Test
    fun doesNotMisfireWithoutTheLeadingMarker() {
        // 中文数字必须保留「第」前缀："斩天十三剑" 不是第十三季
        assertEquals(0, SeasonKeys.seasonNumber("斩天十三剑"))
        assertEquals(0, SeasonKeys.seasonNumber("没有季号的剧"))
    }

    @Test
    fun seasonBaseStripsTheSuffix() {
        assertEquals("魏家逆子成首辅", SeasonKeys.seasonBase("魏家逆子成首辅第六季：魏党篇"))
        assertEquals("没有季号的剧", SeasonKeys.seasonBase("没有季号的剧"))
    }

    @Test
    fun seasonKeyIgnoresPunctuationAndSeason() {
        assertEquals(
            SeasonKeys.seasonKey("血量亿倍增幅，被当成废物异能"),
            SeasonKeys.seasonKey("血量亿倍增幅被当成废物异能"),
        )
        assertEquals(
            SeasonKeys.seasonKey("血量亿倍增幅第六季"),
            SeasonKeys.seasonKey("血量亿倍增幅"),
        )
    }

    @Test
    fun seriesIdsOnlyAcceptDigits() {
        assertEquals(true, SeriesIds.ID.matches("7421"))
        assertEquals(false, SeriesIds.ID.matches(""))
        assertEquals(false, SeriesIds.ID.matches("7421a"))
    }
}
