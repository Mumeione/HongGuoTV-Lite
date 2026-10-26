package com.hongguotv.lite.feature.detail

import com.hongguotv.lite.uikit.focus.FocusDirection
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 详情两页的方向键出口：返回、网格行缘与单页无名条时的夹取规则。 */
class DetailFocusTest {

    private fun cells(count: Int): List<FocusKey> = (1..count).map { FocusKey("ep:$it") }

    @Test
    fun gridRowEdgesClampWhenThereIsNoStrip() {
        val all = cells(20)
        val exits = gridEdgeExits(all, columns = 20, leftFlank = null, rightFlank = null)

        assertEquals("行首按左夹住自己", all.first(), exits.target(all.first(), FocusDirection.LEFT))
        assertEquals("行尾按右夹住自己", all[19], exits.target(all[19], FocusDirection.RIGHT))
        assertNull("行内按左仍由几何走到前一格", exits.target(all[1], FocusDirection.LEFT))
    }

    @Test
    fun partialLastRowClampsOnBothSides() {
        val all = cells(81)
        val exits = gridEdgeExits(all, columns = 20, leftFlank = null, rightFlank = null)

        assertEquals("末行只有一格：按左夹住", all[80], exits.target(all[80], FocusDirection.LEFT))
        assertEquals("末行只有一格：按右也夹住", all[80], exits.target(all[80], FocusDirection.RIGHT))
    }

    @Test
    fun gridRowEdgesKeepTheStripsWhenTheyAreOnScreen() {
        val all = cells(14)
        val left = FocusSection(listOf(FocusKey("prev")), columns = 1)
        val right = FocusSection(listOf(FocusKey("next")), columns = 1)
        val exits = gridEdgeExits(all, columns = 7, leftFlank = left, rightFlank = right)

        assertNull("有左侧长条时不抢它的落点", exits.target(all.first(), FocusDirection.LEFT))
        assertNull("有右侧长条时不抢它的落点", exits.target(all[6], FocusDirection.RIGHT))
    }

    @Test
    fun backDownGoesToThePrimaryAction() {
        val back = FocusKey("detail:back")
        val play = FocusKey("detail:play")
        val pick = FocusKey("detail:pick")
        val favorite = FocusKey("detail:favorite")
        val exits = detailActionExits(back, play, pick, favorite)

        assertEquals("返回按下回主操作", play, exits.target(back, FocusDirection.DOWN))
        assertEquals("动作列按左回返回", back, exits.target(play, FocusDirection.LEFT))
        assertEquals("动作列按左回返回", back, exits.target(favorite, FocusDirection.LEFT))
        assertNull("返回按右不设出口，交给视觉几何", exits.target(back, FocusDirection.RIGHT))
    }
}
