package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.hongguotv.lite.uikit.internal.bindFocus
import com.hongguotv.lite.uikit.internal.dp
import com.hongguotv.lite.uikit.internal.stateShape
import com.hongguotv.lite.uikit.token.Tokens

/**
 * 文本输入框（搜索用）。
 *
 * 与其它控件同一套规则：文字与底色走令牌、焦点态只有一处画法、低内存档不做动效。
 * 单独成类而不是让页面自己 new `EditText`，是为了不让"输入框长什么样"散回每个调用点。
 */
@SuppressLint("ViewConstructor")
class TvInput internal constructor(
    context: Context,
    tokens: Tokens,
    label: String,
    maxLength: Int,
) : EditText(context) {

    init {
        hint = label
        textSize = tokens.type.subtitle
        setTextColor(tokens.colors.text)
        setHintTextColor(tokens.colors.muted)
        includeFontPadding = false
        isSingleLine = true
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
        val horizontal = dp(tokens.metrics.controlPaddingH)
        val vertical = dp(tokens.metrics.controlPaddingV)
        setPadding(horizontal, vertical, horizontal, vertical)

        bindFocus(tokens, paint = { focused ->
            // 焦点 = 白底深字（与按钮同一套语义）：输入框是"光标所在"，整页只有它这一块白
            background = stateShape(
                tokens = tokens,
                fill = if (focused) tokens.colors.focus else tokens.colors.surface,
                radiusDp = tokens.shapes.chip,
                selected = false,
                focused = false,
            )
            setTextColor(if (focused) tokens.colors.onFocus else tokens.colors.text)
            // 白底上的提示文字要用深色，否则（浅灰配白底）读不出来
            setHintTextColor(if (focused) tokens.colors.outline else tokens.colors.muted)
        })

        // 电视上不要"一进页就弹键盘"（清单里是 stateAlwaysHidden）：按一下确认才唤出输入法，
        // 用带键盘的遥控器时也可以直接打字——那时焦点已经在输入框里了。
        setOnClickListener {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
