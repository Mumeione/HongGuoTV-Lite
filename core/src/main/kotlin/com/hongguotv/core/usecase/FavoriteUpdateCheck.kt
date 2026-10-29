package com.hongguotv.core.usecase

import com.hongguotv.core.model.FavoriteEntry
import com.hongguotv.core.model.SeasonLine
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.FavoritesPort
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.port.isRiskControl

/**
 * 检查失败的原因分档——页面据此决定那个按钮该说"重试"还是"重新注册"。
 *
 * 三档按**可采取的动作**排：风控只能换设备，网络只能等，其余是上游自己的问题。
 * 一列里出现多种原因时取 `severity` 最高的那条（用户只要知道最该做的那件事）。
 */
enum class CheckFailure(val severity: Int) {
    /** 被风控（200 空体 / 风控码）：这台设备被拦，**重试没有意义**，只能换一台。 */
    RISK_CONTROL(2),

    /** 网络/连接层失败：等一会儿重试即可。 */
    NETWORK(1),

    /** 其余（HTTP 状态码、结构变化、解密失败、注册被拒）：说不清，给通用文案。 */
    OTHER(0),
}

/** 一次「检查更新」的结果。页面只按这几个数拼状态行。 */
data class FavoriteCheckReport(
    /** 真正检查成功的部数。 */
    val checked: Int,
    /** 失败的部数（这一部唯一那次请求没问成）。 */
    val failed: Int,
    /** 其中"有新一季"的部数。 */
    val newSeasons: Int,
    /** 其中"同一季有新集"的部数（**本次**涨了才算，不读历史未读标记）。 */
    val newEpisodes: Int,
    /** 是否因为连续失败达上限而提前中止。 */
    val stoppedEarly: Boolean,
    /** 失败的**原因**（null = 没失败）。混合失败时取最该处理的那一档。 */
    val failure: CheckFailure? = null,
    /** 本轮没轮到的过期部数（自动检查分批时非 0，页面如实说出来）。 */
    val remaining: Int = 0,
)

/**
 * 本轮要查哪些部。自动检查是"全局门槛 + 最旧优先 + 每轮上限"，手动检查是全量。
 *
 * [blocked] 为真表示全局门槛（[FavoriteUpdateCheck.RUN_GAP_MS]）挡住了这一轮——
 * 页面据此说"刚查过"，而不是"没有要查的"。
 */
data class FavoriteCheckPlan(
    val targets: List<FavoriteEntry>,
    val remaining: Int,
    val blocked: Boolean,
)

/**
 * 收藏更新检查：逐部**只问一次**——多季剧问季线（有没有新季、当前季多少集），
 * 单季剧问详情（集数涨没涨）。
 *
 * 为什么放在 core 而不是页面里：12 小时有效期、全局 6 小时门槛、每轮 40 部、
 * 连续失败 3 次中止、"新季看季号"这几条是领域规则，写进页面就只能靠真机验；
 * 写成用例可以用假端口把顺序、请求数与计数钉死。
 *
 * **每部只问一次**是这一版的核心取舍：以前是 `detail + seasons` 两次，而 `seasons()`
 * 内部在锚点没有专辑证据时还会再补一次详情，100 部收藏的实际请求量是 200~500 次。
 * 现在按"这一部有没有季线"二选一：
 * - 有季线（多季剧）：只问季线。新季看季号，新集看当前季那一格的 `episode_cnt`。
 * - 没有季线（单季剧 / 还没建线）：只问详情，看集数。详情若显示它属于某张专辑，
 *   顺手写回收藏行，下一次就改走季线。
 *
 * 有意的取舍：多季剧的"当前季又加了几集"只在季线里带了那一季时才看得到
 * （plan 的相关作品通常不含锚点本尊，那一格的集数是收藏时的快照）。
 * 多季剧的日常更新不是重点，新季上线才是。
 */
object FavoriteUpdateCheck {

