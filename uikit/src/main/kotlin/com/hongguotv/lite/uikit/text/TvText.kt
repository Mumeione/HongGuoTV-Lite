package com.hongguotv.lite.uikit.text

import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.model.Progress
import com.hongguotv.core.port.LocalError
import com.hongguotv.core.port.UpstreamError

/**
 * 全应用共用的文本派生：时间、集数、季号、失败文案。
 *
 * 为什么放在 uikit 而不是某个 feature：这些是**用户看到的话**，同一条规则出现在两个页面里就会出现
 * "收藏页说网络断了、详情页说加载失败"这类分裂。uikit 的约定是"控件只收字符串"，而这里是
 * **产出那些字符串的唯一一处**——不 import `android.*`，因此能在 JVM 单测里钉死。
 *
 * 领域词汇（季、季键、季线）仍由 core 的类型表达，这里只负责把它们写成中文。
 */
object TvText {

    private const val CN_DIGITS = "零一二三四五六七八九"

    /** 季号用中文数字，与官方季名（「万妖图录传第五季」）一致；超过 99 季直接用阿拉伯数字。 */
    fun chineseNumber(n: Int): String {
        if (n <= 0 || n >= 100) return n.toString()
        return when {
            n < 10 -> CN_DIGITS[n].toString()
            n == 10 -> "十"
            n < 20 -> "十" + CN_DIGITS[n % 10]
            n % 10 == 0 -> CN_DIGITS[n / 10] + "十"
            else -> CN_DIGITS[n / 10] + "十" + CN_DIGITS[n % 10]
        }
    }

    /** 「第七季」；无季号返回空串（单季剧不写季号）。 */
    fun season(n: Int): String = if (n > 0) "第${chineseNumber(n)}季" else ""

    /**
     * 「更新到第九季第108集」——收藏/追剧追番的进度，一句话回答"现在到哪了"。
     *
     * 季与集写成**一个短语**（不带"· "分隔）：它们回答的是同一个问题，
     * 拆成两段会被读成两件事（上一版就是「更新到第九季 · 108集」，还被维护者抓到
     * 单季写成「更新到 100 集」、多季写成「108集」，两种句式并存）。
     * 两个数都不知道时返回空串，由调用方决定要不要换一句（例如"尚未检查更新"）。
     */
    fun updateProgress(season: Int, episodes: Int): String = when {
        // 中日韩字符可以出现在 Kotlin 标识符里：`$episodes集` 会被读成名为 `episodes集` 的变量
        season > 0 && episodes > 0 -> "更新到${season(season)}第${episodes}集"
        season > 0 -> "更新到${season(season)}"
        episodes > 0 -> "更新到第${episodes}集"
        else -> ""
    }

    /** `mm:ss`。手写补零，不用 `String.format`——它按默认 Locale 出数字，可能变成别的字符。 */
    fun clock(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return "${twoDigits(seconds / 60)}:${twoDigits(seconds % 60)}"
    }

    /** 「第 9 集 · 12:34」。 */
    fun progress(index: Int, positionMs: Long): String = "第 ${index + 1} 集 · ${clock(positionMs)}"

    /**
     * 卡片上的一行观看位置：`第九季 第20集`。
     *
     * B 方案口径：季与集之间**一个空格**（「第九季 第20集」），信息条不再带时间——
     * 观看位置是"我自己在哪"，详情在底部信息栏/详情页说。季号为 0 时只写集。
     */
    fun cardProgress(season: Int, index: Int): String =
        listOf(season(season), "第${index + 1}集").filter { it.isNotBlank() }.joinToString(" ")

    /** `1×` / `1.25×`。`Float.toString()` 的 `1.0` 要去掉尾巴，不能写出"1.0×"。 */
    fun speed(value: Float): String = value.toString().removeSuffix(".0") + "×"

    /** `1080P`。 */
    fun quality(value: Int): String = "${value}P"

    /**
     * 上游分类用 `" · "` 分隔；界面上的分类位统一写成 `" / "`（一行里两种分隔符会读成两组信息）。
     * 详情、收藏、搜索、信息栏都用这一份，别在调用点各写一次 replace。
     */
    fun tags(value: String): String = value.replace(" · ", " / ")

    /** 画面模式三档。旧实现把它写成"完整画面 · 保留比例与全部内容"，那半句解释放在选择项里，不放在状态上。 */
    fun frame(mode: FrameMode): String = when (mode) {
        FrameMode.FIT -> "完整画面"
        FrameMode.ZOOM -> "等比铺满"
        FrameMode.FILL -> "拉伸铺满"
    }

    /**
     * 定时停止的剩余量：`15 分钟后` / `再播完 3 集` / `关闭`。
     *
     * 分钟数**向上取整**：还剩 30 秒时说"1 分钟后"比"0 分钟后"诚实。
     */
    fun sleepTimer(remainingMs: Long?, episodesLeft: Int): String = when {
        remainingMs != null -> "${sleepMinutes(remainingMs)} 分钟后"
        episodesLeft > 0 -> "再播完 $episodesLeft 集"
        else -> "关闭"
    }

    /** 剩余毫秒 → 分钟（向上取整）。定时的文案与档位判定共用它，免得两处各写一遍除法和取整。 */
    fun sleepMinutes(remainingMs: Long): Long = (remainingMs + 59_999) / 60_000

    /**
     * 失败文案按**类型**映射，不匹配中文字符串。
     * 风控空体（[UpstreamError.EmptyBody]）必须单独说"被限制"，不能说成"没有内容"——
     * 后者会让用户去换剧名，而不是等一会儿（`docs/CONTEXT.md`「风控空体」）。
     */
    fun error(cause: Throwable): String = when (cause) {
        is UpstreamError.Network -> "连不上内容网站，请检查网络后重试。"
        is UpstreamError.HttpStatus -> "服务暂时不可用（${cause.code}），请稍后重试。"
        is UpstreamError.EmptyBody -> "请求被临时限制，请稍后重试。"
        is UpstreamError.RiskControl -> "请求被限制（${cause.code}），请稍后再试。"
        is UpstreamError.Shape -> "内容结构发生变化，暂时无法显示。"
        is UpstreamError.Decrypt -> "内容解析失败，请稍后重试。"
        is UpstreamError.Device -> "设备验证没有通过，请稍后重试。"
        is LocalError -> "读取本机数据失败，请重试。"
        else -> "内容加载失败，请稍后重试。"
    }

    private fun twoDigits(value: Long): String = if (value < 10) "0$value" else value.toString()
}
