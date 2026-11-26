/*
 * TorrServer addon (backend #10).
 *
 * Port of the Dart addon in AnymeX lib/Engines/TorrentEngine/
 * (TorrServerAddon.dart, RestClient.dart, Models/TorrentInfo.dart) onto native
 * Kotlin. The Dart implementation talked HTTP to a TorrServer instance
 * (`/torrents`, `/stream/...`) with an optional Basic auth pair; that contract is
 * preserved verbatim here.
 *
 * Unlike the other nine backends, TorrServer is not an extension container: it
 * is a torrent streaming service. The addon therefore manages a *server*
 * (local or remote) rather than repositories, and exposes the same operations
 * the Dart addon exposed: add/list/get/drop torrents, resolve stream URLs and
 * report server status.
 *
 * Deployment note (documented in docs/BACKENDS.md): the TorrServer Go binary is
 * a separate GPL-3.0 executable and is not embedded in this repository. The
 * addon connects to a TorrServer endpoint supplied by the host app (default
 * http://127.0.0.1:8090).
 */
package io.chibi.bridge.torrserver

import io.chibi.bridge.backend.BridgeLogger
import io.chibi.bridge.backend.LogLevel
import io.chibi.bridge.backend.PreferenceStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class TorrServerFile(
    @SerialName("id") val id: Int = 0,
    @SerialName("path") val path: String = "",
    @SerialName("length") val length: Long = 0,
    @SerialName("selected") val selected: Boolean = false,
)

@Serializable
data class TorrentInfo(
    @SerialName("title") val title: String = "",
    @SerialName("hash") val hash: String = "",
    @SerialName("stat") val stat: Int = 0,
    @SerialName("stat_string") val statString: String = "",
    @SerialName("torrent_size") val torrentSize: Long = 0,
    @SerialName("loaded_size") val loadedSize: Long = 0,
    @SerialName("download_speed") val downloadSpeed: Long = 0,
    @SerialName("upload_speed") val uploadSpeed: Long = 0,
    @SerialName("active_peers") val activePeers: Int = 0,
    @SerialName("total_peers") val totalPeers: Int = 0,
    @SerialName("file_stats") val fileStats: List<TorrServerFile> = emptyList(),
    @SerialName("poster") val poster: String = "",
    @SerialName("data") val data: String = "",
)

/** Raised when the configured TorrServer endpoint is unreachable or rejects a call. */
class TorrServerException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TorrServerAddon(
    private var client: OkHttpClient,
    private val preferences: PreferenceStore,
    private val logger: BridgeLogger,
    initialConfig: Config,
) : AutoCloseable {

    @Serializable
    data class Config(
        val serverUrl: String = "http://127.0.0.1:8090",
        val username: String? = null,
        val password: String? = null,
        val timeoutSeconds: Long = 30,
    )

    @Serializable
    data class ServerStatus(
        val reachable: Boolean,
        val version: String? = null,
        val serverUrl: String,
        val error: String? = null,
    )

    private var config: Config = initialConfig
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun updateConfig(newConfig: Config) {
        config = newConfig
        client = client.newBuilder()
            .callTimeout(newConfig.timeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    private fun baseUrl(): String = config.serverUrl.trimEnd('/')

    private fun endpoint(path: String): String = "${baseUrl()}$path"

    private fun Request.Builder.authenticate(): Request.Builder = apply {
        val user = config.username
        val pass = config.password
        if (!user.isNullOrBlank() && pass != null) {
            header("Authorization", Credentials.basic(user, pass))
        }
    }

    private fun execute(request: Request): String {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw TorrServerException("TorrServer HTTP ${response.code}: ${body.take(200)}")
                }
                return body
            }
        } catch (t: TorrServerException) {
            throw t
        } catch (t: Throwable) {
            throw TorrServerException("TorrServer unreachable at ${baseUrl()}: ${t.message}", t)
        }
    }

    private fun postTorrents(payload: JsonObject): String {
        val request = Request.Builder()
            .url(endpoint("/torrents"))
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody("application/json".toMediaType()))
            .authenticate()
            .build()
        return execute(request)
    }

    /** `/echo` reports the server build; used as a reachability probe. */
    fun probe(): ServerStatus = try {
        val request = Request.Builder().url(endpoint("/echo")).get().authenticate().build()
        val body = execute(request).trim()
        ServerStatus(reachable = true, version = body.take(120), serverUrl = baseUrl())
    } catch (t: Throwable) {
        ServerStatus(reachable = false, serverUrl = baseUrl(), error = t.message)
    }

    fun listTorrents(): List<TorrentInfo> {
        val body = postTorrents(buildJsonObject { put("action", "list") })
        return parseTorrentList(body)
    }

    fun addTorrent(link: String, title: String? = null, poster: String? = null): TorrentInfo {
        val payload = buildJsonObject {
            put("action", "add")
            put("link", link)
            if (!title.isNullOrBlank()) put("title", title)
            if (!poster.isNullOrBlank()) put("poster", poster)
            put("save_to_db", true)
        }
        val body = postTorrents(payload)
        val parsed = runCatching { json.decodeFromString(TorrentInfo.serializer(), body) }.getOrNull()
        if (parsed != null && parsed.hash.isNotBlank()) return parsed
        // Older servers answer `add` with nothing useful; re-read the list.
        return listTorrents().firstOrNull { link.contains(it.hash, ignoreCase = true) }
            ?: TorrentInfo(title = title ?: link, data = link)
    }

    fun getTorrent(hash: String): TorrentInfo? {
        if (hash.isBlank()) return null
        val body = postTorrents(buildJsonObject {
            put("action", "get")
            put("hash", hash)
        })
        return runCatching { json.decodeFromString(TorrentInfo.serializer(), body) }.getOrNull()
    }

    /** Selects the file to stream (TorrServer `set` with file_stats). */
    fun selectFile(hash: String, fileId: Int) {
        postTorrents(buildJsonObject {
            put("action", "set")
            put("hash", hash)
            put("file_id", fileId)
        })
    }

    fun dropTorrent(hash: String) {
        if (hash.isBlank()) return
        postTorrents(buildJsonObject {
            put("action", "drop")
            put("hash", hash)
        })
        logger.log(LogLevel.INFO, "TorrServer", "Dropped torrent $hash")
    }

    /** HTTP streaming endpoint (progressive playback). */
    fun streamUrl(hash: String, index: String): String = "${baseUrl()}/stream/$hash/$index"

    /** Player-friendly endpoint used by ExoPlayer/AVPlayer. */
    fun playUrl(hash: String, index: String): String = "${baseUrl()}/play/$hash/$index"

    fun rememberServer(url: String) = preferences.putString("torrserver_url", url)

    private fun parseTorrentList(body: String): List<TorrentInfo> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull {
                runCatching { json.decodeFromJsonElement(TorrentInfo.serializer(), it) }.getOrNull()
            }
            is JsonObject -> {
                val list = element["torrents"] as? JsonArray ?: return emptyList()
                list.mapNotNull {
                    runCatching { json.decodeFromJsonElement(TorrentInfo.serializer(), it) }.getOrNull()
                }
            }
            else -> emptyList()
        }
    }

    override fun close() {
        // Stateless HTTP client: nothing to release beyond dropping our cache.
        logger.log(LogLevel.DEBUG, "TorrServer", "Addon closed")
    }
}
