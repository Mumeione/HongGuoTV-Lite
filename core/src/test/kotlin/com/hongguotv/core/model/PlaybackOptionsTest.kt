package com.hongguotv.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 播放档位表：内容与非法值回落。 */
class PlaybackOptionsTest {

    @Test
    fun qualitiesAreTheThreeKnownTiers() {
        assertEquals(listOf(480, 720, 1080), PlaybackOptions.qualities)
    }

    @Test
    fun speedsRunFromThreeQuartersToDouble() {
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), PlaybackOptions.speeds)
    }

    @Test
    fun anUnknownQualityFallsBackTo1080() {
        assertEquals(1080, PlaybackOptions.normalizeQuality(360))
        assertEquals(720, PlaybackOptions.normalizeQuality(720))
    }

    @Test
    fun anUnknownSpeedFallsBackToNormal() {
        assertEquals(1f, PlaybackOptions.normalizeSpeed(0.5f))
        assertEquals(1.5f, PlaybackOptions.normalizeSpeed(1.5f))
    }
}
