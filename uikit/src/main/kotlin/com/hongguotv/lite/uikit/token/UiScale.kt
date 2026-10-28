package com.hongguotv.lite.uikit.token

import android.content.Context
import android.content.res.Resources
import kotlin.math.roundToInt

/**
 * 全局界面缩放系数：把"设计基准宽 960dp"映射到当前屏幕的实际宽度。
 *
 * 背景（2026-10-28 维护者 4K 电视实测）：令牌全是绝对 dp/sp，只有海报网格会按可用宽度
 * 重算列数。4K 电视常把 3840×2160 报成 320dpi，于是逻辑宽度变成 1920dp——卡片撞到
 * 6 列上限被撑到 1.9 倍，字号、图标、侧栏、信息带却一动不动，看起来"只有卡片变大了"。
 *
 * 这里给出一把统一的尺子：屏宽 960dp → 1.0（1080p 基线），1920dp → 2.0。
 * 令牌构造时乘这个系数，4K 上就是 1080p 的等比放大版，而不是"卡大、字小"的拼贴。
 *
 * 三条边界：
 * - **下限 1.0**：比基准更窄的屏（720p 盒子报 960dp）不做缩小——字已经到可读下限了；
 * - **上限 2.0**：8K 屏（3840dp）不再继续放大，否则一屏只剩两张卡；
 * - **只认宽度**：电视都是 16:9，按宽度归一即等于按对角线归一；高度不参与，
 *   避免某些盒子把状态栏算进高度后算出两套比例。
 */
object UiScale {

    /** 设计基准宽度（dp）：`Metrics` 里"960 − 80 侧栏 − 80 页边距 = 800"就是这条口径。 */
    const val DESIGN_WIDTH_DP: Float = 960f

    const val MIN: Float = 1f
    const val MAX: Float = 2f

    /** 由屏宽（dp）算系数；非法值退回 1.0，绝不返回 0 或 NaN。 */
    fun ofWidthDp(widthDp: Float): Float {
        if (!widthDp.isFinite() || widthDp <= 0f) return MIN
        val raw = widthDp / DESIGN_WIDTH_DP
        // 保留两位：1280dp 这类非整数倍不至于把每个 dp 都算成 198.666…
        val rounded = (raw * 100f).roundToInt() / 100f
        return rounded.coerceIn(MIN, MAX)
    }

    fun of(resources: Resources): Float {
        val metrics = resources.displayMetrics
        val density = metrics.density
        if (density <= 0f) return MIN
        return ofWidthDp(metrics.widthPixels / density)
    }

    fun of(context: Context): Float = of(context.resources)
}
