package com.hongguotv.lite.feature.player

import com.hongguotv.lite.uikit.focus.FocusGraph
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusPlan
import com.hongguotv.lite.uikit.focus.FocusSection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抽屉的焦点槽位：左列 10 个分集格 + 右列一个设置项，拼成一张 11 列的表。
 *
 * 末页不足 50 集时，缺的格子必须补空槽——否则设置项会落到第 0 行的中间列，
 * 与视觉上的右侧竖排不一致。
 */
class DrawerFocusTest {

    private fun ep(index: Int) = FocusKey("sb:ep:$index")

    private fun set(index: Int) = FocusKey("sb:set:$index")

    private fun section(episodes: Int, settings: Int = 5): FocusSection =
        FocusSection(DrawerFocus.slots((0 until episodes).map(::ep), (0 until settings).map(::set), columns = 10), 11)

    @Test
    fun `a full page puts each setting at the end of its row`() {
        val slots = DrawerFocus.slots((0 until 50).map(::ep), (0 until 5).map(::set), columns = 10)
        assertEquals("5 行 × (10 格 + 1 设置)", 55, slots.size)
        assertEquals(set(0), slots[10])
        assertEquals(ep(10), slots[11])
        assertEquals(set(1), slots[21])
        assertEquals(set(4), slots[54])
    }

    /** 最后一页只有 3 集：8 个空槽把设置项留在第 11 列，而不是紧跟在第 3 个格子后面。 */
    @Test
    fun `the rest of the last row keeps empty slots so the settings stay in column ten`() {
        val slots = DrawerFocus.slots((0 until 3).map(::ep), (0 until 5).map(::set), columns = 10)
        assertEquals(55, slots.size)
        assertEquals(ep(2), slots[2])
        assertEquals(List(7) { null }, slots.subList(3, 10))
        assertEquals(set(0), slots[10])
        assertEquals(set(1), slots[21])
    }

    /** 程序性落焦与方向键都按这张表算：设置项之间上下直走，最后可用的格子按右进设置列。 */
    @Test
    fun `empty slots keep vertical movement inside the settings column`() {
        val graph = FocusGraph.of(FocusPlan(listOf(section(episodes = 3))))
        assertEquals(set(1), graph.neighborsOf(set(0))!!.down)
        assertEquals(set(0), graph.neighborsOf(set(1))!!.up)
        assertEquals(ep(2), graph.neighborsOf(set(0))!!.left)
        assertEquals(set(0), graph.neighborsOf(ep(2))!!.right)
    }
}
