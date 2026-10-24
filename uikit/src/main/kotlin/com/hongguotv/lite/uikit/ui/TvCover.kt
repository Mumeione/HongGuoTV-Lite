package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.hongguotv.core.port.ArtworkSpec
import com.hongguotv.lite.uikit.internal.shape

/**
 * 单张封面（详情页 / 系列页的 hero 那一块）。
 *
 * 与 [PosterCard] 的区别只在"没有标题与角标"：封面尺寸由调用方给定，
 * 取图仍然走 [com.hongguotv.core.port.ArtworkPort]——**页面不直接碰 Bitmap**，
 * 缓存与优先级策略留在端口的实现里。
 *
 * 画法对齐 mockup 的 `.b-hero2 .cv`：圆角 10dp + `background-size:cover`。
 * 高度由 [HeroBlock] 跟随信息列设置（封面跨两行、与简介底部平齐），
 * 所以比例不是固定的——按 `CENTER_CROP` 裁，而不是 `FIT_CENTER` 留黑边。
 */
@SuppressLint("ViewConstructor")
class TvCover internal constructor(
    private val ui: UiKit,
    private val widthPx: Int,
    heightPx: Int,
) : ImageView(ui.context) {

    private var heightPx: Int = heightPx
    private var url: String = ""

    init {
        scaleType = ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // 圆角靠 outline 裁切：背景只提供轮廓，取不到图时露出的也是同一块 surface 色
        background = shape(ui.tokens.colors.surface, ui.tokens.shapes.row, null)
        clipToOutline = true
    }

    /** 详情页封面不落盘：看得快、扔得也快，不值得占电视存储。 */
    fun show(coverUrl: String) {
        if (coverUrl == url) return
        url = coverUrl
        if (coverUrl.isEmpty()) {
            setImageDrawable(null)
            return
        }
        load(coverUrl)
    }

    /**
     * 改封面高度（像素）。
     *
     * hero 的封面高度跟着右侧信息列走：简介底边与封面底边要在一条线上（2026-10-24 验收）。
     * 高度变了要按新尺寸重新取图，否则端口给的位图还是旧高，CENTER_CROP 会裁错。
     */
    fun heightPx(px: Int) {
        if (px == heightPx) return
        heightPx = px
        (layoutParams as? ViewGroup.LayoutParams)?.height = px
        requestLayout()
        if (url.isNotEmpty()) load(url)
    }

    private fun load(coverUrl: String) {
        ui.artwork?.load(host = this, url = coverUrl, spec = ArtworkSpec(widthPx = widthPx, heightPx = heightPx))
    }
}
