/*
 * Sora backend (anime/manga/novel JavaScript extensions).
 *
 * Port of AnymeX lib/Services/LnReader/ (LnReaderExtensions.dart,
 * LnReaderSourceMethods.dart, Manifest.dart, service.dart) onto native Kotlin.
 *
 * Extension format: a JavaScript file that assigns `source = { id, name, site,
 * version, ... }` and implements getPopular/getLatestUpdates/search/getManga/
 * getChapters/getChapterContent. Scripts run in the embedded engine
 * (javascript-runtime) with the cheerio-compatible DOM shim the Dart side
 * provided through js_cheerio.dart / js_htmlparser.dart.
 */
package io.chibi.runtime.backends.sora

import io.chibi.bridge.backend.BackendContext
import io.chibi.bridge.backend.BackendException
import io.chibi.bridge.backend.ExtensionBackend
import io.chibi.bridge.backend.ExtensionSource
import io.chibi.bridge.js.JsEngine
import io.chibi.bridge.js.JsHostServices
import io.chibi.bridge.models.BackendCapabilities
import io.chibi.bridge.models.BackendStatus
import io.chibi.bridge.models.BridgeJson
import io.chibi.bridge.models.Episode
import io.chibi.bridge.models.ExecutionMechanism
import io.chibi.bridge.models.Filter
import io.chibi.bridge.models.ItemType
import io.chibi.bridge.models.Media
import io.chibi.bridge.models.PageUrl
import io.chibi.bridge.models.Pages
import io.chibi.bridge.models.Repo
import io.chibi.bridge.models.Source
import io.chibi.bridge.models.SourceParams
import io.chibi.bridge.models.Video
import io.chibi.bridge.models.bool
import io.chibi.bridge.models.fromLegacy
import io.chibi.bridge.models.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.File

class SoraBackend : ExtensionBackend {
    override val id: String = "sora"
    override val displayName: String = "Sora"

    private lateinit var ctx: BackendContext
    private var initialized = false

    override fun capabilities() = BackendCapabilities(
        anime = true, manga = true, novel = true,
        video = true, pages = true, filters = true, preferences = false,
        execution = ExecutionMechanism.JAVASCRIPT,
    )

    override suspend fun attach(context: BackendContext) {
        ctx = context
        scriptsDir().mkdirs()
        initialized = true
    }

    private fun scriptsDir(): File = File(ctx.filesDir, "sora_extensions")

    private class Manifest(
        val id: String,
        val name: String,
        val site: String,
        val lang: String,
        val version: String,
        val nsfw: Boolean,
        val path: File,
    )

    private fun readManifests(): List<Manifest> = scriptsDir().listFiles()
        ?.filter { it.extension == "js" || it.extension == "json" }
        ?.mapNotNull { file -> manifestFor(file) }
        ?: emptyList()