    /** 过期时间：12 小时内检查过就跳过（自动检查另有全局门槛，见 [RUN_GAP_MS]）。 */
    const val FRESH_MS: Long = 12 * 3_600_000L

    /** 自动检查的全局门槛：全部收藏里最近一次检查在 6 小时内，整轮跳过。 */
    const val RUN_GAP_MS: Long = 6 * 3_600_000L

    /** 每轮自动检查最多几部（100 部收藏分 3 轮扫完，不一次打 100 个请求）。 */
    const val AUTO_LIMIT: Int = 40

    /** 连续失败上限：断网时别把整个收藏列表空转一遍。 */
    const val MAX_CONSECUTIVE_FAILURES: Int = 3

    /** 一次检查的发现。两个标志互不排斥：上新季的同时也可以给当前季加集。 */
    private data class Finding(val newSeason: Boolean, val newEpisodes: Boolean)

    /**
     * 这一部现在该不该检查。
     *
     * 从未检查（`checkedAt <= 0`）一律算过期——老收藏就是靠这一条补上第一次检查。
     * 时间戳落在未来（设备时钟回拨）也算过期，否则一次坏时钟会把检查冻住一整天。
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
     * 本轮查哪些。
     *
     * - 手动（[manual] = true）：**全量**，不看有效期与全局门槛——用户按了就要最新结果。
     * - 自动：全局门槛（[RUN_GAP_MS]）→ 最旧优先 → 上限 [AUTO_LIMIT]，
     *   剩下的进 [FavoriteCheckPlan.remaining]。最旧优先保证 100 部能轮转扫完，
     *   不会永远只查同一批。
     */
    fun plan(entries: List<FavoriteEntry>, now: Long, manual: Boolean): FavoriteCheckPlan {
        if (entries.isEmpty()) return FavoriteCheckPlan(emptyList(), 0, blocked = false)
        val pending = due(entries, now, manual)
        if (manual) return FavoriteCheckPlan(pending.sortedBy { it.checkedAt }, 0, blocked = false)
        // 全局门槛用"任一部的最大 checkedAt"判断，不需要另存一个时间戳。
        // 时间戳落在未来（时钟回拨）时 age < 0，不算"刚查过"。
        val last = entries.maxOf { it.checkedAt }
        val age = now - last
        if (last > 0L && age in 0 until RUN_GAP_MS) {
            return FavoriteCheckPlan(emptyList(), pending.size, blocked = true)
        }
        val targets = pending.sortedBy { it.checkedAt }.take(AUTO_LIMIT)
        return FavoriteCheckPlan(targets, pending.size - targets.size, blocked = false)
    }

    /**
     * 逐部检查。
     *
     * - 每部只发一次请求（见对象注释）；这一部唯一那次请求失败 = 这一部失败。
     * - `acknowledge` 用**本次探针**的集数落库：角标与"有新集"读的是同一份数据。
     *   集数未知（季线里没有当前季）时传 0——只刷新检查时间，不拿别的季的数字顶。
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
        val plan = plan(entries, clock(), manual = force)
        if (plan.targets.isEmpty()) {
            return FavoriteCheckReport(
                checked = 0,
                failed = 0,
                newSeasons = 0,
                newEpisodes = 0,
                stoppedEarly = false,
                remaining = plan.remaining,
            )
        }

        var checked = 0
        var failed = 0
        var newSeasons = 0
        var newEpisodes = 0
        var streak = 0
        var stopped = false
        var failure: CheckFailure? = null

        for ((index, entry) in plan.targets.withIndex()) {
            onProgress(index, plan.targets.size, entry.series.title)
            // null = 这一部失败了
            val finding: Finding? = try {
                checkOne(entry, content, favorites, clock())
            } catch (error: UpstreamError) {
                // 失败分档：风控与断网对用户的含义完全不同（一个要换设备，一个等会儿就好）
                val kind = error.asCheckFailure()
                if (kind.severity > (failure?.severity ?: -1)) failure = kind
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
        return FavoriteCheckReport(checked, failed, newSeasons, newEpisodes, stopped, failure, plan.remaining)
    }

    /** 上游失败 → 分档。风控判据与"换设备重试"共用 [isRiskControl] 那一份。 */
    private fun UpstreamError.asCheckFailure(): CheckFailure = when {
        isRiskControl -> CheckFailure.RISK_CONTROL
        this is UpstreamError.Network -> CheckFailure.NETWORK
        else -> CheckFailure.OTHER
    }

