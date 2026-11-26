/*
 * Public Kotlin SDK for ChibiExtensionRuntimeBridge.
 *
 * Replaces the Dart surface of `AnymeXExtensionBridge` / `ExtensionManager` /
 * `SourceMethods` (lib/AnymeXBridge.dart, lib/ExtensionManager.dart,
 * lib/Extensions/SourceMethods.dart) with suspending Kotlin APIs and Flows.
 *
 * Transport: binds the runtime host APK's AIDL service. When the host is not
 * installed, [ChibiRuntime.open] reports NOT_INSTALLED with the install intent,
 * and [ChibiRuntime.connect] can still be used with an APK path through
 * [ChibiRuntimeConfig.hostApkPath] (the RuntimeLoader path used by the original
 * bridge to inject the host DEX).
 */
package io.chibi.bridge.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.chibi.bridge.ipc.IChibiRuntime
import io.chibi.bridge.ipc.IChibiStreamCallback
import io.chibi.bridge.loader.HostDiscovery
import io.chibi.bridge.loader.InstalledHost
import io.chibi.bridge.models.BackendStatus
import io.chibi.bridge.models.BridgeError
import io.chibi.bridge.models.BridgeJson
import io.chibi.bridge.models.BridgeProtocol
import io.chibi.bridge.models.BridgeRequest
import io.chibi.bridge.models.BridgeResponse
import io.chibi.bridge.models.Episode
import io.chibi.bridge.models.Filter
import io.chibi.bridge.models.HostInfo
import io.chibi.bridge.models.ItemType
import io.chibi.bridge.models.Media
import io.chibi.bridge.models.Methods
import io.chibi.bridge.models.PageUrl
import io.chibi.bridge.models.Pages
import io.chibi.bridge.models.Repo
import io.chibi.bridge.models.RuntimeState
import io.chibi.bridge.models.RuntimeStatus
import io.chibi.bridge.models.Source
import io.chibi.bridge.models.SourceParams
import io.chibi.bridge.models.SourcePreference
import io.chibi.bridge.models.StreamEvent
import io.chibi.bridge.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Raised for every recoverable bridge failure. */
class ChibiBridgeException(val error: BridgeError) : Exception("${error.code}: ${error.message}") {
    constructor(code: String, message: String, backendId: String? = null) :
        this(BridgeError(code, message, backendId))
}

data class ChibiRuntimeConfig(
    /** Package id of the runtime host to bind. */
    val hostPackage: String = BridgeProtocol.HOST_PACKAGE,
    /** Extra host packages to probe (e.g. a forked host build). */
    val alternativeHostPackages: List<String> = emptyList(),
    /** When set, the host APK is loaded directly instead of being bound. */
    val hostApkPath: String? = null,
    /** Bind timeout. */
    val connectTimeoutMs: Long = 8_000,
    /** Per-request timeout. */
    val requestTimeoutMs: Long = 60_000,
)

/**
 * SDK entry point. One instance per process is enough; it is thread safe.
 *
 * ```kotlin
 * val runtime = ChibiRuntime(context)
 * runtime.connect()
 * runtime.initialize()
 * val sources = runtime.installedSources("aniyomi", ItemType.ANIME)
 * val pages = runtime.search("aniyomi", sources.first().id, "one piece")
 * ```
 */
