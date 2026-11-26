/*
 * Process-wide services for the runtime host: logging, preferences, HTTP and
 * extension installation. These replace the Flutter plugin's helpers
 * (AnymexExtensionRuntimeBridgePlugin's logging channel, installSourceInternal
 * and the Dart KvStore).
 */
package io.chibi.runtimehost

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import io.chibi.bridge.backend.BridgeLogger
import io.chibi.bridge.backend.LogLevel
import io.chibi.bridge.backend.PreferenceStore
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class HostLogger(private val tagPrefix: String = "ChibiHost") : BridgeLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val fullTag = "$tagPrefix/$tag"
        when (level) {
            LogLevel.DEBUG -> Log.d(fullTag, message, throwable)
            LogLevel.INFO -> Log.i(fullTag, message, throwable)
            LogLevel.WARN -> Log.w(fullTag, message, throwable)
            LogLevel.ERROR -> Log.e(fullTag, message, throwable)
        }
    }
}

/** Dart `KvStore` equivalent backed by SharedPreferences. */
class HostPreferences(context: Context, name: String = "chibi_runtime_prefs") : PreferenceStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}

object HostHttp {
    /** Shared client; the Aniyomi/CloudStream network helpers build on top of it. */
    fun build(context: Context): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .apply {
            val cacheDir = File(context.cacheDir, "http_cache").apply { mkdirs() }
            cache(okhttp3.Cache(cacheDir, 32L * 1024 * 1024))
        }
        .build()
}

/**
 * Installs extension packages (Aniyomi APKs, CloudStream plugins, Kotatsu jars)
 * with the Android package installer. Ports the plugin's `installSourceInternal`
 * / `uninstallSourceInternal` helpers, which used PackageInstaller sessions.
 */
object ExtensionInstaller {

    fun installApk(context: Context, apkPath: String): Boolean {
        val file = File(apkPath)
        if (!file.exists()) {
            Log.e("ChibiHost/Installer", "APK not found: $apkPath")
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
                val intent = Intent(context, HostInstallReceiver::class.java)
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
            Log.e("ChibiHost/Installer", "Install failed for $apkPath", t)
            false
        }
    }

    fun uninstallPackage(context: Context, packageName: String): Boolean = try {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.e("ChibiHost/Installer", "Uninstall failed for $packageName", t)
        false
    }
}
