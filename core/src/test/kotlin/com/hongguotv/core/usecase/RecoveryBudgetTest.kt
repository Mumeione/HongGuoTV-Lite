package com.hongguotv.core.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 单集的重试预算：3 次、退避 1s/2.5s/5s，手动重试才清零。 */
class RecoveryBudgetTest {

    @Test
    fun theBudgetIsThreeAttemptsWithGrowingDelays() {
        val budget = RecoveryBudget()
        assertEquals(1_000L, budget.consume())
        assertEquals(2_500L, budget.consume())
        assertEquals(5_000L, budget.consume())
        assertEquals(3, budget.attempts)
        assertNull(budget.consume())
    }

    @Test
    fun aFreshBudgetReportsTheFirstDelayWithoutConsumingIt() {
        val budget = RecoveryBudget()
        assertEquals(1_000L, budget.nextDelayMs)
        assertEquals(0, budget.attempts)
    }

    @Test
    fun resetRestoresTheWholeBudget() {
        val budget = RecoveryBudget()
        repeat(3) { budget.consume() }
        budget.reset()
        assertEquals(0, budget.attempts)
        assertEquals(1_000L, budget.consume())
    }
}
