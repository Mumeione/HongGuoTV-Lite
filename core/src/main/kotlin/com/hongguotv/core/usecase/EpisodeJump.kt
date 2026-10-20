package com.hongguotv.core.usecase

/**
 * 「跳转集数」的输入解析：用户输的是 **1 起**的集号，页面用的是 **0 起**的下标。
 *
 * 两条口径（行为基准：旧仓库 `EpisodeTarget`）：
 * - 只接受 1—6 位纯数字，其它一律视为无效（`abc`、"1.5"、空串都不猜）；
 * - 越界**不夹住**，直接返回 null。夹住会让用户以为输错了也能跳——
 *   他要的是"跳到第 300 集"，悄悄停在最后一集属于骗人。
 */
object EpisodeJump {

    private val DIGITS = Regex("[0-9]{1,6}")

    fun parse(input: String, total: Int): Int? {
        val clean = input.trim()
        if (!DIGITS.matches(clean)) return null
        return clean.toIntOrNull()?.takeIf { it in 1..total }?.minus(1)
    }
}
