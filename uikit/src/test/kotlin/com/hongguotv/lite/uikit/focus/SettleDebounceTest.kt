package com.hongguotv.lite.uikit.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点落定的一帧防抖（`SettleDebounce`）：同帧内的多次提交只执行最后一次。
 *
 * 页面的信息栏更新挂在落定回调上（审计 2026-10-22 #5-c / #6），中间态（焦点"路过"
 * 某张卡）必须被滤掉——这条行为在这里用假执行器钉死，不需要起 Activity。
 */
class SettleDebounceTest {

    /** 手动驱动的执行器：攒下所有提交的 Runnable，测试里自己决定何时跑。 */
    private class ManualExecutor {
        val posted = mutableListOf<Runnable>()
        fun post(runnable: Runnable) {
            posted += runnable
        }

        fun runAll() {
            val snapshot = posted.toList()
            posted.clear()
            snapshot.forEach { it.run() }
        }
    }

    @Test
    fun `only the latest submission runs`() {
        val executor = ManualExecutor()
        val debounce = SettleDebounce(executor::post)
        val seen = mutableListOf<String>()

        debounce.submit { seen += "first" }
        debounce.submit { seen += "second" }
        assertEquals("执行前什么都不跑", emptyList<String>(), seen)

        executor.runAll()
        assertEquals(listOf("second"), seen)
    }

    @Test
    fun `a submission that runs does not cancel later ones`() {
        val executor = ManualExecutor()
        val debounce = SettleDebounce(executor::post)
        val seen = mutableListOf<Int>()

        debounce.submit { seen += 1 }
        executor.runAll()
        debounce.submit { seen += 2 }
        executor.runAll()

        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `stale runnables arriving late are ignored`() {
        // 框架可能把先前提交的 Runnable 拖到更晚执行（与新一轮提交交错）：
        // 过期的代次必须自动作废，不能把旧焦点当成"落定"
        val executor = ManualExecutor()
        val debounce = SettleDebounce(executor::post)
        val seen = mutableListOf<String>()

        debounce.submit { seen += "stale" }
        val stale = executor.posted.removeAt(0)
        debounce.submit { seen += "fresh" }
        executor.runAll()
        stale.run()

        assertEquals(listOf("fresh"), seen)
        assertTrue("过期任务不能重复执行", seen.size == 1)
    }
}
