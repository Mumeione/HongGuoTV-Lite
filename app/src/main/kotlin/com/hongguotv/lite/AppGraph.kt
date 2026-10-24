package com.hongguotv.lite

import android.content.Context
import com.hongguotv.core.port.ContentPort
import com.hongguotv.core.port.MediaPort
import com.hongguotv.core.port.Navigator
import com.hongguotv.lite.data.artwork.ArtworkLoader
import com.hongguotv.lite.data.content.AnonymousIdentity
import com.hongguotv.lite.data.content.ContentRepository
import com.hongguotv.lite.data.diagnostics.LogcatDiagnostics
import com.hongguotv.lite.data.http.OkHttpTransport
import com.hongguotv.lite.data.library.PrefsLibrary
import com.hongguotv.lite.data.library.SharedPreferencesStore
import com.hongguotv.lite.data.media.StreamTransport
import com.hongguotv.lite.feature.catalog.CatalogFeature
import com.hongguotv.lite.feature.detail.DetailFeature
import com.hongguotv.lite.feature.player.PlayerFeature
import com.hongguotv.lite.uikit.ui.UiKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * 依赖组装：**全工程唯一知道"哪个端口由谁实现"的地方**。
 *
 * 三个 feature 只认识 `core.port` 的接口；换上游、换存储、换取流实现都只改这个文件。
 * 它同时持有两样活东西的收摊责任：主线程作用域与封面线程池（[close]）。
 *
 * 这里刻意不做"按需创建"：端口实现都是无状态或自持缓存的单例，
 * 一次建好、随 Activity 一起销毁，比懒加载的判空分支干净。
 */
internal class AppGraph(
    context: Context,
    navigator: Navigator,
) {

    /**
     * **主线程作用域只有一个**。它比页面活得久：写进度、写设置、跨季跳转都挂在它上面，
     * 所以页面被路由销毁时"还没写就被取消"这件事不会发生（S5 踩过的坑）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 内容端点与取流共用一个客户端（超时口径 15/30/45s，见 OkHttpTransport），
    // 封面另起一个短超时的：一张封面不该占住搜索或取流的连接。
    private val http: OkHttpClient = OkHttpTransport.defaultClient()
    private val artworkHttp: OkHttpClient = ArtworkLoader.defaultClient()

    /** 诊断端口：五段探针与关键事件进 logcat（`adb logcat -s HongGuoTV`）。 */
    private val diagnostics = LogcatDiagnostics()

    /** 本地库：进度 / 收藏 / 设置三个端口的同一份事实（prefs 文件名 `native-library-v1` 不许改）。 */
    private val store = SharedPreferencesStore(context)
    private val library: PrefsLibrary = PrefsLibrary(store)

    private val content: ContentPort = ContentRepository(
        http = OkHttpTransport(http, diagnostics),
        // 匿名设备身份：内置那对固定值已被平台拉黑（取流回 200 空体），这里用本机自己的一对。
        identity = AnonymousIdentity.of(store),
        diagnostics = diagnostics,
    )

    /** 取流：`ContentPort.stream` + Range 分段 + CENC 逐样本解密，播放器只拿到一条可读流。 */
    private val media: MediaPort = StreamTransport.of(content, http, diagnostics)

    /** 封面端口。宿主在 `onTrimMemory` / 换路由时直接驱动它（`trim` / `cancelPage`）。 */
    val artwork: ArtworkLoader = ArtworkLoader(context, artworkHttp)

    /** 设计系统的唯一入口：令牌 + 封面端口 + 控件工厂。 */
    val ui: UiKit = UiKit(context, artwork = artwork)

    /** 目录类四页。启动页偏好只在这一处读一次（之后由目录页自己切）。 */
    val catalog: CatalogFeature = CatalogFeature(
        ui = ui,
        content = content,
        progress = library,
        favorites = library,
        settings = library,
        navigator = navigator,
        scope = scope,
        versionName = BuildConfig.VERSION_NAME,
        initialTab = library.current().startupTab,
    )

    val detail: DetailFeature = DetailFeature(
        ui = ui,
        content = content,
        progress = library,
        favorites = library,
        settings = library,
        navigator = navigator,
        scope = scope,
    )

    val player: PlayerFeature = PlayerFeature(
        ui = ui,
        content = content,
        progress = library,
        favorites = library,
        settings = library,
        navigator = navigator,
        media = media,
        scope = scope,
    )

    /** 收摊：取消作用域、停封面线程池、把排队中的本地写入落盘。 */
    fun close() {
        scope.cancel()
        artwork.close()
        library.flush()
    }
}
