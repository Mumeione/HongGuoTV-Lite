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
