package com.hongguotv.lite.feature.player

import android.view.View
import com.hongguotv.lite.uikit.focus.FocusKey
import com.hongguotv.lite.uikit.focus.FocusPlan

/**
 * 一块界面（侧栏 / 提示卡）算出来的焦点结构，交给页面提交。
 *
 * 焦点会话属于**页面**（一个页面只有一张焦点图），侧栏与提示卡只回答"我这里有哪几项、怎么排"。
 * 让它们各自持有 `FocusSession` 的后果是：两块界面同时可见时，两套记忆互相覆盖。
 */
internal data class FocusBinding(
    val plan: FocusPlan,
    val views: Map<FocusKey, View>,
    val preferred: FocusKey?,
)
