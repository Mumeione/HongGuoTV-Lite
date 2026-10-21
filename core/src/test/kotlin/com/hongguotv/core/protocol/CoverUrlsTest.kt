package com.hongguotv.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面地址归一化。真实现场（2026-10-21）：收藏/历史里有一部分封面是 HEIC 变体，
 * API 23 的 BitmapFactory 解不了 → 卡片一块空框。
 */
class CoverUrlsTest {

    private val heic =
        "https://p3-reading-sign.fqnovelpic.com/novel-pic/28611ae705e0ef671034e5844c63863a" +
            "~tplv-81nmtwyey9-superreso-aifit:400:0.heic?lk3s=253f70db&x-expires=1797734946&x-signature=abc"

    @Test
    fun `heic variant is rewritten to the decodable one`() {
        assertEquals(
            "https://p6-novel.byteimg.com/novel-pic/28611ae705e0ef671034e5844c63863a~tplv-shrink:640:0.image",
            CoverUrls.normalize(heic),
        )
    }

    @Test
    fun `query is dropped because the signature only covers the original variant`() {
        val rewritten = CoverUrls.normalize(heic)
        assertEquals(false, rewritten.contains("?"))
        assertEquals(false, rewritten.contains("x-signature"))
    }

    @Test
    fun `jpeg cover is returned untouched`() {
        val jpeg = "https://p6-novel.byteimg.com/novel-pic/abc~tplv-shrink:640:0.image"
        assertEquals(jpeg, CoverUrls.normalize(jpeg))
    }

    @Test
    fun `signed jpeg cover keeps its query`() {
        val signed = "https://p3-reading-sign.fqnovelpic.com/novel-pic/abc~tplv-x.image?x-expires=1&x-signature=2"
        assertEquals(signed, CoverUrls.normalize(signed))
    }

    @Test
    fun `heic without a tplv suffix is not guessed`() {
        val plain = "https://example.com/cover.heic"
        assertEquals(plain, CoverUrls.normalize(plain))
    }

    @Test
    fun `unknown shapes are returned as-is`() {
        assertEquals("", CoverUrls.normalize(""))
        assertEquals("not a url", CoverUrls.normalize("not a url"))
        assertEquals("https://example.com/heic", CoverUrls.normalize("https://example.com/heic"))
    }

    @Test
    fun `whitespace from the payload is trimmed`() {
        assertEquals(
            "https://p6-novel.byteimg.com/novel-pic/abc~tplv-shrink:640:0.image",
            CoverUrls.normalize("  https://x.fqnovelpic.com/novel-pic/abc~tplv-1-superreso-aifit:400:0.HEIC  "),
        )
    }
}
