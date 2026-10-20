package com.hongguotv.core.model

/**
 * 播放相关的档位表：倍速与清晰度。
 *
 * 放在 core 是因为它有**三个消费者**：设置页（改默认值）、本地库（校验盘上的值）、
 * 播放侧栏（临时改当前值）。之前 `data` 与 `feature-catalog` 各存一份，
 * 播放器再存一份就是三份——改档位要改三处，漏一处就出现"设置里有 1.75×、播放器里没有"。
 *
 * 这里只有**档位**，没有文案：`1.25×`、`1080P` 这类展示串在 `uikit` 的 `TvText` 里，
 * 中文字符串不进 core（core 要能脱机单测，也要能换语言）。
 */
object PlaybackOptions {

    val qualities: List<Int> = listOf(480, 720, 1080)

    val speeds: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** 盘上的值被改坏时回落到 1080（旧行为：默认最高）。 */
    fun normalizeQuality(value: Int): Int = value.takeIf { it in qualities } ?: 1080

    fun normalizeSpeed(value: Float): Float = value.takeIf { it in speeds } ?: 1f
}
