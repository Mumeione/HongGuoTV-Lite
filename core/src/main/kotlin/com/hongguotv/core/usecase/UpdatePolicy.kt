package com.hongguotv.core.usecase

import com.hongguotv.core.model.UpdateManifest
import com.hongguotv.core.protocol.urlEncode
import org.json.JSONObject

/**
 * 更新决策与双源清单解析（纯函数，可脱机单测）。
 *
 * 两个源的结构不同，输出统一为 [UpdateManifest]：
 * - CNB：仓库 main 分支根目录的 `update.json`（字段自控，`versionCode` 必填），
 *   apk 下载地址 = `<release 附件直链 base>` + URL 编码后的文件名；
 * - GitHub：`releases/latest`，无数字版本号 → `versionCode = 0`，比较退到版本名。
 */
object UpdatePolicy {

    /**
     * 本地 vs 远端：远端 `versionCode > 0` 时按 code 比较（CNB 源的权威口径）；
     * `= 0`（GitHub 源）退到版本名比较。相等或更旧 = [UpToDate]。
     */
    fun decide(localCode: Int, localName: String, remote: UpdateManifest): Decision = when {
        remote.versionCode > 0 -> if (remote.versionCode > localCode) Decision.Available(remote) else Decision.UpToDate
        isNewerVersion(remote.versionName, localName) -> Decision.Available(remote)
        else -> Decision.UpToDate
    }

    sealed class Decision {
        data class Available(val manifest: UpdateManifest) : Decision()
        object UpToDate : Decision()
    }

    /**
     * 点分段版本名比较（`1.10.0` > `1.9.2`；`v1.2` 与 `1.2` 等价；缺段补 0；
     * 非数字段按 0 处理）。任何一段更大即更新；全等或更旧 = false。
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val r = segments(remote)
        val l = segments(local)
        for (index in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(index) { 0 }
            val lv = l.getOrElse(index) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun segments(version: String): List<Int> =
        stripVersionPrefix(version)
            .split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /**
     * 清单里的安装包哈希：64 位十六进制才算数，统一成小写。
     * 字段缺失或格式不对都按"没给"处理（空串）——清单本身由发布渠道控制，
     * 一个非法的哈希值和没有哈希是同一种威胁等级，不该让它拦死正常更新。
     */
    private fun normalizedSha256(raw: String): String =
        raw.trim().takeIf { SHA256.matches(it) }?.lowercase() ?: ""

    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    /** `v1.2` / `V1.2` 与 `1.2` 等价：两个解析入口（分段比较、GitHub tag）共用这一份。 */
    private fun stripVersionPrefix(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")

    /**
     * 解析 CNB 的 `update.json`。结构：`{versionCode, versionName, apk, size?, notes?}`。
     * versionCode 缺失/非正、apk 文件名含路径分隔符（防 URL 路径注入）都视为无效。
     */
    fun parseCnbUpdateJson(json: String, apkUrlBase: String): UpdateManifest? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        // API 型错误体（errcode/errmsg）不是清单，按"没有清单"处理
        if (obj.has("errcode")) return null
        val code = obj.optInt("versionCode", 0)
        val name = obj.optString("versionName").trim()
        val apk = obj.optString("apk").trim()
        if (code <= 0 || name.isEmpty() || apk.isEmpty()) return null
        if (apk.contains('/') || apk.contains('\\') || apk.contains("..")) return null
        return UpdateManifest(
            versionCode = code,
            versionName = name,
            apkUrl = apkUrlBase + urlEncode(apk),
            sizeBytes = obj.optLong("size", 0L).coerceAtLeast(0L),
            notes = obj.optString("notes").trim(),
            sha256 = normalizedSha256(obj.optString("sha256")),
        )
    }

    /**
     * 解析 GitHub `releases/latest`。tag 去 `v` 前缀当版本名（versionCode=0，按名比较）；
     * assets 里第一个 `.apk` 是下载地址；`body` 是更新说明。没有 apk 资产视为无效
     * （这个源的意义就是"能下载"）。404 等错误体由 HTTP 层拦下，不会进这里。
     */
    fun parseGitHubReleaseJson(json: String): UpdateManifest? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (obj.has("message") && !obj.has("tag_name")) return null
        val tag = obj.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val name = stripVersionPrefix(tag).ifEmpty { return null }
        val asset = obj.optJSONArray("assets")
            ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
            ?.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return null
        val url = asset.optString("browser_download_url").trim()
        if (!url.startsWith("https://")) return null
        return UpdateManifest(
            versionCode = 0,
            versionName = name,
            apkUrl = url,
            sizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L),
            notes = obj.optString("body", "").trim(),
        )
    }
}
