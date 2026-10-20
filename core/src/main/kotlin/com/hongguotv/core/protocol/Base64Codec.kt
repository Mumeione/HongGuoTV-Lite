// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core.protocol

/**
 * 自带 Base64 编解码。
 *
 * **为什么不用 `java.util.Base64`**：那个类从 Android API 26 才有。本项目最低支持 Android 6.0（API 23），
 * 直接用会在老设备上 `NoClassDefFoundError`——而编译期完全看不出来，因为我们自己的设备是 API 26+。
 *
 * **为什么不用脱糖兜底**：`core` 是纯 JVM 模块，不经过 Android 的脱糖流程；而且脱糖对具体类的
 * 覆盖范围需要逐版本确认。为两个工具函数赌一个构建特性，不划算。
 *
 * 行为与平台实现对齐：
 * - 解码接受标准字母表、URL-safe 变体（`-` / `_`）、有无 padding；
 * - 编码输出标准字母表 + `=` 补齐，不加换行；
 * - 非法字符直接抛错，不静默跳过；padding 只能出现在末尾并补齐 4 字符组，
 *   中段 padding、缺位 padding（`Zg=`、`Z===`）一律抛错——与 JDK 严格解码一致。
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** ASCII 反查表；-1 表示非法字符。 */
    private val REVERSE = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, ch -> this[ch.code] = index }
    }

    fun encode(value: ByteArray): String {
        val out = StringBuilder((value.size + 2) / 3 * 4)
        var i = 0
        while (i < value.size) {
            val b0 = value[i].u()
            val has1 = i + 1 < value.size
            val has2 = i + 2 < value.size
            val b1 = if (has1) value[i + 1].u() else 0
            val b2 = if (has2) value[i + 2].u() else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[(n ushr 18) and 63])
            out.append(ALPHABET[(n ushr 12) and 63])
            out.append(if (has1) ALPHABET[(n ushr 6) and 63] else '=')
            out.append(if (has2) ALPHABET[n and 63] else '=')
            i += 3
        }
        return out.toString()
    }

    fun decode(value: String): ByteArray {
        // 输入按 4 字符 → 3 字节估算；余数部分最多多出 2 字节，留足空间
        val out = ByteArray(value.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        var size = 0
        for ((index, ch) in value.withIndex()) {
            if (ch == '=') {
                // padding 只能出现在末尾：其后不允许再有数据，且必须补齐当前 4 字符组。
                // 被替换的 JDK 严格解码器对 "Zg==Zm9v"、"Zg="、"Z===" 都会抛错，这里对齐，
                // 避免畸形输入被静默截断成另一段字节。
                for (j in index until value.length) {
                    require(value[j] == '=') { "Base64 padding 之后仍有数据（位置 $j）" }
                }
                require(index % 4 >= 2 && value.length - index == 4 - index % 4) {
                    "Base64 padding 位置非法：'$ch'（位置 $index，总长 ${value.length}）"
                }
                break
            }
            val digit = when (ch) {
                '-' -> 62
                '_' -> 63
                else -> if (ch.code < 128) REVERSE[ch.code] else -1
            }
            require(digit >= 0) { "Base64 出现非法字符：'$ch'" }
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[size++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        // 只剩 6 位说明多了一个字符（长度 % 4 == 1），不是合法的 Base64
        require(bits < 6) { "Base64 长度非法：${value.length}" }
        return out.copyOf(size)
    }
}
