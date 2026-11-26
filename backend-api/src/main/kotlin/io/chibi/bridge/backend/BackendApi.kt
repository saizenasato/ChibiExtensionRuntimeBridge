/*
 * Kotlin replacement for the Dart `Extension` / `SourceMethods` abstractions
 * (AnymeX lib/Extensions/Extensions.dart and lib/Extensions/SourceMethods.dart).
 *
 * Every backend — whether it executes DEX extensions, JavaScript modules, a
 * declarative rule engine or a native service — implements the same surfaces so
 * the runtime host and SDK stay backend agnostic while backend specific
 * capabilities remain available through [ExtensionBackend.capabilities].
 */
package io.chibi.bridge.backend

import android.content.Context
import io.chibi.bridge.models.BackendCapabilities
import io.chibi.bridge.models.BackendStatus
import io.chibi.bridge.models.Episode
import io.chibi.bridge.models.Filter
import io.chibi.bridge.models.ItemType
import io.chibi.bridge.models.Media
import io.chibi.bridge.models.PageUrl
import io.chibi.bridge.models.Pages
import io.chibi.bridge.models.Repo
import io.chibi.bridge.models.Source
import io.chibi.bridge.models.SourceParams
import io.chibi.bridge.models.SourcePreference
import io.chibi.bridge.models.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/** Logging surface shared by backends. Mirrors Dart `Logger`. */
interface BridgeLogger {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String, t: Throwable? = null) = log(LogLevel.WARN, tag, message, t)
    fun error(tag: String, message: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, message, t)
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Per-user preferences handed to a backend (ports `KvStore` usage in Dart). */
interface PreferenceStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun all(): Map<String, String>
}

/**
 * Shared Tachiyomi-style execution engine.
 *
 * Tsundoku and iReader extensions are Tachiyomi-compatible packages; upstream
 * the Dart bridges for both routed every call into the runtime host's Aniyomi
 * manager (`getInstalledMangaExtensions`, `aniyomiGetPopular`, ...). The host
 * implements this interface on top of the ported Aniyomi engine, so the
 * Tsundoku/iReader modules keep their own repository handling while using the
 * same battle-tested class loading.
 */
interface TachiyomiEngine {
    suspend fun installed(directory: String): List<Source>
    suspend fun source(sourceId: String): TachiyomiSource?

    interface TachiyomiSource {
        val source: Source
        suspend fun getPopular(page: Int, params: SourceParams? = null): Pages
        suspend fun getLatestUpdates(page: Int, params: SourceParams? = null): Pages
        suspend fun search(query: String, page: Int, params: SourceParams? = null): Pages
        suspend fun getDetail(media: Media, params: SourceParams? = null): Media
        suspend fun getPageList(episode: Episode, params: SourceParams? = null): List<PageUrl>
        suspend fun getNovelContent(chapterTitle: String, chapterId: String): String?
        suspend fun cancelRequest(token: String)
    }
}

/** Everything a backend is allowed to touch. */
class BackendContext(
    val appContext: Context,
    val filesDir: java.io.File,
    val cacheDir: java.io.File,
    val httpClient: OkHttpClient,
    val logger: BridgeLogger,
    val preferences: PreferenceStore,
    /** "aniyomi" style runtime settings (network UA, DoH, image proxy...). */
    val settings: Map<String, String> = emptyMap(),
    /** Shared Tachiyomi execution engine (null when unavailable). */
    val tachiyomi: TachiyomiEngine? = null,
)

/**
 * One installed extension. The Dart contract calls this `SourceMethods`;
 * `source` is the descriptor that was installed.
 */
interface ExtensionSource {
    val source: Source

    suspend fun getPopular(page: Int, params: SourceParams? = null): Pages
    suspend fun getLatestUpdates(page: Int, params: SourceParams? = null): Pages
    suspend fun search(query: String, page: Int, filters: List<Filter> = emptyList(), params: SourceParams? = null): Pages
    suspend fun getDetail(media: Media, params: SourceParams? = null): Media
    suspend fun getPageList(episode: Episode, params: SourceParams? = null): List<PageUrl>
    suspend fun getVideoList(episode: Episode, params: SourceParams? = null): List<Video>

    /** Dart streams video results incrementally; native backends may return a single emission. */
    fun getVideoListStream(episode: Episode, params: SourceParams? = null): Flow<Video> = emptyFlow()

    suspend fun getFilterList(): List<Filter> = emptyList()
    suspend fun stopHttpServer() {}
    suspend fun getNovelContent(chapterTitle: String, chapterId: String, params: SourceParams? = null): String? = null
    suspend fun cancelRequest(token: String) {}
    suspend fun getPreference(): List<SourcePreference> = emptyList()
    suspend fun setPreference(pref: SourcePreference, value: JsonElement): Boolean = false
}

/** One backend family: repository handling, install lifecycle and source lookup. */
interface ExtensionBackend {
    val id: String
    val displayName: String
    fun capabilities(): BackendCapabilities

    suspend fun attach(context: BackendContext)
    suspend fun detach() {}

    suspend fun status(): BackendStatus

    suspend fun fetchAvailable(type: ItemType): List<Source>
    suspend fun fetchInstalled(type: ItemType): List<Source>

    suspend fun addRepository(url: String, type: ItemType)
    suspend fun removeRepository(url: String, type: ItemType)
    suspend fun repositories(type: ItemType): List<Repo>

    suspend fun install(source: Source)
    suspend fun uninstall(source: Source)
    suspend fun update(source: Source)

    /** Returns a usable source instance for [sourceId], or null when not installed. */
    fun source(sourceId: String): ExtensionSource?
}

/** Thrown by backends when an extension cannot satisfy a call. */
class BackendException(
    val code: String,
    message: String,
    val backendId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Simple holder so backends can share the host logger without DI. */
object BackendLoggerHolder {
    @Volatile
    var logger: BridgeLogger? = null
}

/** In-memory registry used by the runtime host to expose every backend. */
class BackendRegistry {
    private val backends = LinkedHashMap<String, ExtensionBackend>()

    fun register(backend: ExtensionBackend) {
        backends[backend.id] = backend
    }

    fun all(): List<ExtensionBackend> = backends.values.toList()

    fun get(id: String): ExtensionBackend? = backends[id]

    fun ids(): List<String> = backends.keys.toList()
}
