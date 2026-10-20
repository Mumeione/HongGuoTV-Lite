package com.hongguotv.lite.feature.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 破坏性操作的两步确认。S4 没有模态确认框，删除记录与清空历史都靠它：
 * 第一次按下只进入确认态，同一个键再按才执行；焦点移开或换键都会撤销。
 */
class TwoStepConfirmTest {

    @Test
    fun theSameKeyMustBePressedTwice() {
        val confirm = TwoStepConfirm()
        val key = "delete:1"

        assertFalse(confirm.press(key))
        assertTrue(confirm.isArmed(key))
        assertTrue(confirm.press(key))
        assertFalse("确认后应当撤销，不能连按第三次又删一次", confirm.isArmed(key))
    }

    @Test
    fun movingToAnotherKeyRearmsInsteadOfConfirming() {
        val confirm = TwoStepConfirm()
        assertFalse(confirm.press("delete:1"))
        assertFalse(confirm.press("delete:2"))
        assertFalse(confirm.isArmed("delete:1"))
        assertTrue(confirm.isArmed("delete:2"))
        assertTrue(confirm.press("delete:2"))
    }

    @Test
    fun disarmCancelsAPendingConfirmation() {
        val confirm = TwoStepConfirm()
        confirm.press("clear-history")
        // 焦点离开按钮 = 撤销：挪开再回来不该是一次按键就删
        confirm.disarm()
        assertFalse(confirm.press("clear-history"))
    }
}
