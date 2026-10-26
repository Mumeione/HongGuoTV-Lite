package com.hongguotv.lite.data.update

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.model.UpdateManifest
import com.hongguotv.core.model.UpdateState
import com.hongguotv.core.port.DiagnosticsPort
import com.hongguotv.core.port.SilentDiagnostics
import com.hongguotv.core.port.UpdatePort
import com.hongguotv.core.port.UpstreamError
import com.hongguotv.core.protocol.HttpMethod
import com.hongguotv.core.protocol.SignedRequest
import com.hongguotv.core.usecase.UpdatePolicy
import com.hongguotv.core.usecase.loadCatching
import com.hongguotv.lite.data.http.HttpTransport
import com.hongguotv.lite.data.library.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** 把一个版本下载到 [File]（实现负责 `.part` 临时文件与改名）；`onProgress` 收 0-100，未知进度 -1。 */
fun interface ApkDownloader {
    suspend fun download(manifest: UpdateManifest, target: File, onProgress: (Int) -> Unit)
}

/**
 * 更新检查与下载（[UpdatePort] 的 data 实现）。
 *
 * 双源顺序：CNB raw 清单 → GitHub releases/latest。CNB 的 404/无清单**会**切 GitHub
 * （维护者可能先发 GitHub）；CNB 网络失败也切。两源都没货 = Failed("尚无可用版本")。
 *
 * 状态机见 [UpdateState]。下载挂**自带作用域**（活过页面切换）；检查挂调用方作用域
 * （手动检查随设置页离开而取消，无所谓——重进再查一次）。
 */
