package com.hongguotv.core.usecase

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.Progress
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series

/**
 * 一个系列最近的一条观看记录。
 *
 * 收藏的是**系列**，进度是按**季**存的：要回答"继续播放该落在哪"，必须把季线里每一季都问一遍。
 * 收藏页的预览、系列页的主按钮、详情页的状态行读的都是这一条。
 *
 * 唯一实现：S4 曾把它写在 `feature-catalog` 内部，S5 的系列页是第二个消费者，
 * 于是上提到 core——各留一份必然出现"收藏页说第 6 集、系列页说第 7 集"。
 */
object SeasonProgress {

    /** 这个系列要被问一遍的季 id：收藏的那一季 + 季线里的每一季。 */
    fun seriesIds(entry: FavoriteEntry?, line: SeasonLine?): List<String> {
        val ids = LinkedHashSet<String>()
        entry?.let { ids += it.series.id }
        line?.seasons?.forEach { ids += it.id }
        return ids.toList()
    }

    fun latest(ids: Collection<String>, progressOf: (String) -> Progress?): Progress? =
        ids.mapNotNull(progressOf).maxByOrNull { it.updatedAt }

    fun latest(entry: FavoriteEntry, line: SeasonLine?, progressOf: (String) -> Progress?): Progress? =
        latest(seriesIds(entry, line), progressOf)

    /** 这条记录属于哪一季（用于"第 N 季"文案）；拿不到季线时用 [fallback]。 */
    fun seasonOf(progress: Progress, line: SeasonLine?, fallback: Int): Int =
        line?.seasons?.firstOrNull { it.id == progress.seriesId }?.season ?: fallback

    /** 记录对应的那一季本身（系列页据此定位落点）；不在季线里时返回 null。 */
    fun seriesOf(progress: Progress, line: SeasonLine?): Series? =
        line?.seasons?.firstOrNull { it.id == progress.seriesId }
}