class ChibiRuntime(
    context: Context,
    private val config: ChibiRuntimeConfig = ChibiRuntimeConfig(),
) {
    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.UNKNOWN)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var service: IChibiRuntime? = null
    private var bound: Boolean = false

    // ------------------------------------------------------------- discovery

    /** Runtime hosts installed on the device. */
    fun installedHosts(): List<InstalledHost> =
        HostDiscovery.installedHosts(appContext, listOf(config.hostPackage) + config.alternativeHostPackages)

    /** Intent that installs the host APK (user consent required). */
    fun hostInstallIntent(apk: File): Intent = HostDiscovery.installIntent(appContext, apk)

    /** Copies a host APK bundled in the client's assets into the cache dir. */
    fun stageHostApkFromAssets(assetName: String): File? = HostDiscovery.copyFromAssets(appContext, assetName)

    // ------------------------------------------------------------ connection

    suspend fun connect(): RuntimeStatus = withContext(Dispatchers.IO) {
        if (service != null) return@withContext status()
        val hosts = installedHosts()
        if (hosts.isEmpty()) {
            _state.value = RuntimeState.NOT_INSTALLED
            throw ChibiBridgeException(
                BridgeError.CODE_HOST_NOT_INSTALLED,
                "Runtime host '${config.hostPackage}' is not installed. Ship it with your app or " +
                    "install ${config.hostPackage} first.",
            )
        }
        val boundService = bindService(hosts.first())
        val protocol = boundService.protocolVersion()
        if (protocol != BridgeProtocol.VERSION) {
            throw ChibiBridgeException(
                "PROTOCOL_MISMATCH",
                "Host speaks protocol $protocol, SDK expects ${BridgeProtocol.VERSION}. Update the runtime host.",
            )
        }
        service = boundService
        _state.value = RuntimeState.INSTALLED
        status()
    }

    private suspend fun bindService(host: InstalledHost): IChibiRuntime =
        suspendCancellableCoroutine { continuation ->
            val intent = Intent(host.packageName + ".RUNTIME").setAction("io.chibi.bridge.action.RUNTIME_HOST")
                .setPackage(host.packageName)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val runtime = IChibiRuntime.Stub.asInterface(binder)
                    if (runtime == null) {
                        continuation.resumeWithException(
                            ChibiBridgeException("BIND_FAILED", "Runtime host returned no binder")
                        )
                    } else {
                        continuation.resume(runtime)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    bound = false
                    _state.value = RuntimeState.INSTALLED
                }
            }
            val started = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!started) {
                continuation.resumeWithException(
                    ChibiBridgeException("BIND_FAILED", "Unable to bind ${host.packageName}")
                )
                return@suspendCancellableCoroutine
            }
            bound = true
            continuation.invokeOnCancellation { runCatching { appContext.unbindService(connection) } }
        }

    fun disconnect() {
        if (bound) {
            runCatching { appContext.unbindService(object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }) }
            bound = false
        }
        service = null
        _state.value = RuntimeState.UNKNOWN
    }

    private suspend fun requireService(): IChibiRuntime {
        service?.let { return it }
        connect()
        return service ?: throw ChibiBridgeException("HOST_NOT_READY", "Runtime host is not connected")
    }

    // -------------------------------------------------------------- raw call

    suspend fun hostInfo(): HostInfo = withContext(Dispatchers.IO) {
        val json = requireService().hostInfo()
        BridgeJson.decodeFromString(HostInfo.serializer(), json)
    }

    suspend fun status(): RuntimeStatus = withContext(Dispatchers.IO) {
        val json = requireService().status()
        BridgeJson.decodeFromString(RuntimeStatus.serializer(), json).also { status ->
            _state.value = status.state
        }
    }

    suspend fun call(
        method: String,
        backendId: String? = null,
        sourceId: String? = null,
        args: JsonObject = JsonObject(emptyMap()),
    ): JsonElement = withContext(Dispatchers.IO) {
        val request = BridgeRequest(method, backendId, sourceId, args)
        val payload = BridgeJson.encodeToString(BridgeRequest.serializer(), request)
        val responseJson = try {
            withTimeout(config.requestTimeoutMs) { requireService().execute(payload) }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            throw ChibiBridgeException(BridgeError.CODE_TIMEOUT, "Request timed out: $method", backendId)
        }
        val response = BridgeJson.decodeFromString(BridgeResponse.serializer(), responseJson)
        response.error?.let { throw ChibiBridgeException(it) }
        response.result ?: JsonPrimitive(true)
    }

    private suspend inline fun <reified T> callTyped(
        method: String,
        backendId: String? = null,
        sourceId: String? = null,
        args: JsonObject = JsonObject(emptyMap()),
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val element = call(method, backendId, sourceId, args)
        return BridgeJson.decodeFromJsonElement(serializer, element)
    }

    // ------------------------------------------------------------- lifecycle

    suspend fun initialize(): Boolean {
        val result = call(Methods.INITIALIZE)
        _state.value = RuntimeState.READY
        return result.toString().toBoolean()
    }

    suspend fun shutdown() = withContext(Dispatchers.IO) {
        runCatching { service?.shutdown() }
        _state.value = RuntimeState.INSTALLED
    }

    // -------------------------------------------------------------- backends

    suspend fun backends(): List<BackendStatus> = callTyped(
        Methods.BACKENDS, serializer = kotlinx.serialization.builtins.ListSerializer(BackendStatus.serializer())
    )

    suspend fun backendStatus(backendId: String): BackendStatus = callTyped(
        Methods.BACKEND_STATUS, backendId = backendId, serializer = BackendStatus.serializer()
    )

    suspend fun repositories(backendId: String, type: ItemType): List<Repo> = callTyped(
        Methods.REPOSITORIES, backendId = backendId,
        args = buildJsonObject { put("type", type.name.lowercase()) },
        serializer = kotlinx.serialization.builtins.ListSerializer(Repo.serializer()),
    )

    suspend fun addRepository(backendId: String, url: String, type: ItemType) {
        call(
            Methods.ADD_REPOSITORY, backendId = backendId,
            args = buildJsonObject { put("url", url); put("type", type.name.lowercase()) },
        )
    }

    suspend fun removeRepository(backendId: String, url: String, type: ItemType) {
        call(
            Methods.REMOVE_REPOSITORY, backendId = backendId,
            args = buildJsonObject { put("url", url); put("type", type.name.lowercase()) },
        )
    }

    suspend fun availableSources(backendId: String, type: ItemType): List<Source> = callTyped(
        Methods.SOURCES, backendId = backendId,
        args = buildJsonObject { put("type", type.name.lowercase()) },
        serializer = kotlinx.serialization.builtins.ListSerializer(Source.serializer()),
    )

    suspend fun installedSources(backendId: String, type: ItemType): List<Source> = callTyped(
        Methods.INSTALLED_SOURCES, backendId = backendId,
        args = buildJsonObject { put("type", type.name.lowercase()) },
        serializer = kotlinx.serialization.builtins.ListSerializer(Source.serializer()),
    )

    suspend fun install(backendId: String, source: Source) {
        call(
            Methods.INSTALL, backendId = backendId,
            args = buildJsonObject {
                put("source", BridgeJson.encodeToString(Source.serializer(), source))
            },
        )
    }

    suspend fun uninstall(backendId: String, source: Source) {
        call(
            Methods.UNINSTALL, backendId = backendId,
            args = buildJsonObject { put("source", BridgeJson.encodeToString(Source.serializer(), source)) },
        )
    }

    suspend fun update(backendId: String, source: Source) {
        call(
            Methods.UPDATE, backendId = backendId,
            args = buildJsonObject { put("source", BridgeJson.encodeToString(Source.serializer(), source)) },
        )
    }

    suspend fun refresh(backendId: String): BackendStatus = callTyped(
        Methods.REFRESH, backendId = backendId, serializer = BackendStatus.serializer()
    )

    // --------------------------------------------------------------- sources

    suspend fun popular(backendId: String, sourceId: String, page: Int = 1, params: SourceParams? = null): Pages =
        callTyped(
            Methods.POPULAR, backendId, sourceId,
            args = buildJsonObject {
                put("page", page)
                params?.let { put("params", BridgeJson.encodeToString(SourceParams.serializer(), it)) }
            },
            serializer = Pages.serializer(),
        )

    suspend fun latest(backendId: String, sourceId: String, page: Int = 1, params: SourceParams? = null): Pages =
        callTyped(
            Methods.LATEST, backendId, sourceId,
            args = buildJsonObject {
                put("page", page)
                params?.let { put("params", BridgeJson.encodeToString(SourceParams.serializer(), it)) }
            },
            serializer = Pages.serializer(),
        )

    suspend fun search(
        backendId: String,
        sourceId: String,
        query: String,
        page: Int = 1,
        filters: List<Filter> = emptyList(),
        params: SourceParams? = null,
    ): Pages = callTyped(
        Methods.SEARCH, backendId, sourceId,
        args = buildJsonObject {
            put("query", query)
            put("page", page)
            put("filters", BridgeJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Filter.serializer()), filters
            ))
            params?.let { put("params", BridgeJson.encodeToString(SourceParams.serializer(), it)) }
        },
        serializer = Pages.serializer(),
    )

    suspend fun detail(backendId: String, sourceId: String, media: Media, params: SourceParams? = null): Media =
        callTyped(
            Methods.DETAIL, backendId, sourceId,
            args = buildJsonObject {
                put("media", BridgeJson.encodeToString(Media.serializer(), media))
                params?.let { put("params", BridgeJson.encodeToString(SourceParams.serializer(), it)) }
            },
            serializer = Media.serializer(),
        )

    suspend fun pageList(backendId: String, sourceId: String, episode: Episode): List<PageUrl> = callTyped(
        Methods.PAGES, backendId, sourceId,
        args = buildJsonObject { put("episode", BridgeJson.encodeToString(Episode.serializer(), episode)) },
        serializer = kotlinx.serialization.builtins.ListSerializer(PageUrl.serializer()),
    )

    suspend fun videoList(backendId: String, sourceId: String, episode: Episode): List<Video> = callTyped(
        Methods.VIDEOS, backendId, sourceId,
        args = buildJsonObject { put("episode", BridgeJson.encodeToString(Episode.serializer(), episode)) },
        serializer = kotlinx.serialization.builtins.ListSerializer(Video.serializer()),
    )

    /** Incremental video extraction (Dart `getVideoListStream`). */
    fun videoStream(backendId: String, sourceId: String, episode: Episode): Flow<Video> = callbackFlow {
        val runtime = requireService()
        val request = BridgeRequest(
            Methods.VIDEO_STREAM, backendId, sourceId,
            buildJsonObject { put("episode", BridgeJson.encodeToString(Episode.serializer(), episode)) },
        )
        val callback = object : IChibiStreamCallback.Stub() {
            override fun onEvent(eventJson: String) {
                val event = runCatching {
                    BridgeJson.decodeFromString(StreamEvent.serializer(), eventJson)
                }.getOrNull() ?: return
                event.video?.let { trySend(it) }
            }

            override fun onComplete() {
                close()
            }

            override fun onError(errorJson: String) {
                val error = runCatching {
                    BridgeJson.decodeFromString(BridgeError.serializer(), errorJson)
                }.getOrNull() ?: BridgeError(BridgeError.CODE_EXTENSION_FAILURE, "stream failed")
                close(ChibiBridgeException(error))
            }
        }
        runtime.streamVideos(BridgeJson.encodeToString(BridgeRequest.serializer(), request), callback)
    }.flowOn(Dispatchers.IO)

    suspend fun novelContent(
        backendId: String,
        sourceId: String,
        chapterTitle: String,
        chapterId: String,
    ): String = call(
        Methods.NOVEL_CONTENT, backendId, sourceId,
        args = buildJsonObject {
            put("chapterTitle", chapterTitle)
            put("chapterId", chapterId)
        },
    ).toString().trim('"')

    suspend fun filters(backendId: String, sourceId: String): List<Filter> = callTyped(
        Methods.FILTERS, backendId, sourceId,
        serializer = kotlinx.serialization.builtins.ListSerializer(Filter.serializer()),
    )

    suspend fun preferences(backendId: String, sourceId: String): List<SourcePreference> =
        runCatching {
            val element = call(Methods.PREFERENCES, backendId, sourceId)
            (element as? JsonArray)?.mapNotNull {
                runCatching { BridgeJson.decodeFromJsonElement(SourcePreference.serializer(), it) }.getOrNull()
            } ?: emptyList()
        }.getOrDefault(emptyList())

    suspend fun setPreference(
        backendId: String,
        sourceId: String,
        preference: SourcePreference,
        value: JsonElement,
    ): Boolean = call(
        Methods.SET_PREFERENCE, backendId, sourceId,
        args = buildJsonObject {
            put("pref", BridgeJson.encodeToString(SourcePreference.serializer(), preference))
            put("value", value)
        },
    ).toString().toBoolean()

    suspend fun cancelRequest(backendId: String?, sourceId: String?, token: String) {
        call(Methods.CANCEL, backendId, sourceId, args = buildJsonObject { put("token", token) })
    }

    suspend fun stopHttpServer(backendId: String, sourceId: String) {
        call(Methods.STOP_HTTP_SERVER, backendId, sourceId)
    }

    // ------------------------------------------------------------ torrserver

    suspend fun torrServerStatus(): JsonElement = call("torrserver.status")

    suspend fun torrServerList(): JsonElement = call("torrserver.list")

    suspend fun torrServerAdd(link: String, title: String? = null, poster: String? = null): JsonElement = call(
        "torrserver.add",
        args = buildJsonObject {
            put("link", link)
            title?.let { put("title", it) }
            poster?.let { put("poster", it) }
        },
    )

    suspend fun torrServerDrop(hash: String) = call("torrserver.drop", args = buildJsonObject { put("hash", hash) })

    suspend fun torrServerStreamUrl(hash: String, index: Int = 0): String =
        call("torrserver.streamUrl", args = buildJsonObject { put("hash", hash); put("index", index.toString()) })
            .toString().trim('"')

    suspend fun torrServerPlayUrl(hash: String, index: Int = 0): String =
        call("torrserver.playUrl", args = buildJsonObject { put("hash", hash); put("index", index.toString()) })
            .toString().trim('"')

    suspend fun configureTorrServer(serverUrl: String, username: String? = null, password: String? = null) {
        call(
            "torrserver.config",
            args = buildJsonObject {
                put("serverUrl", serverUrl)
                username?.let { put("username", it) }
                password?.let { put("password", it) }
            },
        )
    }
}
