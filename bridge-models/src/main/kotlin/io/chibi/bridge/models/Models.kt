/*
 * ChibiExtensionRuntimeBridge — unified bridge models.
 *
 * Ported from the Dart model layer of the AnymeX Extension Runtime Bridge
 * (lib/Models/, lib/Extensions/Extensions.dart) by RyanYuuki.
 * Upstream is licensed under the Unabandon Public License (UPL / GPL-3.0).
 * See NOTICE and docs/LICENSE_COMPLIANCE.md for attribution details.
 */
package io.chibi.bridge.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Dart `ItemType` (lib/Models/Source.dart). */
@Serializable
enum class ItemType {
    @SerialName("manga") MANGA,
    @SerialName("anime") ANIME,
    @SerialName("novel") NOVEL;

    val label: String
        get() = when (this) {
            MANGA -> "Manga"
            ANIME -> "Anime"
            NOVEL -> "Novel"
        }

    companion object {
        /** Dart serialised `ItemType.index`. */
        fun fromIndex(index: Int?): ItemType = when (index) {
            1 -> ANIME
            2 -> NOVEL
            else -> MANGA
        }
    }
}

/** Dart `DEpisode`. */
@Serializable
data class Episode(
    val url: String? = null,
    val name: String? = null,
    val dateUpload: String = "",
    val scanlator: String? = null,
    val thumbnail: String? = null,
    val description: String? = null,
    val memo: String? = null,
    val filler: Boolean? = null,
    val episodeNumber: String = "",
    val sortMap: Map<String, String> = emptyMap(),
)

/** Dart `PageUrl`. */
@Serializable
data class PageUrl(
    val url: String,
    val headers: Map<String, String>? = null,
)

/** Dart `Track`. */
@Serializable
data class Track(
    val file: String? = null,
    val label: String? = null,
)

/** Dart `Video`. */
@Serializable
data class Video(
    val title: String? = null,
    val url: String,
    val quality: String = "Default",
    val headers: Map<String, String>? = null,
    val subtitles: List<Track> = emptyList(),
    val audios: List<Track> = emptyList(),
    val extraData: JsonObject? = null,
)

/** Dart `DMedia`. Covers anime, manga and novel entries from every backend. */
@Serializable
data class Media(
    val title: String? = null,
    val url: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genre: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(),
) {
    companion object {
        fun withUrl(url: String) = Media(url = url)
    }
}

/** Dart `Pages`. */
@Serializable
data class Pages(
    val list: List<Media> = emptyList(),
    val hasNextPage: Boolean = false,
) {
    companion object {
        fun empty() = Pages()
    }
}

/** Dart `SourceParams` — cancellation token plus backend specific extras. */
@Serializable
data class SourceParams(
    val cancelToken: String? = null,
    val customParams: Map<String, String> = emptyMap(),
)

/** Dart `Source`. */
@Serializable
data class Source(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val lang: String = "",
    val isNsfw: Boolean = false,
    val iconUrl: String = "",
    val version: String = "0.0.1",
    val versionLast: String = "0.0.1",
    val itemType: ItemType = ItemType.MANGA,
    val repo: String? = null,
    val managerId: String? = null,
    val hasUpdate: Boolean = false,
    val isPrivate: Boolean? = null,
    val supportsLatest: Boolean = false,
    val supportsPopular: Boolean = false,
) {
    val uniqueId: String get() = id
}

/** Dart `Repo`. */
@Serializable
data class Repo(
    val url: String,
    val name: String? = null,
    val iconUrl: String? = null,
    val extensions: String? = null,
    val managerId: String? = null,
)

/** Dart `SourcePreference` hierarchy (lib/Models/SourcePreference.dart). */
@Serializable
sealed class SourcePreference {
    abstract val key: String?
    abstract val type: String
    abstract val title: String?
    abstract val summary: String?

    @Serializable
    @SerialName("checkbox")
    data class CheckBox(
        override val key: String? = null,
        override val title: String? = null,
        override val summary: String? = null,
        val value: Boolean = false,
    ) : SourcePreference() {
        override val type: String get() = "checkbox"
    }

    @Serializable
    @SerialName("switch")
    data class Switch(
        override val key: String? = null,
        override val title: String? = null,
        override val summary: String? = null,
        val value: Boolean = false,
    ) : SourcePreference() {
        override val type: String get() = "switch"
    }

    @Serializable
    @SerialName("list")
    data class ListPref(
        override val key: String? = null,
        override val title: String? = null,
        override val summary: String? = null,
        val entries: List<String> = emptyList(),
        val entryValues: List<String> = emptyList(),
        val value: String? = null,
        val valueIndex: Int? = null,
    ) : SourcePreference() {
        override val type: String get() = "list"
    }

    @Serializable
    @SerialName("multi_select")
    data class MultiSelect(
        override val key: String? = null,
        override val title: String? = null,
        override val summary: String? = null,
        val entries: List<String> = emptyList(),
        val entryValues: List<String> = emptyList(),
        val values: List<String> = emptyList(),
    ) : SourcePreference() {
        override val type: String get() = "multi_select"
    }

