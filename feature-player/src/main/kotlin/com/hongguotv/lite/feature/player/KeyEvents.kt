package com.hongguotv.lite.feature.player

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Android 的 `KeyEvent` → [PlayerKey] 的**唯一**翻译点。
 *
 * 单独一个文件、只有一张 `when`：按键语义（按一下/按住/松手做什么）在 [PlayerKeys] 的表里，
 * 这里只负责"这个数字编号是哪个键"。混在一起写就又会退化成旧实现那种四十个分支的 `dispatchKeyEvent`。
 */
internal fun playerKeyOf(keyCode: Int): PlayerKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> PlayerKey.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerKey.RIGHT
    KeyEvent.KEYCODE_DPAD_UP -> PlayerKey.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> PlayerKey.DOWN
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> PlayerKey.CENTER
    KeyEvent.KEYCODE_MENU -> PlayerKey.MENU
    KeyEvent.KEYCODE_BACK -> PlayerKey.BACK
    KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerKey.MEDIA_PLAY
    KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerKey.MEDIA_PAUSE
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> PlayerKey.MEDIA_TOGGLE
    KeyEvent.KEYCODE_MEDIA_NEXT -> PlayerKey.MEDIA_NEXT
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlayerKey.MEDIA_PREVIOUS
    KeyEvent.KEYCODE_MEDIA_STOP -> PlayerKey.MEDIA_STOP
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerKey.MEDIA_FORWARD
    KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerKey.MEDIA_REWIND
    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> PlayerKey.DELETE
    KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> PlayerKey.DIGIT_0
    KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> PlayerKey.DIGIT_1
    KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> PlayerKey.DIGIT_2
    KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> PlayerKey.DIGIT_3
    KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> PlayerKey.DIGIT_4
    KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> PlayerKey.DIGIT_5
    KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> PlayerKey.DIGIT_6
    KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> PlayerKey.DIGIT_7
    KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> PlayerKey.DIGIT_8
    KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> PlayerKey.DIGIT_9
    else -> null
}

/** 三态：按下 / 按住 / 松手。 */
internal fun pressKindOf(event: KeyEvent): PressKind = when {
    event.action == KeyEvent.ACTION_UP -> PressKind.RELEASE
    event.repeatCount > 0 -> PressKind.REPEAT
    else -> PressKind.TAP
}

/** 连按节流用的单调时钟。抽成一个函数是为了让"时间从哪来"只有一处。 */
internal fun nowMillis(): Long = SystemClock.elapsedRealtime()
