package com.hongguotv.lite.data.update

import android.content.pm.PackageManager
import android.os.Build
import com.hongguotv.core.model.UpdateManifest
import java.io.File
import java.io.IOException

/**
 * 生产用 [ApkVerifier]：APK 落盘之后、进 [UpdateState.ReadyToInstall] 之前的身份校验。
 * 文件哈希对清单的部分在 [UpdateManager] 里做，这里管"这个包
 * 是不是我们签发的更新"：
 *
 * 1. **包名**必须是本应用——不同包名时系统安装器按"新应用"安装，同签名检查不会触发，
 *    这是"改一个清单字段就装上任意 APK"的关键缺口；
 * 2. **versionCode** 必须比本机新——防降级与同版重装；
 * 3. **签名证书** SHA-256 必须等于发布密钥——哈希随清单走，清单可被污染；
 *    证书钉死在本地才是独立于清单的信任根。
 */
class SignedApkVerifier(
    private val packageManager: PackageManager,
    private val localPackage: String,
    private val localVersionCode: Long,
    /** 密钥轮换或专项测试时注入；生产用默认值（当前发布密钥）。 */
    private val trustedCertSha256: String = RELEASE_CERT_SHA256,
) : ApkVerifier {

    override fun verify(apk: File, manifest: UpdateManifest) {
        @Suppress("DEPRECATION") // GET_SIGNATURES 在 API 28 起废弃但仍可用；SigningInfo 是 API 28+，minSdk 23 上做不了唯一通道
        val info = packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: throw ApkVerificationException("archive info unparseable: ${apk.name}")
        if (info.packageName != localPackage) {
            throw ApkVerificationException("package mismatch: ${info.packageName} != $localPackage")
        }
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        if (apkVersionCode <= localVersionCode) {
            throw ApkVerificationException("version not newer: $apkVersionCode <= $localVersionCode")
        }
        val certificate = info.signatures?.firstOrNull()?.toByteArray()
            ?: throw ApkVerificationException("no signing certificate: ${apk.name}")
        val actual = sha256Hex(certificate)
        if (actual != trustedCertSha256) {
            throw ApkVerificationException("signing certificate mismatch: $actual")
        }
    }

    companion object {
        /**
         * 发布签名证书的 SHA-256（`keystore/checkin-release.jks` 别名 `checkin`，keytool 实测）。
         * 换发布密钥时必须同步改这里，否则全量用户的更新都会被签名校验拦下。
         */
        const val RELEASE_CERT_SHA256 = "5ac0c9ab377f853fe0b1974a2d33e1fef6c0dbef0d1d3334e6cdf17adb91e164"
    }
}
