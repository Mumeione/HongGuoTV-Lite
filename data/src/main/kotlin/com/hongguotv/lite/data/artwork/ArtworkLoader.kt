package com.hongguotv.lite.data.artwork

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.hongguotv.core.port.ArtworkPort
import com.hongguotv.core.port.ArtworkSpec
import com.hongguotv.core.protocol.CoverUrls
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
 * `ArtworkPort` 的实现（S7 起草，S9 收口落盘策略，C1 补翻页预热）。
 *
 * 只做四件必须做的事，按远端尺寸二次校准仍留给后续：
 * 1. **内存 LRU**：多页来回切时同一张封面不该重复解码，容量按 `isLowRamDevice` 分档；
 * 2. **收藏 / 历史封面落盘**：这些封面会被反复回看，落盘值得；一次性封面（搜索 / 详情）不占电视存储；
 * 3. **按目标尺寸降采样**：电视上一次解码一张 4K 海报就是一次 OOM；
 * 4. **翻页预热**：下一页数据已在内存时，用 [prefetch] 先把封面取进内存，翻过去直接命中。
 *
 * 线程约定：`load` / `prefetch` / `cancelPage` 可以从任意线程调，内部会切到主线程；
 * 解码与下载在后台线程，结果回主线程贴图（贴图要求视图还在、且这一代页面还没被换掉）。
 *
 * **落盘资格只有收藏 / 历史**（`ArtworkSpec.persist`）：这些封面会被反复回看，落盘值得；
 * 搜索、详情、季线只记地址，看过就扔。落盘发生在两处：
 * 1. 取图那一趟（下载完顺手写盘）；
 * 2. **内存命中但磁盘没有**时补写一次（同一张图可能先在搜索页以不落盘的方式取过，
 *    不补写就永远进不了磁盘——同一张封面会因此重启后仍是空框）。
 *
 * 与旧实现（337 行）的差别是删掉了没有消费者的部分：按布局变化重新请求
 * （规格里尺寸是显式给的）、`refillVisible`。
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
     *
     * [diskOnly] 是"只落盘"的任务：图已经在内存里（命中 LRU）但磁盘没有，补写一次盘，
     * 不贴图、不解码。收藏/历史的封面就是靠它做到"重启后还在"。
     */
    private class Job(
        val key: Key,
        val generation: Int,
        var priority: Int,
        val order: Long,
        @Volatile var persist: Boolean,
        val diskOnly: Boolean = false,
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

    private val disk = ArtworkDisk(File(app.cacheDir, DISK_DIR), reencode = ::reencodeForDisk)

    private val signal = Object()
    /** 数值小的先做（聚焦项最先）；同优先级按入队先后。顺序由 [Job.compareTo] 定义。 */
    private val pending = PriorityQueue<Job>()
    private val jobs = HashMap<Key, Job>()

    /** 已经排了"只落盘"任务的 key：同一张图不重复补写。 */
    private val diskWrites = HashSet<Key>()
    /** 预热失败的 key：同一代页面内不再反复重试（卡片自己的 `load` 不受影响）。 */
    private val prefetchFailed = HashSet<Key>()
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
        if (closed) return
        // HEIC 变体在 API 23 上解不了：入口处统一换成可解码的那份（老数据里存的就是 HEIC）
        val normalized = CoverUrls.normalize(url)
        if (normalized.isBlank()) return
        // 尺寸归桶：布局差几像素不该换一张图（见 ArtworkSizing.cacheSize）
        val size = ArtworkSizing.cacheSize(spec.widthPx, spec.heightPx)

        val previous = bindings[view]
        if (previous != null && previous.generation == generation && previous.url == normalized &&
            previous.width == size.width && previous.height == size.height
        ) {
            // 同一张图再要一次（聚焦、重渲染）：只提升优先级 / 补落盘，不动图
            previous.priority = spec.priority.coerceIn(ArtworkSpec.PRIORITY_FOCUSED, ArtworkSpec.PRIORITY_VISIBLE)
            previous.persist = previous.persist || spec.persist
            previous.failed = null
            request(previous)
            return
        }
        if (previous != null) detach(previous)
        // 只有换了图才清屏：同一张图换尺寸时保留旧位图直到新图贴上，
        // 否则每次重排都白一下（hero 跟着右列文字高度变化时最明显）
        if (previous == null || previous.url != normalized) view.setImageDrawable(null)
        val binding = Binding(
            view = WeakReference(view),
            url = normalized,
            width = size.width,
            height = size.height,
            priority = spec.priority.coerceIn(ArtworkSpec.PRIORITY_FOCUSED, ArtworkSpec.PRIORITY_VISIBLE),
            persist = spec.persist,
            generation = generation,
        )
        bindings[view] = binding
        request(binding)
    }

    override fun prefetch(url: String, spec: ArtworkSpec) {
        if (!onMain()) {
            main.post { prefetch(url, spec) }
            return
        }
        if (closed) return
        val normalized = CoverUrls.normalize(url)
        if (normalized.isBlank() || spec.widthPx <= 0 || spec.heightPx <= 0) return
        // 与 load() 同一个归桶口径：否则预热取回来的图，翻页时永远命不中
        val size = ArtworkSizing.cacheSize(spec.widthPx, spec.heightPx)
        val key = Key(normalized, size.width, size.height)
        if (cache.get(key) != null) return
        synchronized(signal) {
            if (closed || key in prefetchFailed || jobs.containsKey(key)) return
            // 预热不挤占队列：满了就放弃这一张，绝不淘汰正在等图的可见卡
            if (pending.size >= MAX_PENDING) return
            val job = Job(
                key = key,
                generation = generation,
                priority = ArtworkSpec.PRIORITY_PREFETCH,
                order = ++order,
                persist = false,
            )
            jobs[key] = job
            pending += job
            signal.notifyAll()
        }
    }

    override fun cancelPage(clearMemory: Boolean) {
        if (!onMain()) {
            main.post { cancelPage(clearMemory) }
            return
        }
        generation++
        bindings.clear()
        val cancelled = synchronized(signal) {
            prefetchFailed.clear()
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
            // 内存命中但磁盘没有：补写一次盘。
            // 收藏/历史的封面要"重启后还在"，而同一张图可能是先在搜索页（不落盘）取过的——
            // 不补写的话它永远进不了磁盘（同一张封面会因此重启后仍是空框）。
            if (binding.persist) persistOnlyLater(key)
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
        if (job.diskOnly) diskWrites.remove(job.key)
        job.listeners.clear()
    }

    /**
     * 排一个"只下载不贴图"的任务，把内存里已有的图补写进磁盘。
     *
     * 判据只有一条：**要落盘 + 磁盘还没有 + 没排过**。同键已有任务时把那个任务的
     * `persist` 打开就行，不另起一趟请求。
     */
    private fun persistOnlyLater(key: Key) {
        if (disk.has(key.url)) return
        synchronized(signal) {
            if (closed || key in diskWrites) return
            jobs[key]?.let { running ->
                running.persist = true
                return
            }
            if (pending.size >= MAX_PENDING) return
            diskWrites += key
            val job = Job(
                key = key,
                generation = generation,
                priority = ArtworkSpec.PRIORITY_VISIBLE,
                order = ++order,
                persist = true,
                diskOnly = true,
            )
            jobs[key] = job
            pending += job
            signal.notifyAll()
        }
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

            val bitmap = if (job.diskOnly) {
                // 只落盘：不解码、不占内存缓存
                downloadToDisk(job)
                null
            } else {
                try {
                    loadBitmap(job)
                } catch (oom: OutOfMemoryError) {
                    // 内存已经到底了：清一次缓存，这张放弃（下次滚动回来还有机会）
                    cache.evictAll()
                    null
                } catch (error: Exception) {
                    null
                }
            }

            val listeners = synchronized(signal) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                if (job.diskOnly) diskWrites.remove(job.key)
                val wanted = job.listeners.isNotEmpty()
                if (!job.cancelled && !closed && bitmap != null) cache.put(job.key, bitmap)
                // 无监听者 = 预热任务：失败后记下来，同一代页面内不再反复重试
                if (!job.cancelled && !closed && bitmap == null && !job.diskOnly && !wanted) {
                    prefetchFailed += job.key
                }
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

    /** 只把字节写进磁盘（图已经在内存里了）。失败无所谓：下次取图还会再试。 */
    private fun downloadToDisk(job: Job) {
        if (job.cancelled || disk.has(job.key.url)) return
        download(job)
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

    /**
     * 落盘前的重编码（V1.0.0 容量治理）：原图最大可到 4MB，收藏/历史的封面在磁盘上
     * 没必要保真到那个程度。降到长边 [DISK_LONG_EDGE]px 的 JPEG（质量 [DISK_JPEG_QUALITY]），
     * 单张从 MB 级变几十 KB。
     *
     * 返回 null = 存原图：解码失败（给 [ArtworkDisk] 兜底），或编完不比原图小
     * （小图再编码只会变大）。线程：磁盘工作线程，不在主线程。
     */
    private fun reencodeForDisk(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= DISK_LONG_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scaled = if (maxOf(decoded.width, decoded.height) > DISK_LONG_EDGE) {
            val scale = DISK_LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            val created = runCatching {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }.getOrNull()
            // 缩放成功后释放大图：老设备上原生位图不靠 GC 回收，攒几张就是一次 OOM
            if (created != null && created !== decoded) decoded.recycle()
            created ?: decoded
        } else {
            decoded
        }
        // JPEG 没有透明通道：垫白底再压，别让带 alpha 的图黑掉
        val flat = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.hasAlpha()) {
            val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            opaque.eraseColor(Color.WHITE)
            Canvas(opaque).drawBitmap(scaled, 0f, 0f, null)
            if (opaque !== scaled) scaled.recycle()
            opaque
        } else {
            scaled
        }
        val out = ByteArrayOutputStream()
        val ok = flat.compress(Bitmap.CompressFormat.JPEG, DISK_JPEG_QUALITY, out)
        // 压完无条件回收：flat 是这条路径上最后一份位图（缩放失败时它与 decoded 同一张，
        // 条件回收会把 decoded 漏在原生堆上——老设备攒几张就是一次 OOM）
        flat.recycle()
        if (!ok) return null
        val encoded = out.toByteArray()
        return encoded.takeIf { it.size < bytes.size }
    }

    companion object {
        private const val DISK_DIR = "artwork-v1"

        /** 落盘封面的重编码口径：长边 512px、JPEG 质量 80（~30-80KB/张，显示尺寸足够）。 */
        private const val DISK_LONG_EDGE = 512
        private const val DISK_JPEG_QUALITY = 80
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