    private fun manifestFor(file: File): Manifest? {
        if (file.extension == "json") {
            val obj = runCatching { BridgeJson.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
                ?: return null
            val script = File(file.parentFile, (obj.str("file") ?: "${obj.str("id")}.js"))
            return Manifest(
                id = obj.str("id") ?: file.nameWithoutExtension,
                name = obj.str("name") ?: file.nameWithoutExtension,
                site = obj.str("site") ?: "",
                lang = obj.str("lang") ?: "",
                version = obj.str("version") ?: "1.0.0",
                nsfw = obj.bool("nsfw") ?: false,
                path = script,
            )
        }
        // Metadata is embedded in the script header: `source = { id: "x", ... }`
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        fun field(name: String): String? =
            Regex("$name\\s*:\\s*[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1)
        return Manifest(
            id = field("id") ?: file.nameWithoutExtension,
            name = field("name") ?: file.nameWithoutExtension,
            site = field("site") ?: field("sourceSite") ?: "",
            lang = field("lang") ?: "",
            version = field("version") ?: "1.0.0",
            nsfw = Regex("isNsfw\\s*:\\s*true").containsMatchIn(text),
            path = file,
        )
    }

    private fun Manifest.toSource() = Source(
        id = id,
        name = name,
        baseUrl = site,
        lang = lang,
        isNsfw = nsfw,
        version = version,
        itemType = ItemType.ANIME,
        managerId = id.let { "sora" },
        supportsPopular = true,
        supportsLatest = true,
    )

    override suspend fun fetchInstalled(type: ItemType): List<Source> =
        if (type == ItemType.ANIME) readManifests().map { it.toSource() } else emptyList()

    override suspend fun fetchAvailable(type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        if (type != ItemType.ANIME) return@withContext emptyList()
        val installed = readManifests().map { it.id }.toSet()
        val repos = repositories(type).map { it.url }.ifEmpty { DEFAULT_REPOS }
        repos.flatMap { repo ->
            runCatching {
                val body = ctx.httpClient.newCall(Request.Builder().url(repo).build()).execute().use { it.body.string() }
                org_mangayomi_catalog(body, "sora").filter { it.id !in installed }
            }.getOrElse { emptyList() }
        }
    }

    override suspend fun addRepository(url: String, type: ItemType) {
        val existing = repositories(type).map { it.url }
        if (url !in existing) ctx.preferences.putString("repos_$id", (existing + url).joinToString("\n"))
    }

    override suspend fun removeRepository(url: String, type: ItemType) {
        val existing = repositories(type).map { it.url }
        ctx.preferences.putString("repos_$id", existing.filterNot { it == url }.joinToString("\n"))
    }

    override suspend fun repositories(type: ItemType): List<Repo> =
        (ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
            .map { Repo(url = it, managerId = id) }

    override suspend fun install(source: Source) = withContext(Dispatchers.IO) {
        val body = ctx.httpClient.newCall(Request.Builder().url(source.baseUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw BackendException("HTTP_ERROR", "Download failed: ${response.code}", id)
            response.body.string()
        }
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(scriptsDir(), "$safeName.js").writeText(body)
        val meta = JsonObject(
            mapOf(
                "id" to kotlinx.serialization.json.JsonPrimitive(source.id),
                "name" to kotlinx.serialization.json.JsonPrimitive(source.name),
                "site" to kotlinx.serialization.json.JsonPrimitive(source.baseUrl),
                "lang" to kotlinx.serialization.json.JsonPrimitive(source.lang),
                "version" to kotlinx.serialization.json.JsonPrimitive(source.version),
                "file" to kotlinx.serialization.json.JsonPrimitive("$safeName.js"),
            )
        )
        File(scriptsDir(), "$safeName.json").writeText(BridgeJson.encodeToString(JsonObject.serializer(), meta))
    }

    override suspend fun uninstall(source: Source) = withContext(Dispatchers.IO) {
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(scriptsDir(), "$safeName.js").delete()
        File(scriptsDir(), "$safeName.json").delete()
    }

    override suspend fun update(source: Source) = install(source)

    override suspend fun status(): BackendStatus {
        val count = runCatching { readManifests().size }.getOrDefault(0)
        return BackendStatus(
            id = id, displayName = displayName, available = true, initialized = initialized,
            installedCount = count, execution = ExecutionMechanism.JAVASCRIPT,
            capabilities = capabilities(),
            details = mapOf("pluginsDir" to scriptsDir().absolutePath),
        )
    }

    override fun source(sourceId: String): ExtensionSource? {
        val manifest = readManifests().firstOrNull { it.id == sourceId || it.name == sourceId } ?: return null
        return LnReaderSource(manifest.toSource(), manifest)
    }

    inner class LnReaderSource(override val source: Source, private val manifest: Manifest) : ExtensionSource {

        private fun engine(): JsEngine {
            val engine = JsEngine(
                JsHostServices(ctx.httpClient, ctx.preferences, ctx.logger, preferencesPrefix = "sora:${manifest.id}")
            )
            engine.load(manifest.path.readText())
            return engine
        }

        private inline fun <T> withEngine(block: (JsEngine) -> T): T {
            val engine = engine()
            return try {
                block(engine)
            } finally {
                engine.close()
            }
        }

        private fun pagesFrom(json: String): Pages {
            val element = runCatching { BridgeJson.parseToJsonElement(json) }.getOrNull() ?: return Pages.empty()
            val array = when (element) {
                is JsonArray -> element
                is JsonObject -> element["results"] as? JsonArray ?: element["list"] as? JsonArray
                else -> null
            } ?: return Pages.empty()
            return Pages(
                list = array.mapNotNull { row ->
                    (row as? JsonObject)?.let { Media.fromLegacy(it) }
                }
            )
        }

        override suspend fun getPopular(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            withEngine { it.evaluateJson("source.getPopular($page)") }.let { pagesFrom(it) }
        }

        override suspend fun getLatestUpdates(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            withEngine { it.evaluateJson("source.getLatestUpdates($page)") }.let { pagesFrom(it) }
        }

        override suspend fun search(query: String, page: Int, filters: List<Filter>, params: SourceParams?): Pages =
            withContext(Dispatchers.IO) {
                val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
                withEngine { it.evaluateJson("source.search(\"$escaped\", $page)") }.let { pagesFrom(it) }
            }

        override suspend fun getDetail(media: Media, params: SourceParams?): Media = withContext(Dispatchers.IO) {
            val url = (media.url ?: "").replace("\"", "\\\"")
            val json = withEngine { it.evaluateJson("(typeof source.getDetail === 'function' ? source.getDetail : source.getManga)(\"$url\")") }
            val obj = runCatching { BridgeJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@withContext media
            Media.fromLegacy(obj)
        }

        override suspend fun getPageList(episode: Episode, params: SourceParams?): List<PageUrl> =
            withContext(Dispatchers.IO) {
                val url = (episode.url ?: "").replace("\"", "\\\"")
                val json = withEngine {
                    it.evaluateJson("(typeof source.getPageList === 'function' ? source.getPageList(\"$url\") : [])")
                }
                val array = runCatching { BridgeJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
                    ?: return@withContext emptyList()
                array.mapNotNull { row ->
                    (row as? JsonObject)?.str("url")?.let { PageUrl(it) }
                }
            }

        override suspend fun getVideoList(episode: Episode, params: SourceParams?): List<Video> =
            withContext(Dispatchers.IO) {
                val url = (episode.url ?: "").replace("\"", "\\\"")
                val json = withEngine {
                    it.evaluateJson("(typeof source.getVideoList === 'function' ? source.getVideoList(\"$url\") : [])")
                }
                val array = runCatching { BridgeJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
                    ?: return@withContext emptyList()
                array.mapNotNull { row -> (row as? JsonObject)?.let { Video.fromLegacy(it) } }
            }

        override suspend fun getNovelContent(chapterTitle: String, chapterId: String, params: SourceParams?): String =
            withContext(Dispatchers.IO) {
                val id = chapterId.replace("\"", "\\\"")
                withEngine { it.evaluate("(typeof source.getChapterContent === 'function' ? source.getChapterContent : source.getNovelContent)(\"$id\")") }.trim('"')
            }
    }

    companion object {
        val DEFAULT_REPOS = listOf(
            "https://raw.githubusercontent.com/Anime-Moe/sora-extensions/master/extensions.json",
        )
    }
}


/** Lenient catalogue parser for this backend's extension repository. */
private fun org_mangayomi_catalog(body: String, managerId: String): List<Source> {
    val element = runCatching { BridgeJson.parseToJsonElement(body.trim()) }.getOrNull() ?: return emptyList()
    val objects: List<JsonObject> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val list = (element["data"] ?: element["extensions"] ?: element["plugins"] ?: element["sources"]) as? JsonArray
            list?.mapNotNull { it as? JsonObject } ?: listOf(element)
        }
        else -> emptyList()
    }
    return objects.mapNotNull { obj ->
        val id = obj.str("id") ?: obj.str("name") ?: return@mapNotNull null
        val url = obj.str("url") ?: obj.str("scriptUrl") ?: obj.str("file") ?: return@mapNotNull null
        Source(
            id = id,
            name = obj.str("name") ?: id,
            baseUrl = url,
            lang = obj.str("lang") ?: obj.str("language") ?: "",
            iconUrl = obj.str("icon") ?: obj.str("iconUrl") ?: "",
            isNsfw = obj.bool("nsfw") ?: obj.bool("isNsfw") ?: false,
            version = obj.str("version") ?: "1.0.0",
            itemType = ItemType.ANIME,
            managerId = managerId,
            supportsLatest = true,
            supportsPopular = true,
        )
    }
}
