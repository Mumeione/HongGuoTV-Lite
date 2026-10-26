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

    /** 检查或下载失败：reason 是可直接展示的中文短语。 */
    data class Failed(val reason: String) : UpdateState()
}
