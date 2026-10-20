package com.hongguotv.lite.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 按键表：同一个键按一下 / 按住 / 松手分别做什么。
 *
 * 这张表是遥控器体验的**唯一事实来源**——旧实现把同样的语义散在 `dispatchKeyEvent` 的
 * 四十个分支里，"长按确认键会连续暂停"这种毛病没人能用测试挡住。
 */
class PlayerKeysTest {

    private fun command(key: PlayerKey, press: PressKind) = PlayerKeys.commandFor(key, press)

    @Test
    fun tappingLeftAndRightSeeksTenSeconds() {
        assertEquals(PlayerCommand.SeekBy(-10_000), command(PlayerKey.LEFT, PressKind.TAP))
        assertEquals(PlayerCommand.SeekBy(10_000), command(PlayerKey.RIGHT, PressKind.TAP))
    }

    @Test
    fun holdingLeftKeepsRewinding() {
        assertEquals(PlayerCommand.SeekBy(-10_000), command(PlayerKey.LEFT, PressKind.REPEAT))
        assertNull(command(PlayerKey.LEFT, PressKind.RELEASE))
    }

    @Test
    fun holdingRightRunsAtDoubleSpeedUntilReleased() {
        assertEquals(PlayerCommand.FastForward(true), command(PlayerKey.RIGHT, PressKind.REPEAT))
        assertEquals(PlayerCommand.FastForward(false), command(PlayerKey.RIGHT, PressKind.RELEASE))
    }

    @Test
    fun upAndDownSwitchEpisodes() {
        assertEquals(PlayerCommand.SkipEpisode(-1), command(PlayerKey.UP, PressKind.TAP))
        assertEquals(PlayerCommand.SkipEpisode(1), command(PlayerKey.DOWN, PressKind.TAP))
    }

    @Test
    fun centreMenuBackAndMediaKeys() {
        assertEquals(PlayerCommand.TogglePlay, command(PlayerKey.CENTER, PressKind.TAP))
        assertEquals(PlayerCommand.OpenSidebar, command(PlayerKey.MENU, PressKind.TAP))
        assertEquals(PlayerCommand.Back, command(PlayerKey.BACK, PressKind.TAP))
        assertEquals(PlayerCommand.Play, command(PlayerKey.MEDIA_PLAY, PressKind.TAP))
        assertEquals(PlayerCommand.Pause, command(PlayerKey.MEDIA_PAUSE, PressKind.TAP))
        assertEquals(PlayerCommand.TogglePlay, command(PlayerKey.MEDIA_TOGGLE, PressKind.TAP))
        assertEquals(PlayerCommand.SkipEpisode(1), command(PlayerKey.MEDIA_NEXT, PressKind.TAP))
        assertEquals(PlayerCommand.SkipEpisode(-1), command(PlayerKey.MEDIA_PREVIOUS, PressKind.TAP))
        assertEquals(PlayerCommand.Back, command(PlayerKey.MEDIA_STOP, PressKind.TAP))
        assertEquals(PlayerCommand.SeekBy(10_000), command(PlayerKey.MEDIA_FORWARD, PressKind.TAP))
        assertEquals(PlayerCommand.SeekBy(-10_000), command(PlayerKey.MEDIA_REWIND, PressKind.TAP))
    }

    /** 只有左右键的连按有意义：长按确认键不能连续暂停/继续。 */
    @Test
    fun repeatingAnyOtherKeyDoesNothing() {
        val others = PlayerKey.entries.filter { it != PlayerKey.LEFT && it != PlayerKey.RIGHT }
        others.forEach { key ->
            assertNull("${key.name} 的连按不该产生命令", command(key, PressKind.REPEAT))
        }
        // 松手只有右键有语义（结束 2 倍速）
        assertNull(command(PlayerKey.CENTER, PressKind.RELEASE))
        assertNull(command(PlayerKey.DOWN, PressKind.RELEASE))
    }

    @Test
    fun digitsAreNotCommandsTheyBelongToTheJumpInput() {
        PlayerKey.entries.filter { it.name.startsWith("DIGIT_") || it == PlayerKey.DELETE }.forEach { key ->
            PressKind.entries.forEach { press -> assertNull(command(key, press)) }
        }
        assertEquals(0, PlayerKeys.digitOf(PlayerKey.DIGIT_0))
        assertEquals(7, PlayerKeys.digitOf(PlayerKey.DIGIT_7))
        assertNull(PlayerKeys.digitOf(PlayerKey.DELETE))
        assertNull(PlayerKeys.digitOf(PlayerKey.CENTER))
    }
}
