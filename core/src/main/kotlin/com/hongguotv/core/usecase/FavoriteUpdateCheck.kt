package com.hongguotv.core.usecase

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.FavoriteStatus
import com.hongguotv.core.model.SeasonKeys
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.UpstreamError

/** 一次「检查更新」的结果。页面只按这几个数拼状态行。 */
data class FavoriteCheckReport(
    /** 真正检查成功的部数。 */
    val checked: Int,
    /** 失败的部数（详情拿不到）。 */
    val failed: Int,
    /** 其中"有新一季"的部数。 */
    val newSeasons: Int,
    /**
     * 其中"同一季有新集"的部数。
     *
     * 短剧并非都是"一次放全"（维护者实测「十八岁的二次回响」每日更新），
     * 所以"集数增长"必须和"新季上线"分开计数，状态行不能一律说"已是最新"。
     */
    val newEpisodes: Int,
    /** 是否因为连续失败达上限而提前中止。 */
    val stoppedEarly: Boolean,
)

/**
 * 收藏更新检查：串行逐部问两件事——当前季现在多少集（详情接口）、服务端季线里有没有更新的季。
 *
 * 为什么放在 core 而不是页面里：四条规则（1 小时有效期、连续失败 3 次中止、
 * "新季"看季号不看集数、"新集"看详情集数增长）是领域规则，写进页面就只能靠真机验；
 * 写成用例可以用假端口把顺序与计数钉死。
 *
 * 页面只提供回调：进度行要跟着"第几部、哪部"变，检查完一部要刷新那张卡的角标。
 */
object FavoriteUpdateCheck {

    /** 过期时间：一小时内检查过就跳过（收藏看的是新季，低频检查足够）。 */
    const val FRESH_MS: Long = 3_600_000L

    /** 连续失败上限：断网时别把整个收藏列表空转一遍。 */
    const val MAX_CONSECUTIVE_FAILURES: Int = 3

    /** 一次检查的发现。两个标志互不排斥：上新季的同时也可以给当前季加集。 */
    private data class Finding(val newSeason: Boolean, val newEpisodes: Boolean)

    /**
     * 这一部现在该不该检查。
     *
     * 从未检查（`checkedAt <= 0`）一律算过期——老收藏就是靠这一条补上第一次检查。
     * 时间戳落在未来（设备时钟回拨）也算过期，否则一次坏时钟会把检查冻住。
     */
    fun isDue(entry: FavoriteEntry, now: Long, force: Boolean): Boolean {
        if (force) return true
        if (entry.checkedAt <= 0L) return true
        val age = now - entry.checkedAt
        return age < 0L || age >= FRESH_MS
    }

    /** 筛出本次要检查的部，顺序沿用传入顺序（页面按收藏时间倒序给）。 */
    fun due(entries: List<FavoriteEntry>, now: Long, force: Boolean): List<FavoriteEntry> =
        entries.filter { isDue(it, now, force) }

    /**
     * 逐部检查。
     *
     * - 失败只认**详情**拿不到；季线失败只是"这次不知道有没有新季"，不算整部失败（旧行为）。
     * - `acknowledge` 用详情的集数落库：检查结果与"已是最新"标签读的是同一份数据。
     * - 只捕获 [UpstreamError]：取消（`CancellationException`）必须原样抛出去，否则离开页面时
     *   检查会继续在后台跑。
     *
     * @param clock 取当前时间；测试注入假时钟，生产传 `System::currentTimeMillis`
     * @param onProgress 每部开始前回调（下标从 0 起）
     * @param onChecked 每部检查成功后回调，携带 seriesKey，供页面刷新那张卡
     */
    suspend fun run(
        entries: List<FavoriteEntry>,
        content: ContentPort,
        favorites: FavoritesPort,
        clock: () -> Long,
        force: Boolean,
        onProgress: (index: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onChecked: (seriesKey: String) -> Unit = {},
    ): FavoriteCheckReport {
        val pending = due(entries, clock(), force)
        if (pending.isEmpty()) {
            return FavoriteCheckReport(checked = 0, failed = 0, newSeasons = 0, newEpisodes = 0, stoppedEarly = false)
        }

        var checked = 0
        var failed = 0
        var newSeasons = 0
        var newEpisodes = 0
        var streak = 0
        var stopped = false

        for ((index, entry) in pending.withIndex()) {
            onProgress(index, pending.size, entry.series.title)
            // null = 这一部失败了
            val finding: Finding? = try {
                checkOne(entry, content, favorites, clock())
            } catch (error: UpstreamError) {
                null
            }
            if (finding == null) {
                failed++
                streak++
                if (streak >= MAX_CONSECUTIVE_FAILURES) {
                    stopped = true
                    break
                }
                continue
            }
            checked++
            streak = 0
            // 两个计数独立累计：一部剧可以同时上新季又给当前季加集，两边都要说出来
            if (finding.newSeason) newSeasons++
            if (finding.newEpisodes) newEpisodes++
            onChecked(entry.seriesKey)
        }
        return FavoriteCheckReport(checked, failed, newSeasons, newEpisodes, stopped)
    }

    /** @return 这次检查各发现了什么；两个标志独立，允许同时为真 */
    private suspend fun checkOne(
        entry: FavoriteEntry,
        content: ContentPort,
        favorites: FavoritesPort,
        now: Long,
    ): Finding {
        val detail = content.detail(entry.series.id)

        // 季线拿不到不算整部失败：当前季的集数已经拿到了，"有没有新季"留到下次
        val line = try {
            content.seasons(entry.series.id, detail.series)
        } catch (error: UpstreamError) {
            null
        }
        if (line != null && line.seasons.size >= 2) favorites.rememberSeasonLine(entry.seriesKey, line)
        // 先落库再读状态：acknowledge 会把"集数增长"记成 NEW_SEASON，和新季共用同一行。
        favorites.acknowledge(entry.series.id, detail.episodes.size, now)

        // 未上线季不算"新季"（季线里保留占位，但用户点不进去）
        val latest = line?.seasons?.filter { it.released }?.maxOfOrNull { it.season } ?: 0
        val newSeason = latest > 0 && latest > entry.series.season
        // 篇名季的收藏行可能在 rememberSeasonLine 里换到了系列基名键：
        // 状态要按"现在的键"读，否则第一次检查会把新集误报成没有
        val currentKey = line?.let { SeasonKeys.canonicalKey(it.seasons) }
            ?.takeIf { favorites.isFavorite(it) }
            ?: entry.seriesKey
        val newEpisodes = favorites.entry(currentKey)?.status == FavoriteStatus.NEW_SEASON
        return Finding(newSeason = newSeason, newEpisodes = newEpisodes)
    }
}