    @Serializable
    @SerialName("text")
    data class Text(
        override val key: String? = null,
        override val title: String? = null,
        override val summary: String? = null,
        val value: String? = null,
        val dialogTitle: String? = null,
        val dialogMessage: String? = null,
    ) : SourcePreference() {
        override val type: String get() = "text"
    }

    /** The value reported by Dart's `SourcePreference.value` getter. */
    fun currentValue(): JsonElement = when (this) {
        is CheckBox -> JsonPrimitive(value)
        is Switch -> JsonPrimitive(value)
        is ListPref -> value?.let(::JsonPrimitive) ?: JsonNull
        is MultiSelect -> kotlinx.serialization.json.JsonArray(values.map(::JsonPrimitive))
        is Text -> value?.let(::JsonPrimitive) ?: JsonNull
    }
}

/** Filter descriptor returned by backends that expose filters. */
@Serializable
data class Filter(
    val name: String,
    val type: String,
    val values: List<String> = emptyList(),
    val state: JsonElement = JsonNull,
)

/** Capabilities a backend reports for the runtime host. */
@Serializable
data class BackendCapabilities(
    val anime: Boolean = false,
    val manga: Boolean = false,
    val novel: Boolean = false,
    val video: Boolean = false,
    val pages: Boolean = false,
    val filters: Boolean = false,
    val preferences: Boolean = false,
    val streaming: Boolean = false,
    val repositories: Boolean = true,
    val execution: ExecutionMechanism = ExecutionMechanism.NATIVE_KOTLIN,
)

/** How a backend executes third party extension code. */
@Serializable
enum class ExecutionMechanism {
    /** Native Android DEX/JAR class loading (Aniyomi, CloudStream, Kotatsu). */
    DEX_CLASSLOADER,

    /** Embedded JavaScript engine (Mangayomi, Sora, LNReader). */
    JAVASCRIPT,

    /** Declarative rule interpreter (Legado). */
    RULE_ENGINE,

    /** Remote/native service integration (TorrServer). */
    NATIVE_SERVICE,

    /** Plain Kotlin HTTP + parsing implementation. */
    NATIVE_KOTLIN,
}

/** Per-backend runtime status. */
@Serializable
data class BackendStatus(
    val id: String,
    val displayName: String,
    val available: Boolean,
    val initialized: Boolean,
    val message: String? = null,
    val installedCount: Int = 0,
    val availableCount: Int = 0,
    val execution: ExecutionMechanism = ExecutionMechanism.NATIVE_KOTLIN,
    val capabilities: BackendCapabilities = BackendCapabilities(),
    val details: Map<String, String> = emptyMap(),
)

/** Runtime lifecycle state reported by [io.chibi.bridge.models.RuntimeStatus]. */
@Serializable
enum class RuntimeState {
    UNKNOWN,
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    LOADING,
    READY,
    ERROR,
}

/** Version/host metadata exchanged between SDK and runtime host. */
@Serializable
data class HostInfo(
    val hostPackage: String,
    val hostVersionName: String,
    val hostVersionCode: Int,
    val bridgeProtocol: Int,
    val runtimeVersion: String,
    val apiLevel: Int = 0,
    val abis: List<String> = emptyList(),
)

/** Full runtime status snapshot. */
@Serializable
data class RuntimeStatus(
    val state: RuntimeState = RuntimeState.UNKNOWN,
    val host: HostInfo? = null,
    val backends: List<BackendStatus> = emptyList(),
    val loadedExtensions: Int = 0,
    val error: BridgeError? = null,
)

/** Structured error type used across IPC so no stack traces cross the binder. */
@Serializable
data class BridgeError(
    val code: String,
    val message: String,
    val backendId: String? = null,
    val cause: String? = null,
    val recoverable: Boolean = true,
) {
    companion object {
        const val CODE_HOST_NOT_INSTALLED = "HOST_NOT_INSTALLED"
        const val CODE_HOST_NOT_READY = "HOST_NOT_READY"
        const val CODE_UNKNOWN_BACKEND = "UNKNOWN_BACKEND"
        const val CODE_UNKNOWN_SOURCE = "UNKNOWN_SOURCE"
        const val CODE_EXTENSION_FAILURE = "EXTENSION_FAILURE"
        const val CODE_HTTP = "HTTP_ERROR"
        const val CODE_UNSUPPORTED = "UNSUPPORTED"
        const val CODE_CANCELLED = "CANCELLED"
        const val CODE_TIMEOUT = "TIMEOUT"

        fun of(code: String, message: String, backendId: String? = null, cause: Throwable? = null) =
            BridgeError(code, message, backendId, cause?.let { "${it::class.java.simpleName}: ${it.message}" })
    }
}

@Serializable
sealed class BridgeResult<out T> {
    @Serializable
    data class Success<T>(val value: T) : BridgeResult<T>()

    @Serializable
    data class Failure(val error: BridgeError) : BridgeResult<Nothing>()
}

/** Comparison of dotted version strings, mirrors `Extension.compareVersions`. */
object VersionCompare {
    fun compare(v1: String, v2: String): Int {
        val a = v1.split('.')
        val b = v2.split('.')
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val n1 = a.getOrNull(i)?.toIntOrNull() ?: 0
            val n2 = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (n1 != n2) return n1.compareTo(n2)
        }
        return 0
    }
}
