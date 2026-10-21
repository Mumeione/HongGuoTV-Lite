package com.hongguotv.core.protocol

/**
 * 封面地址归一化。
 *
 * 上游有一半的封面给的是 **HEIC** 变体（`~tplv-…-aifit:400:0.heic`），而 API 23 的
 * `BitmapFactory` 根本解不了 HEIC——收藏页与历史页的卡片于是变成一块空框。
 * 实测（2026-10-21）同一个 hash 在 `*-novel.byteimg.com` 上有一份 JPEG/PNG 变体可取，
 * 所以把 HEIC 地址换成那份：
 *
 *     原：https://p3-reading-sign.fqnovelpic.com/novel-pic/<hash>~tplv-xxxx-superreso-aifit:400:0.heic?<签名>
 *     新：https://p6-novel.byteimg.com/novel-pic/<hash>~tplv-shrink:640:0.image
 *
 * 三条纪律：
 * - **只动 HEIC**：其余地址原样返回（上游的 `?x-expires/x-signature` 对原变体有效，
 *   换变体就得丢签名，别的地方乱丢会把本来能用的图弄坏）；
 * - **纯函数**：这是协议口径，不是页面策略，能脱机单测；
 * - **不认识就原样返回**：认不出的形态不猜，宁可它不显示，也不构造一个必然 403 的地址。
 */
object CoverUrls {

    /** 可解码变体所在的主机（与上游其它封面同一个主机，`*-novel.byteimg.com`）。 */
    private const val IMAGE_HOST = "p6-novel.byteimg.com"

    /** 可解码变体的 tplv 后缀。 */
    private const val IMAGE_TPL = "~tplv-shrink:640:0.image"

    fun normalize(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return url

        val queryStart = url.indexOf('?')
        val base = if (queryStart >= 0) url.substring(0, queryStart) else url
        val slash = base.lastIndexOf('/')
        if (slash < 0) return url

        val fileName = base.substring(slash + 1)
        if (!fileName.endsWith(".heic", ignoreCase = true)) return url

        val tpl = fileName.indexOf("~tplv-", ignoreCase = true)
        if (tpl <= 0) return url
        val hash = fileName.substring(0, tpl)

        // host 之后的第一段目录（实测是 novel-pic）；取不到就说明这不是熟悉的形态
        val dir = base.substring(0, slash)
        val path = dir.substringAfter("://", "").substringAfter('/', "")
        if (path.isEmpty()) return url

        return "https://$IMAGE_HOST/$path/$hash$IMAGE_TPL"
    }
}
