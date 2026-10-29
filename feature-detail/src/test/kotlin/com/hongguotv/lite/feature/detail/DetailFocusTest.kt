package com.hongguotv.lite.feature.detail

import com.hongguotv.lite.uikit.focus.FocusDirection
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情两页的方向键出口：页头检查键、动作列、网格行缘与单页无名条时的夹取规则。 */
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

    /**
     * 页头只剩「检查更新」之后的落点。
     *
     * 进入它的路只有两条：主操作按上/左、选季页排序键按上（见 [sortUpGoesBackToTheCheckButton]）。
     * 它自己按下回主操作——检查完最可能接着看，而它下面是片名与简介，交给几何会落到「选集」。
     */
    @Test
    fun theCheckButtonIsTheTopOfTheActionColumn() {
        val check = FocusKey("detail:check")
        val play = FocusKey("detail:play")
        val pick = FocusKey("detail:pick")
        val favorite = FocusKey("detail:favorite")
        val exits = detailCheckExits(check, play) + detailActionExits(play, pick, favorite, check)

        assertEquals("检查更新按下回主操作", play, exits.target(check, FocusDirection.DOWN))
        assertEquals("检查更新按右也回主操作", play, exits.target(check, FocusDirection.RIGHT))
        assertEquals("主操作按上回检查更新", check, exits.target(play, FocusDirection.UP))
        assertEquals("主操作按左回检查更新", check, exits.target(play, FocusDirection.LEFT))
        assertEquals("检查更新按上夹住自己", check, exits.target(check, FocusDirection.UP))
        assertEquals("检查更新按左夹住自己", check, exits.target(check, FocusDirection.LEFT))
        assertEquals("选集按左夹住自己", pick, exits.target(pick, FocusDirection.LEFT))
        assertEquals("收藏按左夹住自己", favorite, exits.target(favorite, FocusDirection.LEFT))
    }

    /** 没有检查键（未收藏 / 多季剧）：主操作左边是封面，夹住自己，别顺几何进侧栏。 */
    @Test
    fun withoutTheCheckButtonThePrimaryActionClampsLeft() {
        val play = FocusKey("detail:play")
        val pick = FocusKey("detail:pick")
        val favorite = FocusKey("detail:favorite")
        val exits = detailActionExits(play, pick, favorite, check = null)

        assertEquals("没有检查键时主操作按左夹住自己", play, exits.target(play, FocusDirection.LEFT))
        assertNull("不声明上出口，仍由焦点图跨段", exits.target(play, FocusDirection.UP))
    }

    /** 选季页「正序/倒序」按上回页头检查键；左右夹住，不按几何跨到动作列或越过季卡。 */
    @Test
    fun sortUpGoesBackToTheCheckButton() {
        val sort = FocusKey("series:sort")
        val check = FocusKey("series:check")
        val exits = sortExits(sort, check)

        assertEquals("排序按上回检查更新", check, exits.target(sort, FocusDirection.UP))
        assertEquals("排序按左夹住自己", sort, exits.target(sort, FocusDirection.LEFT))
        assertEquals("排序按右夹住自己", sort, exits.target(sort, FocusDirection.RIGHT))

        val without = sortExits(sort, check = null)
        assertNull("没有检查键时按上交给焦点图", without.target(sort, FocusDirection.UP))
        assertEquals("左右仍然夹住", sort, without.target(sort, FocusDirection.RIGHT))
    }

    /**
     * 详情页的异步刷新（季线到手、分类补全）不能把光标从用户手里抢走：
     * 只有"这一次显式要落点"或"页面里根本没人持焦"才动光标。
     */
    @Test
    fun aRefreshLeavesTheCursorWhereTheUserPutIt() {
        // 显式要落点（首次进页 / 点收藏 / 翻组）：照旧把光标交过去，页面里有人持焦也一样
        assertTrue(shouldRequest(pageHasFocus = true, explicit = true))
        assertTrue(shouldRequest(pageHasFocus = false, explicit = true))
        // 刷新型：用户已经把光标移到第 3 张季卡，这一帧什么都不能做
        assertFalse(shouldRequest(pageHasFocus = true, explicit = false))
        // 刷新型但页面里没人持焦（加载中的空窗期）：必须兜回主操作，否则遥控器全灭
        assertTrue(shouldRequest(pageHasFocus = false, explicit = false))
    }
}
