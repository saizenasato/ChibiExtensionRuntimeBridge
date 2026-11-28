/*
 * Native replacement for the AnymeX Flutter plugin's `AnymexExtensionRuntimeBridgePlugin`.
 *
 * Upstream the plugin received MethodChannel calls and reflected into the
 * runtime host APK's `io.chibi.runtimehost.RuntimeBridge`. Here the same
 * dispatch happens in-process inside the host APK and is exposed through the
 * AIDL service. Every method name mirrors the original channel handlers so the
 * behaviour stays traceable to upstream.
 */
package io.chibi.runtimehost

import android.content.Context
import android.os.Build
import io.chibi.runtimehost.RuntimeBridge
import io.chibi.bridge.backend.BackendContext
import io.chibi.bridge.backend.BackendException
import io.chibi.bridge.backend.BackendRegistry
import io.chibi.bridge.backend.BackendLoggerHolder
import io.chibi.bridge.backend.ExtensionBackend
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
import io.chibi.bridge.models.Pages
import io.chibi.bridge.models.RuntimeState
import io.chibi.bridge.models.RuntimeStatus
import io.chibi.bridge.models.Source
import io.chibi.bridge.models.SourceParams
import io.chibi.bridge.models.SourcePreference
import io.chibi.bridge.models.StreamEvent
import io.chibi.bridge.models.Video
import io.chibi.bridge.models.str
import io.chibi.runtime.backends.ireader.IReaderBackend
import io.chibi.runtime.backends.legado.LegadoBackend
import io.chibi.runtime.backends.lnreader.LnReaderBackend
import io.chibi.runtime.backends.mangayomi.MangayomiBackend
import io.chibi.runtime.backends.sora.SoraBackend
import io.chibi.runtime.backends.tsundoku.TsundokuBackend
import io.chibi.bridge.torrserver.TorrServerAddon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object HostBridge {

    private const val TAG = "ChibiHostBridge"
    const val VERSION_NAME = "2.0.1-chibi"

    private const val PREFS_NAME = "chibi_runtime_prefs"

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var logger: HostLogger
    private lateinit var preferences: HostPreferences
    private lateinit var backendContext: BackendContext

    val registry = BackendRegistry()

    private var torrServer: TorrServerAddon? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun initialize(context: Context, settings: Map<String, String> = emptyMap()) {
        if (initialized) return
        appContext = context.applicationContext
        logger = HostLogger()
        preferences = HostPreferences(appContext, PREFS_NAME)

        val client = HostHttp.build(appContext)
        backendContext = BackendContext(
            appContext = appContext,
            filesDir = appContext.filesDir,
            cacheDir = appContext.cacheDir,
            httpClient = client,
            logger = logger,
            preferences = preferences,
            settings = settings,
            tachiyomi = HostTachiyomiEngine(appContext, managerId = "tsundoku"),
        )
        BackendLoggerHolder.logger = logger

        registry.register(AniyomiBackend())
        registry.register(CloudStreamBackend())
        registry.register(KotatsuBackend())
        registry.register(LegadoBackend())
        registry.register(IReaderBackend())
        registry.register(TsundokuBackend())
        registry.register(LnReaderBackend())
        registry.register(SoraBackend())
        registry.register(MangayomiBackend())

        torrServer = TorrServerAddon(
            client = client,
            preferences = preferences,
            logger = logger,
            initialConfig = TorrServerAddon.Config(
                serverUrl = preferences.getString("torrserver_url") ?: "http://127.0.0.1:8090",
                username = preferences.getString("torrserver_user"),
                password = preferences.getString("torrserver_password"),
            ),
        )

        initialized = true
        logger.info(TAG, "Runtime host $VERSION_NAME initialised with ${registry.ids().size} backends")
    }

    fun hostInfo(): HostInfo {
        val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") pkgInfo.versionCode
        }
        return HostInfo(
            hostPackage = appContext.packageName,
            hostVersionName = pkgInfo.versionName ?: VERSION_NAME,
            hostVersionCode = code,
            bridgeProtocol = BridgeProtocol.VERSION,
            runtimeVersion = VERSION_NAME,
            apiLevel = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    suspend fun status(): RuntimeStatus = withContext(Dispatchers.IO) {
        val statuses = registry.all().map { backend ->
            runCatching { backend.status() }.getOrElse { t ->
                BackendStatus(
                    id = backend.id,
                    displayName = backend.displayName,
                    available = false,
                    initialized = false,
                    message = t.message,
                )
            }
        }
        RuntimeStatus(
            state = if (initialized) RuntimeState.READY else RuntimeState.UNKNOWN,
            host = hostInfo(),
            backends = statuses,
            loadedExtensions = statuses.sumOf { it.installedCount },
        )
    }

    private fun requireBackend(id: String?): ExtensionBackend {
        if (id.isNullOrBlank()) throw BackendException(BridgeError.CODE_UNKNOWN_BACKEND, "backendId is required")
        return registry.get(id) ?: throw BackendException(
            BridgeError.CODE_UNKNOWN_BACKEND, "Backend '$id' is not registered in this runtime", id
        )
    }

    private fun apiType(args: JsonObject): ItemType {
        val raw = args.str("type") ?: args.str("itemType") ?: "anime"
        return when (raw.lowercase()) {
            "anime", "1" -> ItemType.ANIME
            "novel", "2" -> ItemType.NOVEL
            else -> ItemType.MANGA
        }
    }

    private fun apiSource(json: String?): Source = json?.let {
        BridgeJson.decodeFromString(Source.serializer(), it)
    } ?: throw BackendException("INVALID_ARG", "source payload is required")

    private fun apiEpisode(obj: JsonObject?): Episode = obj?.let {
        BridgeJson.decodeFromJsonElement(Episode.serializer(), it)
    } ?: Episode()

    private fun apiMedia(obj: JsonObject?): Media = obj?.let {
        BridgeJson.decodeFromJsonElement(Media.serializer(), it)
    } ?: Media()

    private fun apiParams(obj: JsonObject?): SourceParams? = obj?.let {
        runCatching { BridgeJson.decodeFromJsonElement(SourceParams.serializer(), it) }.getOrNull()
    }

    private fun apiFilters(array: JsonArray?): List<Filter> = array?.mapNotNull { element ->
        runCatching { BridgeJson.decodeFromJsonElement(Filter.serializer(), element) }.getOrNull()
    } ?: emptyList()

    private inline fun <reified T> encode(value: T): JsonElement =
        BridgeJson.encodeToJsonElement(kotlinx.serialization.serializer(), value)

    suspend fun execute(request: BridgeRequest): BridgeResponse = try {
        BridgeResponse.ok(dispatch(request))
    } catch (t: Throwable) {
        logger.error(TAG, "Request ${request.method} failed", t)
        BridgeResponse.fail(
            BridgeError(
                code = (t as? BackendException)?.code ?: BridgeError.CODE_EXTENSION_FAILURE,
                message = t.message ?: t::class.java.simpleName,
                backendId = request.backendId,
                cause = "${t::class.java.simpleName}",
            )
        )
    }

    private suspend fun dispatch(request: BridgeRequest): JsonElement {
        val args = request.args
        return when (request.method) {
            Methods.HOST_INFO -> encode(hostInfo())
            Methods.STATUS -> encode(status())
            Methods.BACKENDS -> encode(registry.all().map { it.status() })
            Methods.BACKEND_STATUS -> encode(requireBackend(request.backendId).status())
            Methods.INITIALIZE -> {
                initialize(appContext)
                JsonPrimitive(true)
            }
            Methods.SHUTDOWN -> {
                shutdown()
                JsonPrimitive(true)
            }
            Methods.SOURCES -> encode(requireBackend(request.backendId).fetchAvailable(apiType(args)))
            Methods.INSTALLED_SOURCES -> encode(requireBackend(request.backendId).fetchInstalled(apiType(args)))
            Methods.REPOSITORIES -> encode(requireBackend(request.backendId).repositories(apiType(args)))
            Methods.ADD_REPOSITORY -> {
                requireBackend(request.backendId).addRepository(
                    args.str("url") ?: throw BackendException("INVALID_ARG", "url is required"),
                    apiType(args),
                )
                JsonPrimitive(true)
            }
            Methods.REMOVE_REPOSITORY -> {
                requireBackend(request.backendId).removeRepository(
                    args.str("url") ?: throw BackendException("INVALID_ARG", "url is required"),
                    apiType(args),
                )
                JsonPrimitive(true)
            }
            Methods.INSTALL -> {
                requireBackend(request.backendId).install(apiSource(args.str("source")))
                JsonPrimitive(true)
            }
            Methods.UNINSTALL -> {
                requireBackend(request.backendId).uninstall(apiSource(args.str("source")))
                JsonPrimitive(true)
            }
            Methods.UPDATE -> {
                requireBackend(request.backendId).update(apiSource(args.str("source")))
                JsonPrimitive(true)
            }
            Methods.REFRESH -> {
                val backend = requireBackend(request.backendId)
                backend.detach()
                backend.attach(backendContext)
                encode(backend.status())
            }
            Methods.POPULAR, Methods.LATEST, Methods.SEARCH, Methods.DETAIL, Methods.PAGES,
            Methods.VIDEOS, Methods.FILTERS, Methods.PREFERENCES, Methods.SET_PREFERENCE,
            Methods.NOVEL_CONTENT, Methods.CANCEL, Methods.STOP_HTTP_SERVER -> sourceCall(request)
            Methods.LEGACY_INVOKE -> legacyInvoke(args)
            "torrserver.status" -> encode(torrServer().probe())
            "torrserver.list" -> encode(torrServer().listTorrents())
            "torrserver.add" -> encode(
                torrServer().addTorrent(
                    link = args.str("link") ?: throw BackendException("INVALID_ARG", "link is required"),
                    title = args.str("title"),
                    poster = args.str("poster"),
                )
            )
            "torrserver.get" -> encode(torrServer().getTorrent(args.str("hash") ?: ""))
            "torrserver.drop" -> {
                torrServer().dropTorrent(args.str("hash") ?: "")
                JsonPrimitive(true)
            }
            "torrserver.streamUrl" -> JsonPrimitive(torrServer().streamUrl(args.str("hash") ?: "", args.str("index") ?: "0"))
            "torrserver.playUrl" -> JsonPrimitive(torrServer().playUrl(args.str("hash") ?: "", args.str("index") ?: "0"))
            "torrserver.config" -> {
                args.str("serverUrl")?.takeIf { it.isNotBlank() }?.let { preferences.putString("torrserver_url", it) }
                args.str("username")?.let { preferences.putString("torrserver_user", it) }
                args.str("password")?.let { preferences.putString("torrserver_password", it) }
                torrServer()?.let { addon ->
                    addon.updateConfig(
                        TorrServerAddon.Config(
                            serverUrl = preferences.getString("torrserver_url") ?: "http://127.0.0.1:8090",
                            username = preferences.getString("torrserver_user"),
                            password = preferences.getString("torrserver_password"),
                        )
                    )
                }
                JsonPrimitive(true)
            }
            else -> throw BackendException(BridgeError.CODE_UNSUPPORTED, "Unknown method: ${request.method}")
        }
    }

    /** Compatibility escape hatch: route a raw legacy method name to a backend. */
    private suspend fun legacyInvoke(args: JsonObject): JsonElement {
        val method = args.str("method") ?: throw BackendException("INVALID_ARG", "method is required")
        val backendId = args.str("backendId")
        val sourceId = args.str("sourceId")
        val inner = args["args"] as? JsonObject ?: JsonObject(emptyMap())
        return dispatch(BridgeRequest(method, backendId, sourceId, inner))
    }

    private fun torrServer(): TorrServerAddon =
        torrServer ?: throw BackendException("TORRSERVER_UNAVAILABLE", "TorrServer addon is not initialised")

    private suspend fun sourceCall(request: BridgeRequest): JsonElement {
        val backend = requireBackend(request.backendId)
        val args = request.args
        if (request.method == Methods.CANCEL) {
            val token = args.str("token") ?: ""
            val handled = RuntimeBridge.cancelRequest(token)
            request.sourceId?.let { sid -> backend.source(sid)?.cancelRequest(token) }
            return JsonPrimitive(handled)
        }
        if (request.method == Methods.STOP_HTTP_SERVER) {
            request.sourceId?.let { sid -> backend.source(sid)?.stopHttpServer() }
            return JsonPrimitive(true)
        }
        val sourceId = request.sourceId ?: args.str("sourceId")
            ?: throw BackendException("INVALID_ARG", "sourceId is required")
        val source = backend.source(sourceId) ?: throw BackendException(
            BridgeError.CODE_UNKNOWN_SOURCE,
            "Source '$sourceId' is not installed for backend '${backend.id}'",
            backend.id,
        )
        val params = apiParams(args["params"] as? JsonObject)
        val page = (args["page"] as? JsonPrimitive)?.intOrNull ?: 1
        return when (request.method) {
            Methods.POPULAR -> encode(source.getPopular(page, params))
            Methods.LATEST -> encode(source.getLatestUpdates(page, params))
            Methods.SEARCH -> encode(
                source.search(
                    query = args.str("query") ?: "",
                    page = page,
                    filters = apiFilters(args["filters"] as? JsonArray),
                    params = params,
                )
            )
            Methods.DETAIL -> encode(source.getDetail(apiMedia(args["media"] as? JsonObject), params))
            Methods.PAGES -> encode(source.getPageList(apiEpisode(args["episode"] as? JsonObject), params))
            Methods.VIDEOS -> encode(source.getVideoList(apiEpisode(args["episode"] as? JsonObject), params))
            Methods.NOVEL_CONTENT -> JsonPrimitive(
                source.getNovelContent(
                    chapterTitle = args.str("chapterTitle") ?: args.str("title") ?: "",
                    chapterId = args.str("chapterId") ?: args.str("id") ?: "",
                    params = params,
                )
            )
            Methods.FILTERS -> encode(source.getFilterList())
            Methods.PREFERENCES -> encode(source.getPreference())
            Methods.SET_PREFERENCE -> {
                val pref = args["pref"]?.let {
                    runCatching { BridgeJson.decodeFromJsonElement(SourcePreference.serializer(), it) }.getOrNull()
                } ?: throw BackendException("INVALID_ARG", "pref is required")
                JsonPrimitive(source.setPreference(pref, args["value"] ?: JsonNull))
            }
            else -> throw BackendException(BridgeError.CODE_UNSUPPORTED, "Unsupported source method: ${request.method}")
        }
    }

    /** Streams videos through the AIDL callback (Dart `getVideoListStream`). */
    fun streamVideos(
        request: BridgeRequest,
        onEvent: (StreamEvent) -> Unit,
        onComplete: () -> Unit,
        onError: (BridgeError) -> Unit,
    ) {
        scope.launch {
            try {
                val backend = requireBackend(request.backendId)
                val sourceId = request.sourceId ?: throw BackendException("INVALID_ARG", "sourceId is required")
                val source = backend.source(sourceId) ?: throw BackendException(
                    BridgeError.CODE_UNKNOWN_SOURCE, "Source '$sourceId' is not installed", backend.id
                )
                val episode = apiEpisode(request.args["episode"] as? JsonObject)
                val params = apiParams(request.args["params"] as? JsonObject)
                source.getVideoListStream(episode, params).collect { video ->
                    onEvent(StreamEvent(video = video))
                }
                onComplete()
            } catch (t: Throwable) {
                onError(
                    BridgeError(
                        (t as? BackendException)?.code ?: BridgeError.CODE_EXTENSION_FAILURE,
                        t.message ?: t::class.java.simpleName,
                        request.backendId,
                    )
                )
            }
        }
    }

    fun cancel(token: String) {
        RuntimeBridge.cancelRequest(token)
    }

    fun shutdown() {
        if (!initialized) return
        logger.info(TAG, "Shutting down runtime host")
        runCatching { RuntimeBridge.shutdown() }
        runCatching { torrServer?.close() }
        registry.all().forEach { backend -> runCatching { runBlocking { backend.detach() } } }
        initialized = false
    }
}
