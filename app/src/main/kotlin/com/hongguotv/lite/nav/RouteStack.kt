package com.hongguotv.lite.nav

/**
 * 路由：宿主此刻该把哪一页放在屏幕上。
 *
 * **目录页不带 tab**：tab 是目录页自己的可变状态（用户随手按顶栏切换），
 * 路由是宿主的可变状态。两边各存一份，切完 tab 再按返回就会拿到过期值——
 * 这正是 S6 `sidebarMode` 双所有者的同型问题。宿主需要 tab 时读
 * `CatalogFeature.currentTab`，不复制。
 */
sealed interface Route {

    /** 目录四页（搜索 / 收藏 / 历史 / 设置）。具体哪一页归 `feature-catalog`。 */
    data object Catalog : Route

    /**
     * 剧集详情。
     *
     * [autoplay] 为真表示这是一块**起播跳板**：从历史 / 「继续看」进来时，
     * 页面加载完直接调 `toPlayer`，用户从没见过详情页本身——所以它不该留在返回栈里
     * （见 [RouteStack.openPlayer]）。
     */
    data class Detail(val seriesId: String, val autoplay: Boolean = false) : Route

    /** 收藏系列页（季线）。 */
    data class Series(val seasonKey: String) : Route

    /** 播放页。[positionMs] 已由调用方按 `ResumePoint` 归一化。 */
    data class Player(val seriesId: String, val episodeIndex: Int, val positionMs: Long) : Route
}

/** 一次导航动作的结果：宿主按它决定要不要重画。 */
enum class NavResult {
    /** 顶层变了，宿主应重画 `stack.top`。 */
    MOVED,

    /** 什么都没变（重复点击、同一目标连按两下），不要重画——重画会把页面重建一遍。 */
    IGNORED,

    /** 栈底已经无路可退，宿主自行决定（退出应用或先回搜索页）。 */
    EXIT,
}

/** 目录页按返回的两种结局。 */
enum class CatalogBack {
    /** 先回搜索页（旧行为：不在搜索页时按返回切到第一页）。 */
    TO_SEARCH,

    /** 已经在搜索页：这次返回才算退出应用。 */
    EXIT,
}

/**
 * 目录页按返回：不在搜索页就先回搜索页，已经在搜索页才退出。
 *
 * 入参是"当前是否在搜索页"而不是下标：`CatalogTab` 的顺序归 `feature-catalog`，
 * 这里不复制一份"0 就是搜索"的约定。
 */
fun backFromCatalog(onSearchTab: Boolean): CatalogBack = if (onSearchTab) CatalogBack.EXIT else CatalogBack.TO_SEARCH

/**
 * 页面栈。**不碰 `android.*`**：路由是数据，规则就能脱机断言。
 *
 * 只有三条规则，全部集中在 "进播放页" 那一处判定（[openPlayer]）：
 * 1. 用户看得见的页（目录 / 详情 / 系列）→ **压栈**，返回回到它；
 * 2. 用户看不见的页（播放页本身、起播跳板）→ **替换**，返回回到它下面那一层；
 * 3. 重复导航 → **忽略**，防止遥控器连按叠出两层一样的页面。
 *
 * 栈底永远是 [Route.Catalog]（`toCatalog` 重置栈、`back` 只弹不空），
 * 所以"还能不能返回"等于"栈深是否大于 1"。
 */
class RouteStack(initial: Route = Route.Catalog) {

    private val entries = ArrayList<Route>(4).apply { add(initial) }

    val top: Route get() = entries[entries.size - 1]

    /** 栈深。1 = 只剩目录页。单测与诊断用，业务代码不该按它分支。 */
    val depth: Int get() = entries.size

    /** 当前栈的只读快照（从底到顶）。单测断言用。 */
    fun snapshot(): List<Route> = entries.toList()

    /** 进详情。同一目标重复进入被忽略（遥控器连按不该叠两层）。 */
    fun openDetail(seriesId: String, autoplay: Boolean = false): NavResult {
        val next = Route.Detail(seriesId, autoplay)
        if (top == next) return NavResult.IGNORED
        entries += next
        return NavResult.MOVED
    }

    /** 进收藏系列页。 */
    fun openSeries(seasonKey: String): NavResult {
        val next = Route.Series(seasonKey)
        if (top == next) return NavResult.IGNORED
        entries += next
        return NavResult.MOVED
    }

    /**
     * 进播放页。三种来由，三种去向：
     *
     * | 当前顶层 | 行为 | 为什么 |
     * | --- | --- | --- |
     * | 播放页 | 替换 | 跨季连播：换的是 `seriesId`，"返回哪一页"不变 |
     * | 起播跳板（`Detail.autoplay`） | 替换 | 用户从没见过那一页，不能退回它 |
     * | 其它 | 压栈 | 返回要回到用户看得见的详情 / 系列页 |
     */
    fun openPlayer(seriesId: String, episodeIndex: Int, positionMs: Long): NavResult {
        val next = Route.Player(seriesId, episodeIndex, positionMs)
        if (top == next) return NavResult.IGNORED
        return when (val current = top) {
            is Route.Player -> replace(next)
            is Route.Detail -> if (current.autoplay) replace(next) else push(next)
            else -> push(next)
        }
    }

    /** 回目录：**丢掉中间层**。它的语义是"回目录"，不是"再叠一页目录"。 */
    fun resetToCatalog(): NavResult {
        if (entries.size == 1 && top == Route.Catalog) return NavResult.IGNORED
        entries.clear()
        entries += Route.Catalog
        return NavResult.MOVED
    }

    /**
     * 返回一层。返回 [NavResult.EXIT] 表示栈底已无路可退——宿主自己决定退出还是先回搜索页
     * （见 [backFromCatalog]）。
     */
    fun back(): NavResult {
        if (entries.size <= 1) return NavResult.EXIT
        entries.removeAt(entries.size - 1)
        return NavResult.MOVED
    }

    private fun push(route: Route): NavResult {
        entries += route
        return NavResult.MOVED
    }

    private fun replace(route: Route): NavResult {
        entries[entries.size - 1] = route
        return NavResult.MOVED
    }
}
