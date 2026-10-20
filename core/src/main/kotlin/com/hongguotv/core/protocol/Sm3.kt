// SPDX-License-Identifier: GPL-3.0-only
// SM3（GB/T 32907-2016）纯 Kotlin 实现，替代 BouncyCastle 以缩减 APK 体积。
// 正确性由 Sm3Test 的标准向量与 SignerTest 的固定签名对照共同守护。
package com.hongguotv.core.protocol

internal fun sm3(value: ByteArray): ByteArray {
    // 消息填充：追加 0x80、零、64 位大端长度
    val padded = value.size + 1 + 8
    val blocks = (padded + 63) / 64
    val data = ByteArray(blocks * 64)
    value.copyInto(data)
    data[value.size] = 0x80.toByte()
    val bitLength = value.size.toLong() * 8
    for (i in 0 until 8) data[blocks * 64 - 1 - i] = (bitLength ushr (i * 8)).toByte()

    var v = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt()
    )
    val w = IntArray(68); val wPrime = IntArray(64)
    for (block in 0 until blocks) {
        val at = block * 64
        for (j in 0 until 16) w[j] = data.i32(at + j * 4)
        for (j in 16 until 68) {
            val x = w[j - 16] xor w[j - 9] xor rol(w[j - 3], 15)
            w[j] = p1(x) xor rol(w[j - 13], 7) xor w[j - 6]
        }
        for (j in 0 until 64) wPrime[j] = w[j] xor w[j + 4]
        var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
        var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]
        for (j in 0 until 64) {
            val t = if (j < 16) 0x79cc4519 else 0x7a879d8a
            val ss1 = rol(rol(a, 12) + e + rol(t, j and 31), 7)
            val ss2 = ss1 xor rol(a, 12)
            val tt1 = (if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)) + d + ss2 + wPrime[j]
            val tt2 = (if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)) + h + ss1 + w[j]
            d = c; c = rol(b, 9); b = a; a = tt1
            h = g; g = rol(f, 19); f = e; e = p0(tt2)
        }
        v[0] = a xor v[0]; v[1] = b xor v[1]; v[2] = c xor v[2]; v[3] = d xor v[3]
        v[4] = e xor v[4]; v[5] = f xor v[5]; v[6] = g xor v[6]; v[7] = h xor v[7]
    }
    val out = ByteArray(32)
    for (i in 0 until 8) be32(v[i]).copyInto(out, i * 4)
    return out
}
private fun p0(x: Int) = x xor rol(x, 9) xor rol(x, 17)
private fun p1(x: Int) = x xor rol(x, 15) xor rol(x, 23)