    /** 探针：这一部问季线还是问详情。 */
    private enum class Probe { SEASON_LINE, DETAIL }

    /**
     * 判据是**季线里至少两季**，不是"有没有季线"、也不是"带不带专辑号"：
     * - 单季剧（或还没建线）走详情——详情给的是准确集数；季线里那一格可能只是收藏时的快照，
     *   拿它判"涨没涨"会永远看不见增长；
     * - `albumId` 不能当判据：单季剧也可能带专辑号，那样会把它永远锁在季线路径上。
     */
    private fun probe(line: SeasonLine?): Probe =
        if (line != null && line.seasons.size >= 2) Probe.SEASON_LINE else Probe.DETAIL

    private suspend fun checkOne(
        entry: FavoriteEntry,
        content: ContentPort,
        favorites: FavoritesPort,
        now: Long,
    ): Finding {
        val cached = favorites.seasonLine(entry.seriesKey)
        return when (probe(cached)) {
            Probe.SEASON_LINE -> checkSeasonLine(entry, content, favorites, now)
            Probe.DETAIL -> checkDetail(entry, content, favorites, now)
        }
    }

    /**
     * 多季剧：季线一次性回答"有没有新季"与"当前季多少集"。
     *
     * 当前季的集数取线里**同 id** 那一格；那一格是收藏时的快照，可能比服务端旧，
     * 所以只在"比已知更大"时报新集——宁可漏报一次，也不拿别的季的数字冒充。
     */
    private suspend fun checkSeasonLine(
        entry: FavoriteEntry,
        content: ContentPort,
        favorites: FavoritesPort,
        now: Long,
    ): Finding {
        val line = content.seasons(entry.series.id, entry.series)
        if (line.seasons.size >= 2) favorites.rememberSeasonLine(entry.seriesKey, line)

        // 未上线季不算"新季"（季线里保留占位，但用户点不进去）
        val latest = line.seasons.filter { it.released }.maxOfOrNull { it.season } ?: 0
        val newSeason = latest > 0 && latest > entry.series.season

        val currentCount = line.seasons.firstOrNull { it.id == entry.series.id }?.episodeCount ?: 0
        // 集数未知（0）= 只记"查过"，不动 total/seen：否则会把未读标记当成确认清掉
        favorites.acknowledge(entry.series.id, currentCount, now)

        val newEpisodes = entry.currentEpisodes > 0 && currentCount > entry.currentEpisodes
        return Finding(newSeason = newSeason, newEpisodes = newEpisodes)
    }

    /**
     * 单季剧：详情回答"集数涨没涨"。
     *
     * 详情里如果带出了专辑证据（`album_id`），写回收藏行——下一次这一部就改走季线，
     * 免得永远看不见"它其实还有个第二季"。
     */
    private suspend fun checkDetail(
        entry: FavoriteEntry,
        content: ContentPort,
        favorites: FavoritesPort,
        now: Long,
    ): Finding {
        val detail = content.detail(entry.series.id)
        favorites.acknowledge(entry.series.id, detail.episodes.size, now)
        if (entry.series.albumId.isBlank() && detail.series.albumId.isNotBlank()) {
            favorites.setCurrentSeason(entry.seriesKey, detail.series)
        }
        val newEpisodes = entry.currentEpisodes > 0 && detail.episodes.size > entry.currentEpisodes
        return Finding(newSeason = false, newEpisodes = newEpisodes)
    }
}
