package com.hongguotv.core.usecase

import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.model.Series

/**
 * 季线里的"下一个可播的季"。**跨季连播的唯一判据**：详情页的说明与 S6 播放器的片尾跳转都调它，
 * 否则会出现"页面写着会接着播、播完却停下"这种自相矛盾。
 *
 * 三条口径：
 * - 季线是旧 → 新（端口契约），"下一个"是**更新**的方向；
 * - **未上线季不参与连播**，但它仍占季线里的位置——所以不是简单地取 `index + 1`；
 * - 当前季不在季线里时返回 null（分宗篇这类无季号的锚点、或季线缓存里缺了本尊），
 *   此时"下一季"无从谈起，宁可停下也不要猜一个。
 */
object SeasonChain {

    /** 季线里已上线的季，旧 → 新。 */
    fun released(line: SeasonLine): List<Series> = line.seasons.filter { it.released }

    /** 库里最新的一个已上线季；季线为空或全部未上线时返回 null。 */
    fun latest(line: SeasonLine): Series? = line.seasons.lastOrNull { it.released }

    fun next(line: SeasonLine, currentId: String): Series? {
        val seasons = line.seasons
        val index = seasons.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        return seasons.drop(index + 1).firstOrNull { it.released }
    }
}
