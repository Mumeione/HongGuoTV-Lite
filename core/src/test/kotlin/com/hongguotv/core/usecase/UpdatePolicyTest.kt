package com.hongguotv.core.usecase

import com.hongguotv.core.model.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新决策与双源清单解析（CNB update.json / GitHub releases/latest）。 */
class UpdatePolicyTest {

    // ---------- decide ----------

    @Test
    fun newerVersionCodeIsAvailable() {
        val remote = UpdateManifest(2, "1.1.0", "https://x/apk")
        assertTrue(UpdatePolicy.decide(1, "1.0.0", remote) is UpdatePolicy.Decision.Available)
        assertTrue(UpdatePolicy.decide(2, "1.1.0", remote) is UpdatePolicy.Decision.UpToDate)
        assertTrue(UpdatePolicy.decide(3, "1.2.0", remote) is UpdatePolicy.Decision.UpToDate)
    }

    @Test
    fun zeroVersionCodeFallsBackToNameComparison() {
        // GitHub 源：versionCode=0，按版本名比
        val remote = UpdateManifest(0, "1.2.0", "https://x/apk")
        assertTrue(UpdatePolicy.decide(1, "1.0.0", remote) is UpdatePolicy.Decision.Available)
        assertTrue(UpdatePolicy.decide(1, "1.2.0", remote) is UpdatePolicy.Decision.UpToDate)
        assertTrue(UpdatePolicy.decide(1, "2.0.0", remote) is UpdatePolicy.Decision.UpToDate)
    }

    // ---------- isNewerVersion ----------

    @Test
    fun versionSegmentsCompareNumerically() {
        assertTrue(UpdatePolicy.isNewerVersion("1.10.0", "1.9.2"))
        assertFalse(UpdatePolicy.isNewerVersion("1.9.2", "1.10.0"))
        assertTrue(UpdatePolicy.isNewerVersion("v1.2", "1.1.9"))
        assertTrue(UpdatePolicy.isNewerVersion("1.2", "1.1"))       // 缺段补 0
        assertFalse(UpdatePolicy.isNewerVersion("1.2", "1.2.0"))    // 全等
        assertFalse(UpdatePolicy.isNewerVersion("1.2", "1.2"))
        assertFalse(UpdatePolicy.isNewerVersion("abc", "1.0.0"))    // 非数字段按 0
    }

    /**
     * 预发布后缀不参与比较：`1.0.0-rc1` 不比 `1.0.0` 新。
     *
     * GitHub 备源的 versionCode=0 会走这一条——不摘后缀的话末段 `0-rc1` 被读成 "01" → 1 > 0，
     * 稳定版用户会被提示"升级"到 rc。
     */
    @Test
    fun prereleaseSuffixIsNotNewerThanTheStableRelease() {
        assertFalse(UpdatePolicy.isNewerVersion("1.0.0-rc1", "1.0.0"))
        assertFalse(UpdatePolicy.isNewerVersion("v1.0.0-rc1", "1.0.0"))
        assertFalse(UpdatePolicy.isNewerVersion("1.0.0+build.7", "1.0.0"))
        // 真升级仍然认得出
        assertTrue(UpdatePolicy.isNewerVersion("1.0.1-rc1", "1.0.0"))
        assertTrue(UpdatePolicy.isNewerVersion("1.1.0-beta", "1.0.9"))
    }

    // ---------- parseCnbUpdateJson ----------

    @Test
    fun cnbManifestParsesAllFieldsAndEncodesApkName() {
        val json = """{"versionCode":2,"versionName":"1.1.0","apk":"hongguotv 1.1.0.apk","size":1524637,"notes":"修复若干"}"""
        val manifest = UpdatePolicy.parseCnbUpdateJson(json, "https://cnb.cool/o/r/-/releases/latest/download/")
        assertEquals(2, manifest!!.versionCode)
        assertEquals("1.1.0", manifest.versionName)
        assertEquals("https://cnb.cool/o/r/-/releases/latest/download/hongguotv%201.1.0.apk", manifest.apkUrl)
        assertEquals(1524637L, manifest.sizeBytes)
        assertEquals("修复若干", manifest.notes)
    }

