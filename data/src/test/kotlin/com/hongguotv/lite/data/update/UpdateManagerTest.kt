package com.hongguotv.lite.data.update

import com.hongguotv.core.model.UpdateFailure
import com.hongguotv.core.model.UpdateState
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.lite.data.http.HttpTransport
import com.hongguotv.lite.data.library.KeyValueStore
import com.hongguotv.lite.data.library.ValidatingMemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** FakeDownloader 固定写 10 个 0 字节：清单哈希按这个值给才能过校验。 */
    private fun cnbManifestWith(sha256: String = ""): String =
        """{"versionCode":2,"versionName":"1.1.0","apk":"h.apk","size":10,"sha256":"$sha256"}"""

    private fun manager(
        transport: HttpTransport,
        downloader: ApkDownloader = FakeDownloader(),
        verifier: ApkVerifier = ApkVerifier { _, _ -> },   // 包校验是 Android 侧的事，这里测哈希与状态机
        store: KeyValueStore? = null,
        now: Long = 1_000_000L,
        downloadDir: File = tmp.newFolder(),
    ): UpdateManager = UpdateManager(
        downloadDir = downloadDir,
        http = transport,
        downloader = downloader,
        verifier = verifier,
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

    /**
     * 下载失败后按「重试下载」直接重下：不再走一遍检查。
     *
     * 以前 `download()` 只认 [UpdateState.Available]，失败后要按三次（检查 → 等结果 → 下载）
     * 才能重下一次下载——按钮上写着「检查更新」，说明行却写着"下载失败，请重试"。
     */
    @Test
    fun aDownloadFailureIsRetriedWithoutCheckingAgain() = runBlocking {
        val transport = FakeTransport(respond = { if (isCnb(it.url)) cnbManifest else null })
        val downloader = FakeDownloader().apply { fail = true }
        val updates = manager(transport, downloader)

        updates.check()
        updates.download()
        assertEquals(UpdateState.Failed(UpdateFailure.DOWNLOAD), updates.state())
        val requestsAfterCheck = transport.requests.size

        downloader.fail = false
        updates.download()   // 「重试下载」
        assertTrue("重试下载不该再发检查请求", transport.requests.size == requestsAfterCheck)
        assertTrue("state=${updates.state()}", updates.state() is UpdateState.ReadyToInstall)
        assertEquals(1, downloader.downloads.size)   // 第一次失败没落盘，只有这一次算数
    }

    /** 校验未通过也是"包不对"：清单还在，重下一份是唯一能推进的动作。 */
    @Test
    fun aVerificationFailureIsAlsoRetryable() = runBlocking {
        var reject = true
        val verifier = ApkVerifier { _, _ -> if (reject) throw ApkVerificationException("package mismatch") else Unit }
        val updates = manager(
            FakeTransport(respond = { if (isCnb(it.url)) cnbManifest else null }),
            verifier = verifier,
        )
        updates.check()
        updates.download()
        assertEquals(UpdateState.Failed(UpdateFailure.VERIFICATION), updates.state())

        reject = false
        updates.download()
        assertTrue("state=${updates.state()}", updates.state() is UpdateState.ReadyToInstall)
    }

    /** 检查类失败（断网 / 没发版）手上没有清单：`download()` 必须是空操作，别把状态搅乱。 */
    @Test
    fun checkFailuresAreNotRetryableAsDownload() = runBlocking {
        val downloader = FakeDownloader()
        val updates = manager(FakeTransport(respond = { "!net down" }), downloader)
        updates.check()
        assertEquals(UpdateState.Failed(UpdateFailure.NETWORK), updates.state())

        updates.download()
        assertEquals(UpdateState.Failed(UpdateFailure.NETWORK), updates.state())
        assertTrue(downloader.downloads.isEmpty())
    }

    /**
     * 连按「检查更新」只发一轮请求：第二轮进来时状态已经是 Checking，直接返回。
     *
     * 用一条会卡住的请求把第一轮按在"检查中"，再从另一条协程按第二下——
     * 用户连按时按钮还没刷新，两下之间只隔几十毫秒，就是这个时序。
     */
    @Test
    fun aSecondCheckWhileOneIsRunningSendsNothing() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var first = true
        val requests = mutableListOf<String>()
        val transport = object : HttpTransport {
            override suspend fun text(request: SignedRequest): String {
                requests += request.url
                if (first) {
                    first = false
                    entered.complete(Unit)
                    gate.await()   // 把这一轮按在 Checking
                }
                return cnbManifest
            }
        }
        val updates = manager(transport)
        val running = launch(Dispatchers.Default) { updates.check() }
        entered.await()
        assertEquals(UpdateState.Checking, updates.state())

        // 第二下：状态是 Checking，立即返回。给 1 秒上限——没有重入保护时它会卡在这条
        // 请求上，回归应该表现为"失败"而不是"测试挂死"
        withTimeout(1_000) { updates.check() }
        assertEquals(1, requests.size)

        gate.complete(Unit)
        running.join()
        assertEquals(1, requests.size)
        assertTrue("state=${updates.state()}", updates.state() is UpdateState.Available)
    }

    /** 清单带了 sha256 且对得上：哈希校验通过才进 ReadyToInstall。 */
    @Test
    fun manifestSha256MatchLandsReadyToInstall() = runBlocking {
        val transport = FakeTransport(respond = { if (isCnb(it.url)) cnbManifestWith(sha256Hex(ByteArray(10))) else null })
        val updates = manager(transport)
        updates.check()
        updates.download()
        val state = updates.state()
        assertTrue("state=$state", state is UpdateState.ReadyToInstall)
    }

    /** 等长但内容被换：体积校验拦不住，哈希必须拦下。 */
    @Test
    fun manifestSha256MismatchFailsAsVerification() = runBlocking {
        val dir = tmp.newFolder()
        // 与下载内容（10 个 0 字节）等长的另一份内容的哈希
        val transport = FakeTransport(respond = { if (isCnb(it.url)) cnbManifestWith(sha256Hex(ByteArray(11))) else null })
        val updates = manager(transport, downloadDir = dir)
        updates.check()
        updates.download()
        assertEquals(UpdateState.Failed(UpdateFailure.VERIFICATION), updates.state())
        assertFalse(File(dir, "hongguotv-update.apk").exists())   // 拒下的包不落盘
    }

    /** 包名/签名校验由注入的 verifier 把关：它拒，状态就是 VERIFICATION（不弹安装器）。 */
    @Test
    fun verifierRejectionFailsAsVerification() = runBlocking {
        val transport = FakeTransport(respond = { if (isCnb(it.url)) cnbManifestWith(sha256Hex(ByteArray(10))) else null })
        val updates = manager(transport, verifier = ApkVerifier { _, _ -> throw ApkVerificationException("package mismatch") })
        updates.check()
        updates.download()
        assertEquals(UpdateState.Failed(UpdateFailure.VERIFICATION), updates.state())
    }

    /** GitHub 源没有清单哈希字段：sha256 为空时跳过哈希校验，落到 ReadyToInstall。 */
    @Test
    fun githubSourceWithoutSha256SkipsHashCheck() = runBlocking {
        val transport = FakeTransport(respond = { if (isGithub(it.url)) githubRelease else null })
        val updates = manager(transport)
        updates.check()
        updates.download()
        val state = updates.state()
        assertTrue("state=$state", state is UpdateState.ReadyToInstall)
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
