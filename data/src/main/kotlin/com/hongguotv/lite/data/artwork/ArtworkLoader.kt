package com.hongguotv.lite.data.artwork

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.hongguotv.core.port.ArtworkPort
import com.hongguotv.core.port.ArtworkSpec
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.PriorityQueue
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * `ArtworkPort` 的实现：**S7 的最小可用版**。
 *
 * 只做三件必须做的事，其余（预取、翻页预热、按远端尺寸二次校准）留到 S8：
 * 1. **内存 LRU**：多页来回切时同一张封面不该重复解码，容量按 `isLowRamDevice` 分档；
 * 2. **收藏 / 历史封面落盘**：这些封面会被反复回看，落盘值得；一次性封面（搜索 / 详情）不占电视存储；
 * 3. **按目标尺寸降采样**：电视上一次解码一张 4K 海报就是一次 OOM。
 *
 * 线程约定：`load` / `cancelPage` 可以从任意线程调，内部会切到主线程；解码与下载在后台线程，
 * 结果回主线程贴图（贴图要求视图还在、且这一代页面还没被换掉）。
 *
 * 落盘只发生在**取图那一趟**：同一张封面若先以"不落盘"取过（搜索页），随后出现在收藏页，
 * 不会再补写盘——不为此重发一次请求。内存缓存只有 8MB，下次进收藏页基本都要重新取，
 * 那时才落盘。
 *
 * 与旧实现（337 行）的差别是删掉了没有消费者的部分：`prefetch`、按布局变化重新请求
 * （规格里尺寸是显式给的）、`refillVisible`、以及"已在内存的图补写盘"。
 * 留下的骨架同型——代际号（`cancelPage`）、同键合并、超限丢弃，都是旧实现在真机上试出来的。
 */
