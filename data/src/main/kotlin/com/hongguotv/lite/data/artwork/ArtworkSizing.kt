package com.hongguotv.lite.data.artwork

/**
 * 封面的尺寸算术。**与 Android API 无关**，所以在 JVM 单测里直接可测。
 *
 * 三条口径来自旧实现（`ArtworkLoader`），这里只是把它们从 337 行的加载器里拆出来：
 * 1. **不放大**：源图比目标框小就原样用——电视上把 120px 的图拉到 300px 只会更糊；
 * 2. **等比缩进目标框**，不裁切：裁切信息在解码时就丢了，`FIT_CENTER` 补不回来（旧实现踩过，
 *    竖版海报被切掉大半）；
 * 3. **2 的幂降采样**：`BitmapFactory` 只认 2 的幂，降采样后再做一次精确缩放。
 */
object ArtworkSizing {

    data class Size(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height.toLong()
    }

    /**
     * 目标尺寸：等比缩到不超过 [maxWidth] × [maxHeight]，且不超过源尺寸。
     * 任何一边非正（调用方没量出尺寸、图坏了）时退回 1×1，绝不返回 0 去喂 Bitmap。
     */
    fun targetSize(sourceWidth: Int, sourceHeight: Int, maxWidth: Int, maxHeight: Int): Size {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) return Size(1, 1)
        val fit = minOf(
            1.0,
            maxWidth.toDouble() / sourceWidth,
            maxHeight.toDouble() / sourceHeight,
        )
        return Size(
            (sourceWidth * fit).toInt().coerceAtLeast(1),
            (sourceHeight * fit).toInt().coerceAtLeast(1),
        )
    }

    /**
     * `BitmapFactory.Options.inSampleSize`：2 的幂，取"解码后仍不小于目标"的最大值。
     * 结果至少是 1（1 = 不降采样）。
     */
    fun sampleSize(sourceWidth: Int, sourceHeight: Int, target: Size): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= target.width &&
            sourceHeight / (sample * 2) >= target.height
        ) {
            sample *= 2
        }
        return sample
    }

    /** 降采样后仍然过大的图直接放弃：老电视上一次分配几十 MB 就是一次 OOM。 */
    fun exceedsDecodeBudget(size: Size, limitPixels: Long): Boolean = size.pixels > limitPixels

    /**
     * 缓存键 / 解码目标用的尺寸：向上取整到 [SIZE_BUCKET] 的整桶。
     *
     * 为什么要归桶：缓存键含宽高，而宽高是布局量出来的——hero 封面跟着右侧信息列的
     * 文字高度走，规格表从空值变成真值就差几十像素。不归桶的话，一次重排就是一次新下载，
     * 同一张封面在内存里还留两份；归桶之后"差几像素"落在同一张图上。
     *
     * 向上取整（而不是四舍五入）：取整后的图只会比需要的略大，`CENTER_CROP` 不会缺角。
     */
    fun cacheSize(width: Int, height: Int): Size = Size(bucket(width), bucket(height))

    /** 64px 一档：1080p 卡宽 298 → 320。 */
    const val SIZE_BUCKET: Int = 64

    private fun bucket(value: Int): Int {
        if (value <= 0) return SIZE_BUCKET
        return ((value + SIZE_BUCKET - 1) / SIZE_BUCKET) * SIZE_BUCKET
    }
}
