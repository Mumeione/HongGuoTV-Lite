package com.hongguotv.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 档位循环：下一档、末档回绕、脏值回落、空表不动。 */
class OptionsTest {

    @Test
    fun itAdvancesAndWrapsAround() {
        assertEquals(720, PlaybackOptions.qualities.nextAfter(480))
        assertEquals(480, PlaybackOptions.qualities.nextAfter(1080))
        assertEquals(1.25f, PlaybackOptions.speeds.nextAfter(1f))
        assertEquals(0.75f, PlaybackOptions.speeds.nextAfter(2f))
    }

    @Test
    fun aValueOutsideTheOptionsFallsBackToTheFirst() {
        assertEquals(480, PlaybackOptions.qualities.nextAfter(999))
        assertEquals(0.75f, PlaybackOptions.speeds.nextAfter(3f))
    }

    @Test
    fun anEmptyTableKeepsTheCurrentValue() {
        assertEquals("x", emptyList<String>().nextAfter("x"))
    }
}