class UpdateManager(
    /** 下载目标目录（app 传 `cacheDir/update`）；不存在时下载器负责创建。 */
    private val downloadDir: File,
    private val http: HttpTransport,
    private val downloader: ApkDownloader,
    private val diagnostics: DiagnosticsPort = SilentDiagnostics,
    /** 节流时间戳的落盘（独立 prefs 文件）；null = 不节流（单测）。 */
    private val store: KeyValueStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 状态变化如何投递给监听者（生产=主线程 Handler；测试=同步执行）。 */
    private val notify: (block: () -> Unit) -> Unit = { block ->
        Handler(Looper.getMainLooper()).post(block)
    },
    override val versionCode: Int,
    override val versionName: String,
    /** 下载与自动检查的调度器；测试注入 Unconfined 让 launch 同步走完。 */
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UpdatePort {

    private val lock = Any()
    private var current: UpdateState = UpdateState.Idle
    private var listener: (() -> Unit)? = null

    /** 下载与自动检查共用的后台作用域：活过页面，死在 [shutdown]。 */
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var downloadJob: Job? = null

    override fun state(): UpdateState = synchronized(lock) { current }

    override fun onChange(listener: (() -> Unit)?) {
        synchronized(lock) { this.listener = listener }
    }

    override suspend fun check() = checkInternal(silent = false)

    private suspend fun checkInternal(silent: Boolean) {
        // 下载进行中不再检查：查完的 UpToDate/Available 会把"下载中 45%"盖掉，
        // 而下载完成又会强设 ReadyToInstall——两边互相覆盖，UI 语义就乱了
        if (downloadJob?.isActive == true) return
        set(UpdateState.Checking)
        try {
            val state = when (val fetched = fetch()) {
                is Fetch.Found -> when (val decision = UpdatePolicy.decide(versionCode, versionName, fetched.manifest)) {
                    is UpdatePolicy.Decision.Available ->
                        UpdateState.Available(decision.manifest).also {
                            diagnostics.event("update.available", mapOf("version" to it.manifest.versionName))
                        }
                    UpdatePolicy.Decision.UpToDate -> UpdateState.UpToDate
                }
                // 双源都没货：这是"还没发版"，不是故障——文案按状态给，不是错误
                Fetch.None -> if (silent) UpdateState.Idle else UpdateState.Failed(REASON_NONE)
                Fetch.Unavailable -> if (silent) UpdateState.Idle else UpdateState.Failed(REASON_NETWORK)
            }
            set(state)
            store?.commit(mapOf(KEY_LAST_CHECK to clock()))
        } catch (cancelled: CancellationException) {
            // 手动检查挂页面作用域，离开设置页会取消：回落 Idle，别把状态卡死在 Checking
            if (state() is UpdateState.Checking) set(UpdateState.Idle)
            throw cancelled
        }
    }

    /** CNB 主源，缺货/不可用再试 GitHub。 */
    private suspend fun fetch(): Fetch {
        val cnb = fetchFrom(UpdateSources.CNB_MANIFEST_URL) {
            UpdatePolicy.parseCnbUpdateJson(it, UpdateSources.CNB_APK_BASE)
        }
        if (cnb is Fetch.Found) return cnb
        diagnostics.event("update.source", mapOf("cnb" to cnb.javaClass.simpleName))
        return fetchFrom(UpdateSources.GITHUB_LATEST_URL) { UpdatePolicy.parseGitHubReleaseJson(it) }
    }

    /**
     * 单源的异常翻译（两源共用一份，别各写一份）：
     * 404 与 200 空体 = 该源没货（None）；其余失败 = 源不可用（Unavailable，切下一源）。
     */
    private suspend fun fetchFrom(url: String, parse: (String) -> UpdateManifest?): Fetch = try {
        parse(http.text(request(url)))?.let { Fetch.Found(it) } ?: Fetch.None
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: UpstreamError.HttpStatus) {
        if (error.code == 404) Fetch.None else Fetch.Unavailable
    } catch (error: UpstreamError.EmptyBody) {
        Fetch.None
    } catch (error: Exception) {
        Fetch.Unavailable
    }

    private fun request(url: String) = SignedRequest(
        method = HttpMethod.GET,
        url = url,
        headers = mapOf(
            "User-Agent" to "HongGuoTV-Lite/$versionName",
            "Accept" to "application/vnd.cnb.api+json, application/vnd.github+json, application/json",
        ),
        body = null,
    )

    override fun download() {
        val manifest = synchronized(lock) { (current as? UpdateState.Available)?.manifest } ?: return
        if (downloadJob?.isActive == true) return
        val target = apkFile()
        downloadJob = ioScope.launch {
            set(UpdateState.Downloading(if (manifest.sizeBytes > 0) 0 else -1))
            loadCatching({ error ->
                diagnostics.event("update.download", mapOf("ok" to false, "error" to error.message))
                runCatching { target.delete() }
                set(UpdateState.Failed(if (error is IncompleteDownload) REASON_INCOMPLETE else REASON_DOWNLOAD))
            }) {
                downloader.download(manifest, target) { percent -> set(UpdateState.Downloading(percent)) }
                val actual = target.length()
                if (manifest.sizeBytes > 0 && actual != manifest.sizeBytes) {
                    target.delete()
                    // 大小不符 = 下载不完整，不是"下载失败"：文案分开说，别让用户怀疑网络
                    throw IncompleteDownload("size mismatch: $actual != ${manifest.sizeBytes}")
                }
                diagnostics.event("update.downloaded", mapOf("bytes" to actual))
                set(UpdateState.ReadyToInstall(target.absolutePath, manifest.versionName))
                target
            }
        }
    }

    override fun autoCheck() {
        val last = (store?.all()?.get(KEY_LAST_CHECK) as? Long) ?: 0L
        if (clock() - last < THROTTLE_MS) return
        ioScope.launch { checkInternal(silent = true) }
    }

    /** 宿主收摊：停下载与自动检查。幂等。 */
    fun shutdown() {
        ioScope.cancel()
    }

    private fun apkFile(): File = File(downloadDir, APK_NAME)

    private fun set(next: UpdateState) {
        val notifyListener = synchronized(lock) {
            current = next
            listener
        }
        if (notifyListener != null) notify { runCatching { notifyListener() } }
    }

    private sealed interface Fetch {
        data class Found(val manifest: UpdateManifest) : Fetch
        object None : Fetch
        object Unavailable : Fetch
    }

    /** 下载完成但大小与清单不符：区分于网络失败的文案。 */
    private class IncompleteDownload(message: String) : IOException(message)

    companion object {
        private const val APK_NAME = "hongguotv-update.apk"
        private const val KEY_LAST_CHECK = "update.lastCheckAt"

        /** 启动静默检查的节流：一天一次足够（检查本身只是一个几 KB 的 GET）。 */
        const val THROTTLE_MS = 24 * 60 * 60 * 1000L
        const val REASON_NONE = "尚无可用版本"
        const val REASON_NETWORK = "检查失败，请稍后再试"
        const val REASON_DOWNLOAD = "下载失败，请重试"
        const val REASON_INCOMPLETE = "下载不完整，请重试"
    }
}
