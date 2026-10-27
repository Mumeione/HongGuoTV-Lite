package com.hongguotv.lite.data.update

/**
 * 更新源 URL（2026-10-26 实测口径，换仓库只改这里）：
 *
 * - CNB 的 api.cnb.cool **匿名一律要登录**（公开仓库也 401，实测 `errcode:16`），
 *   所以清单走仓库 main 分支根目录的 `update.json`（raw 路由免登录），
 *   apk 走 release 附件直链（同样免登录）。**仓库必须保持公开。**
 * - GitHub 走 `releases/latest` API，免 token，但要带 User-Agent。
 */
object UpdateSources {
    const val CNB_MANIFEST_URL = "https://cnb.cool/Private_Auto/HongGuoTV_Lite/-/git/raw/main/update.json"

    /** release 附件直链 base（`update.json` 的 `apk` 文件名拼在后面）。 */
    const val CNB_APK_BASE = "https://cnb.cool/Private_Auto/HongGuoTV_Lite/-/releases/latest/download/"

    const val GITHUB_LATEST_URL = "https://api.github.com/repos/Mumeione/HongGuoTV-Lite/releases/latest"

    /**
     * 两个更新请求共用一份 UA（清单检查与 apk 下载各写一份会漂移）；
     * 版本取**发起方**的版本名——清单检查用本机版本，下载用远端清单里的版本。
     */
    fun userAgent(versionName: String): String = "HongGuoTV-Lite/$versionName"
}
