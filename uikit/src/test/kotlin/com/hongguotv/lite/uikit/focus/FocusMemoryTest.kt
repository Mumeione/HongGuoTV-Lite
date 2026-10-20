package com.hongguotv.lite.uikit.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMemoryTest {

    private val card0 = FocusKey("card:0")
    private val card7 = FocusKey("card:7")

    @Test
    fun `recalls the key it was given`() {
        val memory = FocusMemory()
        memory.remember("catalog:搜索", card7)
        assertEquals(card7, memory.recall("catalog:搜索"))
    }

    @Test
    fun `pages do not see each other`() {
        val memory = FocusMemory()
        memory.remember("catalog:收藏", card0)
        memory.remember("catalog:历史", card7)
        assertEquals(card0, memory.recall("catalog:收藏"))
        assertEquals(card7, memory.recall("catalog:历史"))
        assertNull(memory.recall("detail:123"))
    }

    @Test
    fun `remembering null forgets the page`() {
        val memory = FocusMemory()
        memory.remember("catalog", card7)
        memory.remember("catalog", null)
        assertNull(memory.recall("catalog"))
    }

    @Test
    fun `forget and clear only remove what they name`() {
        val memory = FocusMemory()
        memory.remember("a", card0)
        memory.remember("b", card7)
        memory.forget("a")
        assertNull(memory.recall("a"))
        assertEquals(card7, memory.recall("b"))
        memory.clear()
        assertNull(memory.recall("b"))
    }
}

class KeyRepeatGateTest {

    @Test
    fun `first press is always accepted`() {
        val gate = KeyRepeatGate(110)
        assertTrue(gate.accept(5_000))
    }

    @Test
    fun `repeats inside the interval are rejected`() {
        val gate = KeyRepeatGate(110)
        assertTrue(gate.accept(1_000))
        assertFalse(gate.accept(1_050))
        assertFalse(gate.accept(1_109))
    }

    @Test
    fun `repeat exactly at the interval is accepted`() {
        val gate = KeyRepeatGate(110)
        assertTrue(gate.accept(1_000))
        assertTrue(gate.accept(1_110))
    }

    @Test
    fun `reset arms the gate again so a fresh press is never dropped`() {
        val gate = KeyRepeatGate(110)
        assertTrue(gate.accept(1_000))
        gate.reset()
        assertTrue("新按下必须立刻被接受", gate.accept(1_001))
    }

    @Test
    fun `zero interval accepts everything`() {
        val gate = KeyRepeatGate(0)
        assertTrue(gate.accept(1_000))
        assertTrue(gate.accept(1_000))
    }
}
