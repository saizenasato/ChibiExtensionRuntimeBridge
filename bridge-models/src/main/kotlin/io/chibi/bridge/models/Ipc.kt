/*
 * Binder wire format shared by the runtime host and the Kotlin SDK.
 *
 * The original bridge used Flutter MethodChannels ("anymeXBridge",
 * "aniyomiExtensionBridge", ...). Those channels carried maps of method names
 * and arguments; this protocol keeps that shape (method name + JSON args) but
 * transports it over a stable AIDL binder instead of Flutter.
 */
package io.chibi.bridge.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object BridgeProtocol {
    /** Incremented whenever the wire contract changes in a breaking way. */
    const val VERSION: Int = 1

    /**
     * Runtime host package shipped with this project. Deliberately distinct from
     * AnymeX's own `com.anymex.runtimehost` bridge so the two can coexist.
     */
    const val HOST_PACKAGE: String = "io.chibi.runtimehost"

    const val ACTION_HOST_SERVICE: String = "io.chibi.bridge.action.RUNTIME_HOST"
    const val SERVICE_DESCRIPTOR: String = "io.chibi.bridge.ipc.IChibiRuntime"
}

/** Canonical method names. These mirror the original MethodChannel handlers. */
object Methods {
    const val HOST_INFO = "hostInfo"
    const val STATUS = "status"
    const val INITIALIZE = "initialize"
    const val SHUTDOWN = "shutdown"
    const val BACKENDS = "backends"
    const val BACKEND_STATUS = "backendStatus"
    const val SOURCES = "sources"
    const val INSTALLED_SOURCES = "installedSources"
    const val REPOSITORIES = "repositories"
    const val ADD_REPOSITORY = "addRepository"
    const val REMOVE_REPOSITORY = "removeRepository"
    const val REFRESH = "refresh"
    const val INSTALL = "install"
    const val UNINSTALL = "uninstall"
    const val UPDATE = "update"
    const val POPULAR = "getPopular"
    const val LATEST = "getLatestUpdates"
    const val SEARCH = "search"
    const val DETAIL = "getDetail"
    const val PAGES = "getPageList"
    const val VIDEOS = "getVideoList"
    const val VIDEO_STREAM = "getVideoListStream"
    const val NOVEL_CONTENT = "getNovelContent"
    const val FILTERS = "getFilterList"
    const val PREFERENCES = "getPreference"
    const val SET_PREFERENCE = "setPreference"
    const val CANCEL = "cancelRequest"
    const val STOP_HTTP_SERVER = "stopHttpServer"
    const val LEGACY_INVOKE = "invoke"
}

@Serializable
data class BridgeRequest(
    val method: String,
    val backendId: String? = null,
    val sourceId: String? = null,
    val args: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BridgeResponse(
    val result: JsonElement? = null,
    val error: BridgeError? = null,
) {
    val isSuccess: Boolean get() = error == null

    companion object {
        fun ok(value: JsonElement?): BridgeResponse = BridgeResponse(result = value)
        fun fail(error: BridgeError): BridgeResponse = BridgeResponse(error = error)
        fun fail(code: String, message: String, backendId: String? = null) =
            BridgeResponse(error = BridgeError(code, message, backendId))
    }
}

/** Streaming envelope used for `getVideoListStream` parity. */
@Serializable
data class StreamEvent(
    val video: Video? = null,
    val done: Boolean = false,
    val error: BridgeError? = null,
)

/** Typed accessors over the untyped binder arguments. */
object Args {
    fun BridgeRequest.json(): JsonObject = args
    fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (this[key] as? JsonPrimitive)?.content

    fun JsonObject.int(key: String, default: Int = 0): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

    fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

    fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

    fun JsonObject.token(): String? = string("token") ?: string("cancelToken")

    fun JsonObject.params(): SourceParams? = objectOrNull("params")?.let {
        runCatching { BridgeJson.decodeFromJsonElement(SourceParams.serializer(), it) }.getOrNull()
    }

    fun JsonObject.primitive(): JsonPrimitive? = this as? JsonPrimitive
}