    @Test
    fun cnbManifestRejectsInvalidShapes() {
        val base = "https://cnb.cool/o/r/-/releases/latest/download/"
        assertNull(UpdatePolicy.parseCnbUpdateJson("not json", base))
        assertNull(UpdatePolicy.parseCnbUpdateJson("""{"errcode":16,"errmsg":"not logged in"}""", base))
        assertNull(UpdatePolicy.parseCnbUpdateJson("""{"versionName":"1.1.0","apk":"a.apk"}""", base))          // 缺 versionCode
        assertNull(UpdatePolicy.parseCnbUpdateJson("""{"versionCode":0,"versionName":"1.1.0","apk":"a.apk"}""", base))
        assertNull(UpdatePolicy.parseCnbUpdateJson("""{"versionCode":2,"versionName":"","apk":"a.apk"}""", base))
        assertNull(UpdatePolicy.parseCnbUpdateJson("""{"versionCode":2,"versionName":"1.1.0","apk":""}""", base))
        // 路径注入：apk 文件名不许带分隔符或 ..
        assertNull(UpdatePolicy.parseCnbUpdateJson("""{"versionCode":2,"versionName":"1.1.0","apk":"../evil.apk"}""", base))
        assertNull(UpdatePolicy.parseCnbUpdateJson("""{"versionCode":2,"versionName":"1.1.0","apk":"a/b.apk"}""", base))
    }

    // ---------- 清单 sha256 ----------

    private val validHash = "5ac0c9ab377f853fe0b1974a2d33e1fef6c0dbef0d1d3334e6cdf17adb91e164"

    @Test
    fun cnbManifestParsesSha256NormalizedToLowercase() {
        val base = "https://cnb.cool/o/r/-/releases/latest/download/"
        val upper = """{"versionCode":2,"versionName":"1.1.0","apk":"a.apk","sha256":"${validHash.uppercase()}"}"""
        assertEquals(validHash, UpdatePolicy.parseCnbUpdateJson(upper, base)!!.sha256)
    }

    @Test
    fun cnbManifestWithoutSha256YieldsEmpty() {
        val base = "https://cnb.cool/o/r/-/releases/latest/download/"
        val manifest = UpdatePolicy.parseCnbUpdateJson("""{"versionCode":2,"versionName":"1.1.0","apk":"a.apk"}""", base)
        assertEquals("", manifest!!.sha256)
    }

    /** 格式不对的哈希与"没给"同级：不能让它拦死正常更新。 */
    @Test
    fun cnbManifestRejectsMalformedSha256AsAbsent() {
        val base = "https://cnb.cool/o/r/-/releases/latest/download/"
        val short = """{"versionCode":2,"versionName":"1.1.0","apk":"a.apk","sha256":"deadbeef"}"""
        val notHex = """{"versionCode":2,"versionName":"1.1.0","apk":"a.apk","sha256":"${
            validHash.dropLast(1).replace('5', 'g')
        }"}"""
        assertEquals("", UpdatePolicy.parseCnbUpdateJson(short, base)!!.sha256)
        assertEquals("", UpdatePolicy.parseCnbUpdateJson(notHex, base)!!.sha256)
    }

    // ---------- parseGitHubReleaseJson ----------

    @Test
    fun githubReleaseParsesTagAssetsAndBody() {
        val json = """
            {"tag_name":"v1.2.0","body":"更新说明",
             "assets":[{"name":"sha256.txt","browser_download_url":"https://g/x.txt","size":10},
                       {"name":"hongguotv-1.2.0.apk","browser_download_url":"https://g/hongguotv-1.2.0.apk","size":6841665}]}
        """.trimIndent()
        val manifest = UpdatePolicy.parseGitHubReleaseJson(json)
        assertEquals(0, manifest!!.versionCode)
        assertEquals("1.2.0", manifest.versionName)
        assertEquals("https://g/hongguotv-1.2.0.apk", manifest.apkUrl)
        assertEquals(6841665L, manifest.sizeBytes)
        assertEquals("更新说明", manifest.notes)
    }

    @Test
    fun githubReleaseRejectsInvalidShapes() {
        assertNull(UpdatePolicy.parseGitHubReleaseJson("""{"message":"Not Found","status":"404"}"""))
        assertNull(UpdatePolicy.parseGitHubReleaseJson("not json"))
        assertNull(UpdatePolicy.parseGitHubReleaseJson("""{"tag_name":"v1.0.0","assets":[]}"""))          // 没有 apk 资产
        assertNull(UpdatePolicy.parseGitHubReleaseJson("""{"tag_name":"","assets":[]}"""))
        assertNull(UpdatePolicy.parseGitHubReleaseJson("""{"tag_name":"v1.0.0","assets":[{"name":"a.apk","browser_download_url":"http://insecure/a.apk"}]}"""))
    }
}
