/*
 * Package installation used by the Tachiyomi-family backends (Tsundoku, iReader).
 * Mirrors the plugin helpers `installSourceInternal` / `uninstallSourceInternal`
 * from AnymeX's Flutter plugin, but without crossing a channel.
 */
package io.chibi.bridge.backend

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import java.io.File

object ExtensionInstallerBridge {

    private const val TAG = "ChibiExtensionInstaller"

    fun install(context: Context, apkPath: String): Boolean {
        val file = File(apkPath)
        if (!file.exists()) {
            Log.e(TAG, "APK not found: $apkPath")
            return false
        }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("chibi_extension", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // The host app receives the result through its own receiver.
                val intent = Intent(context.packageName + ".CHIBI_INSTALL_RESULT")
                intent.setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Install failed for $apkPath", t)
            false
        }
    }

    fun uninstall(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Uninstall failed for $packageName", t)
            false
        }
    }
}
