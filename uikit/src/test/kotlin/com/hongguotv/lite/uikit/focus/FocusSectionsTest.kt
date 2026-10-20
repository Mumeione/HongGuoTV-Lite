package com.hongguotv.lite.uikit.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 焦点段组装：S4/S5 里最容易"按进去出不来"的一处。 */
class FocusSectionsTest {

    @Test
    fun emptySectionsAreDroppedAndOrderIsKept() {
        val nav = FocusSections.section(FocusSections.keys("nav", 4), 4)
        val grid = FocusSections.section(FocusSections.keys("result", 6), 6)

        val plan = FocusSections.plan(listOf(nav, null, grid, null))
        assertEquals(
            listOf("nav:0", "nav:1", "nav:2", "nav:3", "result:0", "result:1", "result:2", "result:3", "result:4", "result:5"),
            plan!!.sections.flatMap { section -> section.keys.map { it.value } },
        )
        assertEquals(4, plan.sections[0].columns)
        assertEquals(6, plan.sections[1].columns)
    }

    @Test
    fun aPageWithoutAnyFocusableItemHasNoPlan() {
        assertNull(FocusSections.plan(listOf(null, null)))
    }

    @Test
    fun zeroKeysNeverBecomeAnEmptySection() {
        assertNull(FocusSections.section(emptyList()))
        assertNull(FocusSections.section(FocusSections.keys("suggest", 0)))
    }

    @Test
    fun keysArePrefixedBySectionName() {
        assertEquals(listOf(FocusKey("suggest:0"), FocusKey("suggest:1")), FocusSections.keys("suggest", 2))
    }
}
