package com.hongguotv.core.model

/**
 * 季号与系列键（季键）。规则逐条沿用旧仓库 `ContentRepository` 的 companion（行为基准），
 * 只在收藏 base 键、锚点定序与"是否已收藏"判断时使用——季线排序本身由服务端下发，本地不重排。
 *
 * 放在 core 而不是 data：季键是领域概念，
 * 收藏页与搜索页都要用它判断"这是不是同一个系列"。留在实现层会让 feature 复制一份规则。
 */
object SeasonKeys {

    // 季号两种形态：①「第N季/部」（阿拉伯或中文数字）②阿拉伯数字直连季字（"之无灵证道40季"，实测存在）。
    // 中文数字必须保留「第」前缀，否则"斩天十三剑"这类标题会被误伤。
    private val seasonPattern = Regex("(?:第\\s*([0-9０-９一二两三四五六七八九十]{1,3})|([0-9０-９]{1,3}))\\s*[季部]")
    private val cnDigits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val baseNoise = Regex("[\\s，,、。．.！!？?：:；;·\\-—_～~（）()\\[\\]【】\"'“”‘’]")

    /** 归组用的标题归一化：去掉空白与常见中英标点。 */
    fun normalizeTitle(value: String): String = value.replace(baseNoise, "")

    /** 标题中的季号（第N季/部、"40季"，阿拉伯或中文数字）；无季号返回 0。 */
    fun seasonNumber(title: String): Int {
        val match = seasonPattern.find(title) ?: return 0
        val text = match.groupValues[1].ifEmpty { match.groupValues[2] }.replace('０', '0').replace('９', '9')
        text.toIntOrNull()?.let { return it }
        var total = 0
        var current = 0
        for (ch in text) when {
            ch == '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
            else -> { val d = cnDigits[ch] ?: return 0; current = current * 10 + d }
        }
        return total + current
    }

    /** 去掉季号后缀的系列基名，取「第N季」之前的前缀；无季号返回原标题。 */
    fun seasonBase(title: String): String {
        val match = seasonPattern.find(title) ?: return title.trim()
        return title.substring(0, match.range.first).trimEnd(' ', '·', '-', '—', '：', ':', ',')
    }

    /** 收藏/连播共用的系列键：去季号再归一化标点。 */
    fun seasonKey(title: String): String = normalizeTitle(seasonBase(title))

    /**
     * 一条季线能反推出的系列基名键（canonical key）：取第一条**标题自带季号**的季
     * （「第N季」/「40季」），去掉季后缀再归一化。
     *
     * 篇名季（分宗篇、折桂篇）的标题不带季号，拿它反推只会得到篇名本身；
     * 整条线都没有带季号的标题时返回 null——没有证据就不猜，避免把同 IP 误并组。
     */
    fun canonicalKey(seasons: List<Series>): String? =
        seasons.firstOrNull { seasonNumber(it.title) > 0 }
            ?.let { seasonKey(it.title) }
            ?.takeIf { it.isNotBlank() }
}

/** 合法剧集 id：纯数字。上游偶尔混入脏行，解析时按此过滤。 */
object SeriesIds {
    val ID = Regex("[0-9]{1,30}")
}
