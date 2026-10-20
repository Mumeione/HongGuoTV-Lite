package com.hongguotv.lite.feature.catalog

import com.hongguotv.lite.uikit.focus.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 焦点段组装与两步确认：S4 里最容易"按进去出不来"的两处。 */
class CatalogSectionsTest {

    @Test
    fun emptySectionsAreDroppedAndOrderIsKept() {
        val nav = CatalogSections.section(CatalogSections.keys("nav", 4), 4)
        val grid = CatalogSections.section(CatalogSections.keys("result", 6), 6)

        val plan = CatalogSections.plan(listOf(nav, null, grid, null))
        assertEquals(
            listOf("nav:0", "nav:1", "nav:2", "nav:3", "result:0", "result:1", "result:2", "result:3", "result:4", "result:5"),
            plan!!.sections.flatMap { section -> section.keys.map { it.value } },
        )
        assertEquals(4, plan.sections[0].columns)
        assertEquals(6, plan.sections[1].columns)
    }

    @Test
    fun aPageWithoutAnyFocusableItemHasNoPlan() {
        assertNull(CatalogSections.plan(listOf(null, null)))
    }

    @Test
    fun zeroKeysNeverBecomeAnEmptySection() {
        assertNull(CatalogSections.section(emptyList()))
        assertNull(CatalogSections.section(CatalogSections.keys("suggest", 0)))
    }

    @Test
    fun keysArePrefixedBySectionName() {
        assertEquals(listOf(FocusKey("suggest:0"), FocusKey("suggest:1")), CatalogSections.keys("suggest", 2))
    }
}
