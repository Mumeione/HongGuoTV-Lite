package com.hongguotv.lite.data.update

import com.hongguotv.core.model.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * OkHttp 实现的 apk 下载：流式写 `.part` 临时文件，完成且大小核对后才改名。
 * 硬顶 64MB（当前安装包 ~6.8MB，这个顶是防呆不是防上游）。
 */
class OkHttpApkDownloader(
    private val client: OkHttpClient,
    private val maxBytes: Long = MAX_APK_BYTES,
) : ApkDownloader {

    override suspend fun download(manifest: UpdateManifest, target: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            target.parentFile?.let { dir -> if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs failed") }
            val part = File(target.parentFile, target.name + ".part")
            val request = Request.Builder()
                .url(manifest.apkUrl)
                .header("User-Agent", "HongGuoTV-Lite/${manifest.versionName}")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("http ${response.code}")
                    val body = response.body ?: throw IOException("empty body")
                    val total = body.contentLength()
                    if (total > maxBytes) throw IOException("apk too large: $total")
                    var read = 0L
                    var lastReported = Int.MIN_VALUE
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                yield() // 协作式取消：shutdown/换源时能及时退出
                                if (read > maxBytes) throw IOException("apk too large")
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                read += n
                                val percent = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else -1
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    if (!part.renameTo(target)) {
                        part.delete()
                        throw IOException("rename failed")
                    }
                }
            } catch (error: Throwable) {
                runCatching { part.delete() }
                throw error
            }
        }

    companion object {
        private const val MAX_APK_BYTES = 64L * 1024 * 1024
    }
}