class ArtworkLoader(
    context: Context,
    private val http: OkHttpClient = defaultClient(),
) : ArtworkPort {

    private data class Key(val url: String, val width: Int, val height: Int)

    /** 一个 ImageView 当前的取图意图。视图被回收后弱引用自动失效，不必显式清理。 */
    private class Binding(
        val view: WeakReference<ImageView>,
        val url: String,
        val width: Int,
        val height: Int,
        var priority: Int,
        var persist: Boolean,
        val generation: Int,
    ) {
        var key: Key? = null
        var applied: Key? = null
        var failed: Key? = null
    }

    /**
     * 排队中的一个取图任务。
     *
     * 自带 `compareTo`（而不是给 `PriorityQueue` 传 Comparator）：
     * `PriorityQueue(Comparator)` 是 API 24 才有的构造器，minSdk 23 上会 `NoSuchMethodError`。
     */
    private class Job(
        val key: Key,
        val generation: Int,
        var priority: Int,
        val order: Long,
        @Volatile var persist: Boolean,
    ) : Comparable<Job> {
        val listeners = linkedSetOf<Binding>()
        @Volatile var cancelled = false
        @Volatile var call: Call? = null

        override fun compareTo(other: Job): Int {
            val byPriority = priority.compareTo(other.priority)
            return if (byPriority != 0) byPriority else order.compareTo(other.order)
        }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lowRam = (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    private val cacheBytes = if (lowRam) LOW_RAM_CACHE_BYTES else CACHE_BYTES

    private val cache = object : LruCache<Key, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    private val disk = ArtworkDisk(File(app.cacheDir, DISK_DIR))

    private val signal = Object()
    /** 数值小的先做（聚焦项最先）；同优先级按入队先后。顺序由 [Job.compareTo] 定义。 */
    private val pending = PriorityQueue<Job>()
    private val jobs = HashMap<Key, Job>()
    private val bindings = WeakHashMap<ImageView, Binding>()

    /** 代际号：`cancelPage` 递增。旧页排队中与在途的请求据此作废，不必逐个找回来。 */
    private var generation = 0
    private var order = 0L

    @Volatile
    private var closed = false

    init {
        repeat(if (lowRam) 1 else 2) { index ->
            Thread(::work, "hongguotv-artwork-$index").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
                start()
            }
        }
    }

    // ---------- ArtworkPort ----------

    override fun load(host: Any, url: String, spec: ArtworkSpec) {
        val view = host as? ImageView ?: return
        if (!onMain()) {
            main.post { load(view, url, spec) }
            return
        }
        if (closed || url.isBlank()) return

        val previous = bindings[view]
        if (previous != null && previous.generation == generation && previous.url == url &&
            previous.width == spec.widthPx && previous.height == spec.heightPx
        ) {
            // 同一张图再要一次（聚焦、重渲染）：只提升优先级 / 补落盘，不动图
            previous.priority = spec.priority.coerceIn(ArtworkSpec.PRIORITY_FOCUSED, ArtworkSpec.PRIORITY_VISIBLE)
            previous.persist = previous.persist || spec.persist
            previous.failed = null
            request(previous)
            return
        }
        if (previous != null) detach(previous)
        view.setImageDrawable(null)
        val binding = Binding(
            view = WeakReference(view),
            url = url,
            width = spec.widthPx,
            height = spec.heightPx,
            priority = spec.priority.coerceIn(ArtworkSpec.PRIORITY_FOCUSED, ArtworkSpec.PRIORITY_VISIBLE),
            persist = spec.persist,
            generation = generation,
        )
        bindings[view] = binding
        request(binding)
    }

    override fun cancelPage(clearMemory: Boolean) {
        if (!onMain()) {
            main.post { cancelPage(clearMemory) }
            return
        }
        generation++
        bindings.clear()
        val cancelled = synchronized(signal) {
            jobs.values.toList().also { list ->
                list.forEach { cancelLocked(it) }
                signal.notifyAll()
            }
        }
        cancelled.forEach { it.call?.cancel() }
        if (clearMemory) cache.evictAll()
    }

    @Suppress("DEPRECATION") // 低内存回调的分档常量是 API 23 上唯一可用的信号（新 API 30 才有等价物）
    override fun trim(level: Int) {
        // 按**具体档位**分派，不能用 `>=` 比大小：这几个常量不在一个量纲上
        // （RUNNING_CRITICAL=15 比 UI_HIDDEN=20 小），用大小比较会让"按 HOME 回桌面"
        // 被当成"内存告急"，每退一次后台就把封面扔光。
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cacheBytes / 2)
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> cache.evictAll()
            // 其余（RUNNING_MODERATE / UI_HIDDEN）不是内存压力：缓存留着，回前台封面秒出
            else -> Unit
        }
    }

    /** 进程收摊：停掉工作线程与在途请求。端口接口里没有它，宿主拿着具体类型调。 */
    fun close() {
        if (closed) return
        closed = true
        cancelPage(clearMemory = true)
        synchronized(signal) { signal.notifyAll() }
        // 只取消自己发出的请求，不动客户端的连接池/线程池——那是宿主建的东西，收摊归宿主
        http.dispatcher.cancelAll()
    }

    // ---------- 请求调度 ----------

    private fun request(binding: Binding) {
        val view = binding.view.get() ?: return
        if (closed || binding.generation != generation || bindings[view] !== binding) return
        if (binding.width <= 0 || binding.height <= 0) return

        val key = Key(binding.url, binding.width, binding.height)
        if (binding.key != key) {
            detachJob(binding)
            binding.key = key
            binding.failed = null
        }
        if (binding.applied == key) return
        if (binding.failed == key) return

        cache.get(key)?.let { bitmap ->
            view.setImageBitmap(bitmap)
            binding.applied = key
            return
        }

        synchronized(signal) {
            jobs[key]?.let { job ->
                if (binding.persist) job.persist = true
                job.listeners += binding
                if (binding.priority < job.priority) {
                    val queued = pending.remove(job)
                    job.priority = binding.priority
                    if (queued) pending.add(job)
                }
                return
            }
            if (pending.size >= MAX_PENDING) {
                val worst = pending.maxWithOrNull(compareBy<Job> { it.priority }.thenBy { -it.order })!!
                if (worst.priority < binding.priority) return
                cancelLocked(worst)
            }
            val job = Job(key, binding.generation, binding.priority, ++order, binding.persist)
            job.listeners += binding
            jobs[key] = job
            pending += job
            signal.notifyAll()
        }
    }

    private fun cancelLocked(job: Job) {
        job.cancelled = true
        pending.remove(job)
        if (jobs[job.key] === job) jobs.remove(job.key)
        job.listeners.clear()
    }

    private fun detachJob(binding: Binding) {
        val key = binding.key ?: return
        val cancelled = synchronized(signal) {
            jobs[key]?.takeIf { it.generation == binding.generation }?.let { job ->
                job.listeners.remove(binding)
                if (job.listeners.isEmpty()) {
                    cancelLocked(job)
                    job
                } else {
                    null
                }
            }
        }
        cancelled?.call?.cancel()
    }

    private fun detach(binding: Binding) {
        binding.view.get()?.let { view -> if (bindings[view] === binding) bindings.remove(view) }
        detachJob(binding)
    }

    private fun work() {
        while (!closed) {
            val job = try {
                synchronized(signal) {
                    while (pending.isEmpty() && !closed) signal.wait()
                    if (closed) null else pending.poll()
                }
            } catch (interrupted: InterruptedException) {
                return
            } ?: return

            val bitmap = try {
                loadBitmap(job)
            } catch (oom: OutOfMemoryError) {
                // 内存已经到底了：清一次缓存，这张放弃（下次滚动回来还有机会）
                cache.evictAll()
                null
            } catch (error: Exception) {
                null
            }

            val listeners = synchronized(signal) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                if (!job.cancelled && !closed && bitmap != null) cache.put(job.key, bitmap)
                job.listeners.toList().also { job.listeners.clear() }
            }
            if (job.cancelled || closed) continue

            main.post {
                if (job.generation != generation || closed) {
                    // 页面已经换掉了：这张图没人要了（内存缓存里那份留着无妨）
                    return@post
                }
                listeners.forEach { binding ->
                    val view = binding.view.get() ?: return@forEach
                    if (bindings[view] !== binding || binding.key != job.key) return@forEach
                    if (bitmap != null) {
                        view.setImageBitmap(bitmap)
                        binding.applied = job.key
                    } else {
                        binding.failed = job.key
                    }
                }
            }
        }
    }

    // ---------- 取图与解码 ----------

    private fun loadBitmap(job: Job): Bitmap? {
        if (job.cancelled) return null
        val bytes = (if (job.persist) disk.get(job.key.url) else null) ?: download(job) ?: return null
        if (job.cancelled) return null
        return decode(bytes, job.key)
    }

    private fun download(job: Job): ByteArray? {
        if (job.cancelled) return null
        val call = http.newCall(Request.Builder().url(job.key.url).build())
        job.call = call
        if (job.cancelled) {
            call.cancel()
            return null
        }
        return runCatching {
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BYTES) return null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                body.byteStream().use { input ->
                    while (!job.cancelled) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > MAX_BYTES) return null
                        out.write(buffer, 0, count)
                    }
                }
                if (job.cancelled) return null
                val bytes = out.toByteArray()
                if (job.persist) disk.put(job.key.url, bytes)
                bytes
            }
        }.getOrNull()
    }

    private fun decode(bytes: ByteArray, key: Key): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val target = ArtworkSizing.targetSize(sourceWidth, sourceHeight, key.width, key.height)
        val sample = ArtworkSizing.sampleSize(sourceWidth, sourceHeight, target)
        val decodedWidth = (sourceWidth / sample).coerceAtLeast(1)
        val decodedHeight = (sourceHeight / sample).coerceAtLeast(1)
        if (ArtworkSizing.exceedsDecodeBudget(ArtworkSizing.Size(decodedWidth, decodedHeight), decodePixelBudget)) {
            return null
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (decoded.width == target.width && decoded.height == target.height) return decoded
        val scaled = runCatching { Bitmap.createScaledBitmap(decoded, target.width, target.height, true) }
            .getOrElse { return decoded }
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /**
     * 一次解码允许的**像素数**（不是字节）：低内存档 2M px ≈ 8MB 位图，常规档 4M px ≈ 16MB。
     * 降采样后仍然超限的图直接放弃——老电视上一次分配几十 MB 就是一次 OOM。
     */
    private val decodePixelBudget: Long = (if (lowRam) 2L else 4L) * 1024 * 1024

    private fun onMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    companion object {
        private const val DISK_DIR = "artwork-v1"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_PENDING = 32
        private const val CACHE_BYTES = 8 * 1024 * 1024
        private const val LOW_RAM_CACHE_BYTES = 4 * 1024 * 1024

        /** 封面自己的客户端：短超时、独立调度器——一张封面卡住不该拖住搜索或取流。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
