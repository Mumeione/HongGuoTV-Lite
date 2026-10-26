package com.hongguotv.core.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面加载兜底：取消原样抛出，其余异常交给 onFailure 并返回 null。 */
class LoadCatchingTest {

    @Test
    fun returnsTheValueWhenTheBlockSucceeds() {
        assertEquals(3, runBlocking { loadCatching { 1 + 2 } })
    }

    @Test
    fun handsNonCancellationExceptionsToOnFailureAndReturnsNull() {
        val seen = mutableListOf<Throwable>()
        val result = runBlocking { loadCatching({ seen += it }) { error("非法 id") } }
        assertNull(result)
        assertEquals(1, seen.size)
        assertTrue(seen.single() is IllegalStateException)
    }

    @Test
    fun rethrowsCancellationWithoutTouchingOnFailure() {
        var failures = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                loadCatching({ failures++ }) { throw CancellationException("页面销毁") }
            }
        }
        assertEquals(0, failures)
    }
}
