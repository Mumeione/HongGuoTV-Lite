package com.hongguotv.lite.uikit.token

import android.app.ActivityManager
import android.content.Context

/**
 * 读设备能力，决定用哪一档令牌。
 *
 * 判据是系统的低内存标记，**不是屏幕分辨率**：老盒子常见 1GB 内存配 1080p 屏，
 * 按分辨率猜会猜错档。这也是 uikit 里唯一读系统服务的地方——令牌本身保持纯数据。
 */
object DeviceProfile {
    fun tierOf(context: Context): UiTier {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return UiTier.of(manager?.isLowRamDevice == true)
    }
}
