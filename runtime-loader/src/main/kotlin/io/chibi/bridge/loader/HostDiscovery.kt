/*
 * Runtime host discovery, versioning and installation.
 *
 * The original bridge downloaded the runtime host APK and injected its DEX
 * (RuntimeDownloader + RuntimePaths in Dart). In native form the host is a
 * separately installable APK, so discovery has to work in both directions:
 *  - the host is installed: bind its runtime service (preferred);
 *  - the host APK is available as a file: load it with [RuntimeLoader].
 */
package io.chibi.bridge.loader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.chibi.bridge.models.BridgeProtocol
import io.chibi.bridge.models.HostInfo
import java.io.File

data class InstalledHost(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val sourceDir: String,
    val installedAt: Long,
) {
    fun toHostInfo(protocolVersion: Int = BridgeProtocol.VERSION): HostInfo = HostInfo(
        hostPackage = packageName,
        hostVersionName = versionName ?: "unknown",
        hostVersionCode = versionCode.toInt(),
        bridgeProtocol = protocolVersion,
        runtimeVersion = versionName ?: "unknown",
        apiLevel = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
    )
}

object HostDiscovery {

    /**
     * All runtime hosts installed on the device. Only this project's host package
     * is probed; AnymeX's separate `com.anymex.runtimehost` bridge is never touched.
     */
    fun installedHosts(
        context: Context,
        packages: List<String> = listOf(BridgeProtocol.HOST_PACKAGE),
    ): List<InstalledHost> {
        val pm = context.packageManager
        return packages.mapNotNull { pkg ->
            try {
                val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION") info.versionCode.toLong()
                }
                InstalledHost(
                    packageName = info.packageName,
                    versionName = info.versionName,
                    versionCode = code,
                    sourceDir = info.applicationInfo?.sourceDir ?: "",
                    installedAt = info.lastUpdateTime,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    fun isHostInstalled(context: Context, pkg: String = BridgeProtocol.HOST_PACKAGE): Boolean =
        installedHosts(context, listOf(pkg)).isNotEmpty()

    fun hostApkPath(context: Context, pkg: String = BridgeProtocol.HOST_PACKAGE): String? =
        installedHosts(context, listOf(pkg)).firstOrNull()?.sourceDir

    /** Builds the user-consented install intent for a downloaded host APK. */
    fun installIntent(context: Context, apk: File): Intent {
        val authority = "${context.packageName}.chibi.runtime.host.files"
        val uri: Uri = if (apk.parentFile?.canonicalPath?.startsWith(context.cacheDir.canonicalPath) == true) {
            FileProvider.getUriForFile(context, authority, apk)
        } else {
            FileProvider.getUriForFile(context, authority, apk)
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Copies an APK bundled with the client app into cache for installation. */
    fun copyFromAssets(context: Context, assetName: String): File? = try {
        val out = File(context.cacheDir, assetName.substringAfterLast('/'))
        context.assets.open(assetName).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        out
    } catch (_: Throwable) {
        null
    }
}
