package com.hongguotv.lite.data.update

import com.hongguotv.core.model.UpdateFailure
import com.hongguotv.core.model.UpdateState
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.lite.data.http.HttpTransport
import com.hongguotv.lite.data.library.KeyValueStore
import com.hongguotv.lite.data.library.ValidatingMemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 更新检查：双源回退、状态机、24h 节流（全部走假 HTTP 与假下载器）。 */
class UpdateManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** body=null 表示该 URL 回 404；body 前缀 `!` 表示网络失败；否则返回文本。 */
    private class FakeTransport(
        val respond: (SignedRequest) -> String?,
        val requests: MutableList<String> = mutableListOf(),
    ) : HttpTransport {
        override suspend fun text(request: SignedRequest): String {
            requests += request.url
            val body = respond(request)
            if (body == null) throw UpstreamError.HttpStatus(404)
            if (body.startsWith("!")) throw UpstreamError.Network(java.io.IOException(body.drop(1)))
            return body
        }
    }

    /** 假下载器：写目标文件 + 回报 50%，返回 manifest 记录供断言。 */
    private class FakeDownloader(val downloads: MutableList<com.hongguotv.core.model.UpdateManifest> = mutableListOf()) : ApkDownloader {
        var fail = false
        override suspend fun download(manifest: com.hongguotv.core.model.UpdateManifest, target: File, onProgress: (Int) -> Unit) {
            if (fail) throw java.io.IOException("boom")
            downloads += manifest
            onProgress(50)
            target.writeBytes(ByteArray(10))
            onProgress(100)
        }
    }

    private val cnbManifest = """{"versionCode":2,"versionName":"1.1.0","apk":"hongguotv-1.1.0.apk","size":10,"notes":"n"}"""
    private val githubRelease = """{"tag_name":"v1.3.0","assets":[{"name":"h.apk","browser_download_url":"https://g/h.apk","size":10}],"body":"b"}"""

    private fun manager(
        transport: FakeTransport,
        downloader: ApkDownloader = FakeDownloader(),
        store: KeyValueStore? = null,
        now: Long = 1_000_000L,
        downloadDir: File = tmp.newFolder(),
    ): UpdateManager = UpdateManager(
        downloadDir = downloadDir,
        http = transport,
        downloader = downloader,
        store = store,
        clock = { now },
        notify = { it() },   // 测试里同步执行
        versionCode = 1,
        versionName = "1.0.0",
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,   // launch 同步走完，断言才可靠
    )

    private fun isCnb(url: String) = url.contains("cnb.cool")
    private fun isGithub(url: String) = url.contains("api.github.com")

    @Test
    fun cnbManifestWinsAndBecomesAvailable() = runBlocking {
        val transport = FakeTransport(respond = { if (isCnb(it.url)) cnbManifest else null })
        val updates = manager(transport)
        updates.check()
        val state = updates.state()
        assertTrue(state is UpdateState.Available)
        assertEquals("1.1.0", (state as UpdateState.Available).manifest.versionName)
        assertFalse(transport.requests.any(::isGithub))   // CNB 命中就不碰 GitHub
    }

    @Test
    fun cnbMissFallsBackToGithub() = runBlocking {
        val transport = FakeTransport(respond = { if (isGithub(it.url)) githubRelease else null })
        val updates = manager(transport)
        updates.check()
        val state = updates.state()
        // GitHub 无数字版本号：1.3.0 > 1.0.0（按版本名比较）
        assertTrue(state is UpdateState.Available)
        assertEquals("1.3.0", (state as UpdateState.Available).manifest.versionName)
    }

    @Test
    fun bothSourcesEmptyMeansNoRelease() = runBlocking {
        val updates = manager(FakeTransport(respond = { null }))
        updates.check()
        assertEquals(UpdateState.Failed(UpdateFailure.NONE), updates.state())
    }

    @Test
    fun networkFailureOnBothSourcesMeansCheckFailed() = runBlocking {
        val updates = manager(FakeTransport(respond = { "!net down" }))
        updates.check()
        assertEquals(UpdateState.Failed(UpdateFailure.NETWORK), updates.state())
    }

    @Test
    fun upToDateWhenRemoteIsNotNewer() = runBlocking {
        val old = """{"versionCode":1,"versionName":"1.0.0","apk":"a.apk"}"""
        val updates = manager(FakeTransport(respond = { if (isCnb(it.url)) old else null }))
        updates.check()
        assertEquals(UpdateState.UpToDate, updates.state())
    }

    @Test
    fun downloadOnlyWorksFromAvailableAndLandsReadyToInstall() = runBlocking {
        val transport = FakeTransport(respond = { if (isCnb(it.url)) cnbManifest else null })
        val downloader = FakeDownloader()
        val updates = manager(transport, downloader)
        val seen = mutableListOf<UpdateState>()
        updates.onChange { seen += updates.state() }

        updates.download()   // Idle 态：空操作
        assertEquals(UpdateState.Idle, updates.state())

        updates.check()
        updates.download()
        val state = updates.state()
        assertTrue(state is UpdateState.ReadyToInstall)
        state as UpdateState.ReadyToInstall
        assertTrue(state.apkPath.endsWith("hongguotv-update.apk"))
        assertTrue(File(state.apkPath).isFile)
        assertEquals("1.1.0", state.versionName)
        assertEquals(1, downloader.downloads.size)
        assertEquals(10L, File(state.apkPath).length())   // 与 manifest.size 一致才落地
        assertTrue(UpdateState.Downloading(50) in seen)
    }

    @Test
    fun sizeMismatchDropsTheFileAndFails() = runBlocking {
        val manifest = cnbManifest.replace("\"size\":10", "\"size\":999")
        val transport = FakeTransport(respond = { if (isCnb(it.url)) manifest else null })
        val downloader = FakeDownloader()
        val updates = manager(transport, downloader)
        updates.check()
        updates.download()
        val state = updates.state()
        assertEquals(UpdateState.Failed(UpdateFailure.INCOMPLETE), state)
    }

    @Test
    fun downloadFailureBecomesFailedState() = runBlocking {
        val transport = FakeTransport(respond = { if (isCnb(it.url)) cnbManifest else null })
        val downloader = FakeDownloader().apply { fail = true }
        val updates = manager(transport, downloader)
        updates.check()
        updates.download()
        assertEquals(UpdateState.Failed(UpdateFailure.DOWNLOAD), updates.state())
    }

    @Test
    fun autoCheckIsThrottledAndSilentlyDropsFailures() = runBlocking {
        val store = ValidatingMemoryStore()
        val transport = FakeTransport(respond = { null })   // 两源都空
        // 时钟必须大于 24h 节流窗，否则首查就被节流掉
        val updates = manager(transport, store = store, now = 100_000_000L)

        updates.autoCheck()   // 首次：真的查（CNB 404 → 切 GitHub，共 2 个请求），失败回落 Idle
        assertEquals(UpdateState.Idle, updates.state())
        assertEquals(2, transport.requests.size)
        assertEquals(100_000_000L, store.data["update.lastCheckAt"])

        updates.autoCheck()   // 24h 内：不再查
        assertEquals(2, transport.requests.size)

        val later = manager(transport, store = store, now = 100_000_000L + UpdateManager.THROTTLE_MS + 1)
        later.autoCheck()
        assertEquals(4, transport.requests.size)
    }

    @Test
    fun staleDownloadArtifactsAreCleanedUpOnConstruction() {
        val dir = tmp.newFolder()
        File(dir, "hongguotv-update.apk").writeBytes(ByteArray(10))
        File(dir, "hongguotv-update.apk.part").writeBytes(ByteArray(3))
        File(dir, "unrelated.txt").writeBytes(ByteArray(1))
        // Unconfined 下清理协程同步走完：构造返回时已知文件已删，目录里别的产物不误伤
        manager(FakeTransport(respond = { null }), downloadDir = dir)
        assertEquals(listOf("unrelated.txt"), dir.listFiles()?.map { it.name }?.sorted())
    }

    @Test
    fun throttledTimestampWriteFailureDoesNotBreakCheck() = runBlocking {
        // 磁盘满 = commit 抛异常：节流时间戳丢了只是下次多查一次，检查结果必须照常落地
        val throwing = object : KeyValueStore {
            override fun all(): Map<String, Any?> = emptyMap()
            override fun commit(values: Map<String, Any?>) = throw IllegalStateException("disk full")
        }
        val updates = manager(FakeTransport(respond = { if (isCnb(it.url)) cnbManifest else null }), store = throwing)
        updates.check()
        assertTrue(updates.state() is UpdateState.Available)
    }
}
