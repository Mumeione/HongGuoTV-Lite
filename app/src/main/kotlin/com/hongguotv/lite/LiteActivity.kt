package com.hongguotv.lite

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * S0 空壳：只证明"7 个模块能装成一个 APK，且依赖规则成立"。
 * S7 收口时这里替换为导航宿主 + 依赖组装，目标 ≤250 行。
 */
class LiteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "HongGuoTV-Lite · S0 骨架\n\n业务代码尚未搬迁。"
            textSize = 22f
            setTextColor(0xFFF3F4F6.toInt())
            setBackgroundColor(0xFF0B0F15.toInt())
            gravity = android.view.Gravity.CENTER
        })
    }
}
