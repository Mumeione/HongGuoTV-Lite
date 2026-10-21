package com.hongguotv.lite.uikit.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 落焦阶梯。S9 之前"进页落在哪"是隐式的：回退链最后一项是焦点图首项（顶栏 `nav:0`），
 * 于是任何重建都把光标扔回顶栏。这些断言钉住新规则：阶梯在记忆之后、首项之前。
 */
class FocusStartTest {

    private val navSearch = FocusKey("nav:0")
    private val navFavorites = FocusKey("nav:1")
    private val input = FocusKey("search:input")
    private val firstCard = FocusKey("result:1001")
    private val oldCard = FocusKey("result:9")

    private val order = listOf(navSearch, navFavorites, input, firstCard)

    @Test
    fun `preferred wins over everything`() {
        assertEquals(
            oldCard,
            FocusStart.pick(oldCard, firstCard, listOf(input), available = order.toSet() + oldCard, order = order),
        )
    }

    @Test
    fun `memory wins over the page ladder`() {
        assertEquals(
            firstCard,
            FocusStart.pick(null, firstCard, listOf(input), available = order.toSet(), order = order),
        )
    }

    @Test
    fun `ladder is used when there is no memory`() {
        assertEquals(
            firstCard,
            FocusStart.pick(null, null, listOf(firstCard, input), available = order.toSet(), order = order),
        )
    }

    @Test
    fun `ladder falls through to its next entry when the first is missing`() {
        // 搜索结果清空后，第一张卡的 key 已经不在图里了：应当落到搜索框，而不是顶栏
        assertEquals(
            input,
            FocusStart.pick(null, null, listOf(FocusKey("result:gone"), input), available = order.toSet(), order = order),
        )
    }

    @Test
    fun `graph order is only the last resort`() {
        assertEquals(
            navSearch,
            FocusStart.pick(null, null, listOf(FocusKey("result:gone")), available = order.toSet(), order = order),
        )
    }

    @Test
    fun `stale memory is skipped instead of dropping focus`() {
        assertEquals(
            input,
            FocusStart.pick(null, FocusKey("result:gone"), listOf(input), available = order.toSet(), order = order),
        )
    }

    @Test
    fun `nothing available means no focus request`() {
        assertNull(FocusStart.pick(null, null, listOf(input), available = emptySet(), order = emptyList()))
    }

    @Test
    fun `candidates are ordered preferred then memory then ladder then graph order`() {
        assertEquals(
            listOf(oldCard, firstCard, input, navSearch, navFavorites, input, firstCard),
            FocusStart.candidates(oldCard, firstCard, listOf(input), order),
        )
    }

    @Test
    fun `empty ladder keeps the old behaviour`() {
        assertEquals(input, FocusStart.pick(null, null, emptyList(), available = order.toSet(), order = listOf(input)))
    }
}
