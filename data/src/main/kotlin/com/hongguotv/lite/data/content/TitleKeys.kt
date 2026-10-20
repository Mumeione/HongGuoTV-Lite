package com.hongguotv.lite.data.content

/**
 * 标题里的季号与系列键。规则逐条沿用旧仓库 ContentRepository 的 companion（行为基准），
 * 只在收藏 base 键与锚点定序时使用——季线排序本身由服务端下发，本地不重排。
 */
internal object TitleKeys {

    /** 合法剧集 id：纯数字。上游偶尔混入脏行，解析时按此过滤。 */
    val ID = Regex("[0-9]{1,30}")

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
}
