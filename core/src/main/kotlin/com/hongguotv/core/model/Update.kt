package com.hongguotv.core.model

/**
 * 一次更新检查得到的远端版本信息。
 *
 * `versionCode` 是比较基准（CNB 的 `update.json` 必填）；GitHub 的 release 没有数字版本号，
 * 解析出来置 0，比较退到版本名（[com.hongguotv.core.usecase.UpdatePolicy.decide] 处理）。
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long = 0L,
    val notes: String = "",
    /**
     * 安装包 SHA-256（64 位十六进制，大小写不限；CNB 清单可给，GitHub 源没有）。
     * 空串 = 清单没给，下载后只做包名/签名校验不做哈希比对。
     * 体积校验只能拦截断，拦不住"等长但被换过内容"的包——哈希才是权威。
     */
    val sha256: String = "",
)

/** 「检查更新」一行按钮的状态机（设置页按它分派点击行为与文案）。 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()

    /** 检出可升级版本：确认键开始下载。 */
    data class Available(val manifest: UpdateManifest) : UpdateState()

    /** `percent` -1 = 服务端没给总长度（未知进度）。 */
    data class Downloading(val percent: Int) : UpdateState()

    /** 下载完成：确认键拉起系统安装器（Intent 由 app 层组装）。 */
    data class ReadyToInstall(val apkPath: String, val versionName: String) : UpdateState()

    /** 检查或下载失败：只给**类型**，文案由 UI 按类型映射（ARCHITECTURE：运行期失败不传中文字符串）。 */
    data class Failed(val failure: UpdateFailure) : UpdateState()
}

/**
 * 更新链路的失败类型。检查类两种（没货 / 网络不通），下载类两种（失败 / 不完整）；
 * 展示文案见设置页的标签映射，这里只做分类——"还没发版"和"网络断了"要分开说。
 */
enum class UpdateFailure {
    /** 双源都没货：还没发版，不是故障。 */
    NONE,

    /** 双源都不可达：网络或服务端问题。 */
    NETWORK,

    /** 下载中途失败（网络断、写盘失败等）。 */
    DOWNLOAD,

    /** 下载完成但大小与清单不符：不完整，删了重来。 */
    INCOMPLETE,

    /**
     * 下载完成但校验未通过（哈希不符 / 包名不符 / 版本未变新 / 签名不符）：
     * 不该装的包被拦下了，文案与"下载失败"分开说——重试同样的清单大概率还是拒。
     */
    VERIFICATION,
}
