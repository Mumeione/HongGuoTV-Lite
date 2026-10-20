package com.hongguotv.lite.uikit.ui

import android.annotation.SuppressLint
import android.view.View
import android.widget.ImageView
import com.hongguotv.core.port.ArtworkSpec

/**
 * 单张封面（详情页 / 系列页的 hero 那一块）。
 *
 * 与 [PosterCard] 的区别只在"没有标题与角标"：封面尺寸由调用方给定，
 * 取图仍然走 [com.hongguotv.core.port.ArtworkPort]——**页面不直接碰 Bitmap**，
 * 缓存与优先级策略留在端口的实现里。
 */
@SuppressLint("ViewConstructor")
class TvCover internal constructor(
    private val ui: UiKit,
    private val widthPx: Int,
    private val heightPx: Int,
) : ImageView(ui.context) {

    private var url: String = ""

    init {
        scaleType = ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 详情页封面不落盘：看得快、扔得也快，不值得占电视存储。 */
    fun show(coverUrl: String) {
        if (coverUrl == url) return
        url = coverUrl
        if (coverUrl.isEmpty()) {
            setImageDrawable(null)
            return
        }
        ui.artwork?.load(host = this, url = coverUrl, spec = ArtworkSpec(widthPx = widthPx, heightPx = heightPx))
    }
}
