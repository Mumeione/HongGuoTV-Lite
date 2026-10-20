package com.hongguotv.core.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 跳集输入：1 起的集号 → 0 起的下标。越界不夹住（夹住等于骗用户"跳到了"）。 */
class EpisodeJumpTest {

    @Test
    fun parsesAValidEpisodeNumber() {
        assertEquals(0, EpisodeJump.parse("1", 30))
        assertEquals(8, EpisodeJump.parse("9", 30))
        assertEquals(29, EpisodeJump.parse("30", 30))
    }

    @Test
    fun trimsSurroundingSpaces() {
        assertEquals(6, EpisodeJump.parse("  7 ", 30))
    }

    @Test
    fun rejectsZeroAndOutOfRange() {
        assertNull(EpisodeJump.parse("0", 30))
        assertNull(EpisodeJump.parse("31", 30))
        assertNull(EpisodeJump.parse("999999", 30))
    }

    @Test
    fun rejectsNonNumericInput() {
        assertNull(EpisodeJump.parse("", 30))
        assertNull(EpisodeJump.parse("  ", 30))
        assertNull(EpisodeJump.parse("abc", 30))
        assertNull(EpisodeJump.parse("1.5", 30))
        assertNull(EpisodeJump.parse("-1", 30))
    }

    @Test
    fun rejectsMoreThanSixDigits() {
        assertNull(EpisodeJump.parse("1000000", 2_000_000))
    }

    @Test
    fun leadingZerosAreAcceptedAsTheSameEpisode() {
        assertEquals(6, EpisodeJump.parse("007", 30))
    }

    @Test
    fun anEmptyCatalogueAcceptsNothing() {
        assertNull(EpisodeJump.parse("1", 0))
    }
}
