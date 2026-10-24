package com.hongguotv.core.usecase

import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.port.ProgressPort

/**
 * 季线元数据回填（2026-10-24 收藏闭环）：季线里的季凡是有观看记录的，
 * 都用季线的完整元数据（标题/封面/角标/季号）重写历史行。
 *
 * 使用场景：跨季连播直接落进播放页的季，最初只有"一条只有 id 的进度记录"，
 * 历史页会是无名黑块；季线到手（进详情页、收藏、播放页预取）时补一次。
 *
 * 不会造出新行：`ProgressPort.rememberSeries` 的契约是"没有这一行的历史就不写"
 * ——浏览/收藏不等于看过。
 */
object SeasonLineBackfill {

    suspend fun run(line: SeasonLine, progress: ProgressPort) {
        line.seasons.forEach { progress.rememberSeries(it) }
    }
}
