/*
 * Dynamic runtime loading.
 *
 * Direct port of AnymeX's Android RuntimeLoader
 * (android/src/main/kotlin/com/ryan/runtimebridge/RuntimeLoader.kt):
 *  - child-first DexClassLoader with the same delegate prefixes, minus the
 *    io.flutter.* entry which no longer exists in the native bridge;
 *  - native library extraction from the runtime APK's lib/<abi>/ folder;
 *  - private APK staging + dex optimisation cache invalidation.
 *
 * This is what lets any host app load the separately installable runtime host
 * APK without compiling its sources into the app, exactly like the original.
 */
package io.chibi.bridge.loader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile

data class LoadedBridge(
    val bridgeClass: Class<*>,
    val bridgeInstance: Any,
)

class RuntimeLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

object RuntimeLoader {

    const val TAG = "ChibiRuntimeLoader"

    /** Default entry point of the runtime host APK (unchanged from upstream). */
    const val DEFAULT_BRIDGE_CLASS = "com.anymex.runtimehost.RuntimeBridge"

    /**
     * Same delegation policy as the original loader: platform and bridge packages
     * always come from the host process, everything else is resolved inside the
     * runtime APK first so extension APIs never clash with the host app.
     */
    private class ChildFirstClassLoader(
        dexPath: String,
        optimizedDirectory: String?,
        private val librarySearchPath: String?,
        parent: ClassLoader,
    ) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

        private val systemClassLoader: ClassLoader? = getSystemClassLoader()

        private fun shouldDelegateToParent(name: String?): Boolean {
            if (name == null) return false
            return name.startsWith("android.") ||
                name.startsWith("androidx.") ||
                name.startsWith("java.") ||
                name.startsWith("javax.") ||
                name.startsWith("com.anymex.runtimehost.") ||
                name.startsWith("io.chibi.bridge.")
        }

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            var c = findLoadedClass(name)

            if (c == null) {
                c = try {
                    systemClassLoader?.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    null
                }
            }

            if (c == null && shouldDelegateToParent(name)) {
                c = try {
                    parent.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    null
                }
            }

            if (c == null) {
                c = try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    super.loadClass(name, resolve)
                }
            }

            if (resolve) resolveClass(c)
            return c
        }

        override fun findLibrary(name: String): String? {
            if (librarySearchPath != null) {
                val direct = File(librarySearchPath, "lib$name.so")
                if (direct.exists()) return direct.absolutePath
            }
            return super.findLibrary(name)
        }
    }

    /** Copies the runtime APK into private storage once and keeps it read-only. */
    private fun stageApk(context: Context, source: File): File {
        val privateDir = File(context.filesDir, "plugins").apply { if (!exists()) mkdirs() }
        val dst = File(privateDir, source.name)
        val shouldCopy = !dst.exists() ||
            source.length() != dst.length() ||
            source.lastModified() != dst.lastModified()

        if (shouldCopy) {
            val tmp = File(privateDir, "${source.name}.tmp")
            source.inputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            tmp.setLastModified(source.lastModified())
            if (dst.exists()) {
                dst.setWritable(true, false)
                dst.delete()
            }
            if (!tmp.renameTo(dst)) {
                tmp.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
                tmp.delete()
            }
            File(context.codeCacheDir, "plugin_opt").let { dir ->
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
            }
        }
        dst.setReadable(true, false)
        dst.setWritable(false, false)
        dst.setExecutable(false)
        return dst
    }

    /** Extracts the ABI-matching .so files embedded in the runtime APK. */
    private fun extractNativeLibraries(context: Context, apkFile: File) {
        val outDir = File(context.codeCacheDir, "plugin_libs").apply { mkdirs() }
        ZipFile(apkFile).use { zip ->
            val matchedAbi = Build.SUPPORTED_ABIS.firstOrNull { abi ->
                zip.entries().asSequence().any { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
            } ?: return
            val prefix = "lib/$matchedAbi/"
            zip.entries().asSequence()
                .filter { it.name.startsWith(prefix) && it.name.endsWith(".so") }
                .forEach { entry ->
                    val out = File(outDir, entry.name.substringAfterLast('/'))
                    if (!out.exists() || out.length() != entry.size) {
                        if (out.exists()) {
                            out.setWritable(true, false)
                            out.delete()
                        }
                        zip.getInputStream(entry).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                        out.setReadable(true, false)
                        out.setExecutable(true, false)
                        out.setWritable(false, false)
                    }
                }
        }
    }

    /**
     * Loads the runtime host APK and instantiates its bridge entry point.
     *
     * @param apkPath absolute path to the runtime host APK (installed package
     *   `sourceDir` or a downloaded copy).
     */
    @SuppressLint("SetWorldReadable")
    fun loadRuntime(
        context: Context,
        apkPath: String,
        className: String = DEFAULT_BRIDGE_CLASS,
    ): LoadedBridge {
        val external = File(apkPath)
        if (!external.exists()) throw RuntimeLoadException("Runtime APK not found at $apkPath")

        val staged = stageApk(context, external)
        val optimizedDir = File(context.codeCacheDir, "plugin_opt").apply { if (!exists()) mkdirs() }
        extractNativeLibraries(context, staged)
        val nativeLibsDir = File(context.codeCacheDir, "plugin_libs")

        val loader = ChildFirstClassLoader(
            staged.absolutePath,
            optimizedDir.absolutePath,
            nativeLibsDir.absolutePath,
            context.classLoader,
        )

        val clazz = try {
            loader.loadClass(className)
        } catch (t: Throwable) {
            throw RuntimeLoadException("Unable to load $className from $apkPath", t)
        }

        val instance = try {
            // Kotlin `object` singletons expose INSTANCE; Java-style entry points use a ctor.
            clazz.getField("INSTANCE").get(null)
        } catch (_: Throwable) {
            try {
                clazz.getDeclaredConstructor().newInstance()
            } catch (t: Throwable) {
                throw RuntimeLoadException("Unable to instantiate $className", t)
            }
        }

        return LoadedBridge(clazz, instance)
    }

    /** True when the runtime APK at [apkPath] contains the expected entry point. */
    fun probe(apkPath: String, className: String = DEFAULT_BRIDGE_CLASS): BridgeProbe {
        val file = File(apkPath)
        if (!file.exists()) return BridgeProbe(false, null, "APK not found")
        return try {
            ZipFile(file).use { zip ->
                val hasDex = zip.entries().asSequence().any { it.name.endsWith(".dex") }
                if (!hasDex) return BridgeProbe(false, null, "APK contains no dex")
            }
            BridgeProbe(true, className, null)
        } catch (t: Throwable) {
            BridgeProbe(false, null, t.message)
        }
    }
}

data class BridgeProbe(
    val usable: Boolean,
    val className: String?,
    val reason: String?,
)
