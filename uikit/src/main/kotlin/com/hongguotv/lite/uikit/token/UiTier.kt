package com.hongguotv.lite.uikit.token

/**
 * 设备档位。判据是系统的低内存标记，不是分辨率——老盒子常见 1GB 内存配 1080p 屏。
 *
 * 两档之间**只允许**在动效与单页卡片上限上不同；颜色与字号必须一致，
 * 否则同一张截图在两台设备上对不上，"这是不是 bug"就说不清了。
 */
enum class UiTier {
    NORMAL,
    LOW_RAM;

    companion object {
        fun of(isLowRam: Boolean): UiTier = if (isLowRam) LOW_RAM else NORMAL
    }
}
