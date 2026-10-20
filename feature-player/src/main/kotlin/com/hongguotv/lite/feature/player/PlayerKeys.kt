package com.hongguotv.lite.feature.player

/**
 * 按键表：**播放页能做的事，全部由这张表回答**。
 *
 * 为什么不用 `when (keyCode)` 散在页面里：电视遥控器的语义是"同一个键按一下 / 按住 / 松手"三件事，
 * 散着写必然有一处忘了 `repeatCount`（旧实现为此踩过"按住 3 会变成 333"）。
 * 这里把 Android 的 `KeyEvent` 翻译成 [PlayerKey] 之后，三态就是三个参数，
 * 纯函数、可逐条断言，页面只负责"过了节流闸再执行"。
 */
enum class PlayerKey {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    CENTER,
    MENU,
    BACK,
    MEDIA_PLAY,
    MEDIA_PAUSE,
    MEDIA_TOGGLE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    MEDIA_STOP,
    MEDIA_FORWARD,
    MEDIA_REWIND,
    /** 数字键 0—9（只用于侧栏的跳集输入）。 */
    DIGIT_0,
    DIGIT_1,
    DIGIT_2,
    DIGIT_3,
    DIGIT_4,
    DIGIT_5,
    DIGIT_6,
    DIGIT_7,
    DIGIT_8,
    DIGIT_9,
    DELETE,
}

/** 一次按键的三态。 */
enum class PressKind {
    /** 刚按下（`repeatCount == 0`）。 */
    TAP,

    /** 按住不放时系统的重复事件（`repeatCount > 0`）。 */
    REPEAT,

    /** 松手（`ACTION_UP`）。 */
    RELEASE,
}

/** 播放页的命令。页面照着执行，不自己解释按键。 */
sealed interface PlayerCommand {
    data class SeekBy(val deltaMs: Long) : PlayerCommand
    data class FastForward(val on: Boolean) : PlayerCommand
    data class SkipEpisode(val delta: Int) : PlayerCommand
    data object TogglePlay : PlayerCommand
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data object OpenSidebar : PlayerCommand
    data object Back : PlayerCommand
}

object PlayerKeys {

    /** ←→ 每次 10 秒（旧口径）。 */
    const val SEEK_STEP_MS = 10_000L

    /** 按住右键的快进倍率。写死 2×：电视解码能力有限，再快只是掉帧。 */
    const val FAST_FORWARD_SPEED = 2f

    /**
     * 按键 → 命令。
     *
     * 两条刻意的"没有命令"：
     * - 只有左右键的连按有意义（连续快退/快进）。其它键的 REPEAT 一律返回 null，
     *   否则长按确认键会连续暂停/继续，看起来像卡住；
     * - 数字键与退格不在这里产生命令——它们属于侧栏的输入框，由侧栏自己消费。
     */
    fun commandFor(key: PlayerKey, press: PressKind): PlayerCommand? = when (key) {
        PlayerKey.LEFT -> when (press) {
            PressKind.TAP, PressKind.REPEAT -> PlayerCommand.SeekBy(-SEEK_STEP_MS)
            PressKind.RELEASE -> null
        }

        PlayerKey.RIGHT -> when (press) {
            PressKind.TAP -> PlayerCommand.SeekBy(SEEK_STEP_MS)
            PressKind.REPEAT -> PlayerCommand.FastForward(true)
            PressKind.RELEASE -> PlayerCommand.FastForward(false)
        }

        PlayerKey.UP -> if (press == PressKind.TAP) PlayerCommand.SkipEpisode(-1) else null
        PlayerKey.DOWN -> if (press == PressKind.TAP) PlayerCommand.SkipEpisode(1) else null
        PlayerKey.CENTER -> if (press == PressKind.TAP) PlayerCommand.TogglePlay else null
        PlayerKey.MENU -> if (press == PressKind.TAP) PlayerCommand.OpenSidebar else null
        PlayerKey.BACK -> if (press == PressKind.TAP) PlayerCommand.Back else null
        PlayerKey.MEDIA_PLAY -> if (press == PressKind.TAP) PlayerCommand.Play else null
        PlayerKey.MEDIA_PAUSE -> if (press == PressKind.TAP) PlayerCommand.Pause else null
        PlayerKey.MEDIA_TOGGLE -> if (press == PressKind.TAP) PlayerCommand.TogglePlay else null
        PlayerKey.MEDIA_NEXT -> if (press == PressKind.TAP) PlayerCommand.SkipEpisode(1) else null
        PlayerKey.MEDIA_PREVIOUS -> if (press == PressKind.TAP) PlayerCommand.SkipEpisode(-1) else null
        PlayerKey.MEDIA_STOP -> if (press == PressKind.TAP) PlayerCommand.Back else null
        PlayerKey.MEDIA_FORWARD -> if (press == PressKind.TAP) PlayerCommand.SeekBy(SEEK_STEP_MS) else null
        PlayerKey.MEDIA_REWIND -> if (press == PressKind.TAP) PlayerCommand.SeekBy(-SEEK_STEP_MS) else null
        PlayerKey.DIGIT_0, PlayerKey.DIGIT_1, PlayerKey.DIGIT_2, PlayerKey.DIGIT_3, PlayerKey.DIGIT_4,
        PlayerKey.DIGIT_5, PlayerKey.DIGIT_6, PlayerKey.DIGIT_7, PlayerKey.DIGIT_8, PlayerKey.DIGIT_9,
        PlayerKey.DELETE,
        -> null
    }

    /** 数字键对应的数字；不是数字键返回 null。 */
    fun digitOf(key: PlayerKey): Int? = when (key) {
        PlayerKey.DIGIT_0 -> 0
        PlayerKey.DIGIT_1 -> 1
        PlayerKey.DIGIT_2 -> 2
        PlayerKey.DIGIT_3 -> 3
        PlayerKey.DIGIT_4 -> 4
        PlayerKey.DIGIT_5 -> 5
        PlayerKey.DIGIT_6 -> 6
        PlayerKey.DIGIT_7 -> 7
        PlayerKey.DIGIT_8 -> 8
        PlayerKey.DIGIT_9 -> 9
        else -> null
    }
}
