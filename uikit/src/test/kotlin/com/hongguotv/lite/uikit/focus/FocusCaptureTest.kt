package com.hongguotv.lite.uikit.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点记忆只收「用户自己移动」的那一步。
 *
 * 旧焦点视图被移除、收起或禁用时，框架会把光标交给另一个视图（通常是侧栏第一项）；
 * 冷启动的自动落焦同理。这些都不是用户的选择，记下来会让之后的恢复把光标钉在意外位置。
 */
class FocusCaptureTest {

    @Test
    fun `a move from a live view is the user's choice`() {
        assertTrue(
            FocusCapture.active(
                oldFocusShown = true,
                oldFocusAttached = true,
                oldFocusEnabled = true,
            ),
        )
    }

    @Test
    fun `cold start is not a user move`() {
        // 旧焦点为 null 时三项判据全为 false：自动落焦不算用户选择
        assertFalse(
            FocusCapture.active(
                oldFocusShown = false,
                oldFocusAttached = false,
                oldFocusEnabled = false,
            ),
        )
    }

    /** 列表重建时卡片先从行容器里摘掉、`isShown` 还没来得及变 false——这一瞬间的落焦也是被动的。 */
    @Test
    fun `a removed view is passive even before isShown flips`() {
        assertFalse(
            FocusCapture.active(
                oldFocusShown = true,
                oldFocusAttached = false,
                oldFocusEnabled = true,
            ),
        )
    }

    @Test
    fun `hidden or disabled old focus is passive too`() {
        assertFalse(
            FocusCapture.active(
                oldFocusShown = false,
                oldFocusAttached = true,
                oldFocusEnabled = true,
            ),
        )
        assertFalse(
            FocusCapture.active(
                oldFocusShown = true,
                oldFocusAttached = true,
                oldFocusEnabled = false,
            ),
        )
    }
}
