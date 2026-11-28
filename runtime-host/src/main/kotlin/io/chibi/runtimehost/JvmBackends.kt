/*
 * ExtensionBackend adapters for the three DEX/JVM ecosystems.
 *
 * These wrap the ported AnymeX `RuntimeBridge` facade — the same entry point
 * that the original Flutter plugin reached through reflection. No behaviour is
 * re-implemented here: the adapters only translate between the unified Kotlin
 * models and the facade's Dart-shaped maps, so the battle-tested extension
 * loaders (Aniyomi DexClassLoader, CloudStream PluginManager, Kotatsu parser
 * classloaders) keep doing the work.
 */
package io.chibi.runtimehost

import android.content.Context
import io.chibi.runtimehost.RuntimeBridge
import io.chibi.bridge.backend.BackendContext
import io.chibi.bridge.backend.BackendException
import io.chibi.bridge.backend.ExtensionBackend
import io.chibi.bridge.backend.ExtensionSource
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
import io.chibi.bridge.models.SourcePreference
import io.chibi.bridge.models.Video
import io.chibi.bridge.models.bool
import io.chibi.bridge.models.fromCloudStream
import io.chibi.bridge.models.fromLegacy
import io.chibi.bridge.models.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.File

private fun Map<String, Any?>.asList(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()

private fun Map<String, Any?>.asPages(): Pages = Pages(
    list = asList("list").map { Media.fromLegacy(it.toJsonObject()) },
    hasNextPage = this["hasNextPage"] as? Boolean ?: false,
)

private fun paramsMap(params: SourceParams?): Map<String, Any?>? {
    if (params == null) return null
    val map = mutableMapOf<String, Any?>()
    params.cancelToken?.let { map["token"] = it }
    params.customParams.forEach { (k, v) -> map[k] = v }
    return map.ifEmpty { null }
}

private fun filtersToMap(filters: List<Filter>): List<Any?> = filters.map { it.toJsonMap() }

private fun Filter.toJsonMap(): Map<String, Any?> = mapOf(
    "name" to name,
    "type" to type,
    "values" to values,
    "state" to state.toString().let { if (it.startsWith("\"")) it.trim('"') else it },
)

private fun JsonObject.mapFilters(): List<Filter> = (this["list"] as? JsonArray)?.mapNotNull { el ->
    val obj = el as? JsonObject ?: return@mapNotNull null
    Filter(
        name = obj.str("name") ?: "",
        type = obj.str("type") ?: "Unknown",
        values = (obj["values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
        state = obj["state"] ?: JsonPrimitive(""),
    )
} ?: emptyList()

private fun JsonObject.toPages(): Pages = Pages(
    list = (this["list"] as? JsonArray)?.mapNotNull { m -> (m as? JsonObject)?.let { Media.fromLegacy(it) } } ?: emptyList(),
    hasNextPage = this["hasNextPage"]?.toString()?.toBoolean() ?: false,
)

// --------------------------------------------------------------------------
// Aniyomi
// --------------------------------------------------------------------------

class AniyomiBackend : ExtensionBackend {
    override val id: String = "aniyomi"
    override val displayName: String = "Aniyomi"

    private lateinit var ctx: BackendContext
    private lateinit var app: Context
    private var initialized = false
    private val itemTypeHints = mutableMapOf<String, Boolean>()

    override fun capabilities() = BackendCapabilities(
        anime = true, manga = true, novel = false,
        video = true, pages = true, filters = true, preferences = true,
        streaming = false, execution = ExecutionMechanism.DEX_CLASSLOADER,
    )

    override suspend fun attach(context: BackendContext) {
        ctx = context
        app = context.appContext
        withContext(Dispatchers.IO) {
            RuntimeBridge.initialize(app, context.settings)
            initialized = true
        }
    }

    private fun extensionsDir(): String =
        ctx.preferences.getString("aniyomi_extensions_path")
            ?: File(ctx.filesDir, "extensions").apply { mkdirs() }.absolutePath

    private fun installedBlocking(type: ItemType): List<Map<String, Any?>> = when (type) {
        ItemType.ANIME -> RuntimeBridge.getInstalledAnimeExtensions(app, extensionsDir())
        ItemType.MANGA -> RuntimeBridge.getInstalledMangaExtensions(app, extensionsDir())
        ItemType.NOVEL -> emptyList()
    }

    override suspend fun fetchInstalled(type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        installedBlocking(type).mapNotNull { row ->
            val source = row.toSourceOrNull(id)
            source?.let { itemTypeHints[it.id] = row["itemType"] == 1 }
            source
        }
    }

    override suspend fun fetchAvailable(type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        if (type == ItemType.NOVEL) return@withContext emptyList()
        val repos = repositories(type).map { it.url }.ifEmpty { DEFAULT_REPOS }
        val installedNames = runCatching { fetchInstalled(type).map { it.name.lowercase() }.toSet() }
            .getOrDefault(emptySet())
        repos.flatMap { repo ->
            runCatching { AniyomiRepoClient(ctx.httpClient).fetchIndex(repo, type) }.getOrElse { emptyList() }
        }.filter { it.name.lowercase() !in installedNames }
    }

    override suspend fun addRepository(url: String, type: ItemType) {
        val existing = ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        if (existing.none { it == url }) ctx.preferences.putString("repos_$id", (existing + url).joinToString("\n"))
    }

    override suspend fun removeRepository(url: String, type: ItemType) {
        val existing = ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        ctx.preferences.putString("repos_$id", existing.filterNot { it == url }.joinToString("\n"))
    }

    override suspend fun repositories(type: ItemType): List<Repo> =
        (ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
            .map { Repo(url = it, managerId = id) }

    override suspend fun install(source: Source) {
        val apkUrl = source.baseUrl.ifBlank { source.id }
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "extension_downloads").apply { mkdirs() }
            val out = File(dir, "aniyomi_${source.name.hashCode()}.apk")
            val request = Request.Builder().url(apkUrl).build()
            ctx.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw BackendException("HTTP_ERROR", "Aniyomi extension download failed: ${response.code}", id)
                }
                response.body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            out
        }
        if (!ExtensionInstaller.installApk(app, file.absolutePath)) {
            throw BackendException("INSTALL_FAILED", "Package installer rejected ${file.name}", id)
        }
    }

    override suspend fun uninstall(source: Source) {
        val pkg = source.id.substringBeforeLast('/')
        if (pkg.isNotBlank()) ExtensionInstaller.uninstallPackage(app, pkg)
    }

    override suspend fun update(source: Source): Unit = install(source)

    override suspend fun status(): BackendStatus {
        val count = runCatching {
            fetchInstalled(ItemType.ANIME).size + fetchInstalled(ItemType.MANGA).size
        }.getOrDefault(0)
        return BackendStatus(
            id = id, displayName = displayName, available = true, initialized = initialized,
            installedCount = count,
            execution = ExecutionMechanism.DEX_CLASSLOADER,
            capabilities = capabilities(),
            details = mapOf("extensionsDir" to extensionsDir()),
        )
    }

    override fun source(sourceId: String): ExtensionSource? {
        val realId = sourceId.substringAfter(':')
        val hintedType = itemTypeHints[realId]
        val isAnime = when {
            sourceId.startsWith("anime:") -> true
            sourceId.startsWith("manga:") -> false
            hintedType != null -> hintedType
            else -> true
        }
        val descriptor = (installedBlocking(if (isAnime) ItemType.ANIME else ItemType.MANGA))
            .firstOrNull { it["id"] == realId }
            ?.toSourceOrNull(id) ?: return null
        itemTypeHints[realId] = isAnime
        return AniyomiSource(descriptor, isAnime)
    }

    inner class AniyomiSource(override val source: Source, private val isAnime: Boolean) : ExtensionSource {

        override suspend fun getPopular(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.aniyomiGetPopular(app, source.id, isAnime, page, paramsMap(params)).asPages()
        }

        override suspend fun getLatestUpdates(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.aniyomiGetLatestUpdates(app, source.id, isAnime, page, paramsMap(params)).asPages()
        }

        override suspend fun search(
            query: String, page: Int, filters: List<Filter>, params: SourceParams?,
        ): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.aniyomiSearch(app, source.id, isAnime, query, page, filtersToMap(filters), paramsMap(params))
                .asPages()
        }

        override suspend fun getDetail(media: Media, params: SourceParams?): Media = withContext(Dispatchers.IO) {
            Media.fromLegacy(
                RuntimeBridge.aniyomiGetDetail(app, source.id, isAnime, media.toLegacyMap(), paramsMap(params))
                    .toJsonObject()
            )
        }

        override suspend fun getPageList(episode: Episode, params: SourceParams?): List<PageUrl> =
            withContext(Dispatchers.IO) {
                RuntimeBridge.aniyomiGetPageList(app, source.id, isAnime, episode.toLegacyMap(), paramsMap(params))
                    .mapNotNull { row ->
                        (row["url"] as? String)?.let { url ->
                            PageUrl(url, (row["headers"] as? Map<*, *>)?.entries?.associate { "${it.key}" to "${it.value}" })
                        }
                    }
            }

        override suspend fun getVideoList(episode: Episode, params: SourceParams?): List<Video> =
            withContext(Dispatchers.IO) {
                RuntimeBridge.aniyomiGetVideoList(app, source.id, isAnime, episode.toLegacyMap(), paramsMap(params))
                    .map { Video.fromLegacy(it.toJsonObject()) }
            }

        override suspend fun getFilterList(): List<Filter> = withContext(Dispatchers.IO) {
            RuntimeBridge.aniyomiGetFilterList(app, source.id, isAnime)
                .let { list -> JsonObject(mapOf("list" to JsonArray(list.map { it.toJsonElement() }))).mapFilters() }
        }

        override suspend fun getPreference(): List<SourcePreference> = emptyList()

        override suspend fun cancelRequest(token: String) {
            RuntimeBridge.cancelRequest(token)
        }

        override suspend fun stopHttpServer() {
            RuntimeBridge.aniyomiStopHttpServer(app, source.id, isAnime)
        }
    }

    companion object {
        /** Keiyoushi-maintained Tachiyomi/Aniyomi compatible indexes. */
        val DEFAULT_REPOS = listOf(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json",
        )
    }
}

/** Reads Tachiyomi/Aniyomi `index.min.json` extension indexes. */
class AniyomiRepoClient(private val client: okhttp3.OkHttpClient) {

    suspend fun fetchIndex(repoUrl: String, type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        val indexUrl = if (repoUrl.contains("index")) repoUrl else "${repoUrl.trimEnd('/')}/index.min.json"
        val request = Request.Builder().url(indexUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val array = BridgeJson.parseToJsonElement(response.body.string()) as? JsonArray
                ?: return@withContext emptyList()
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = obj.str("name") ?: return@mapNotNull null
                val apk = obj.str("apk") ?: return@mapNotNull null
                Source(
                    id = obj.str("pkg") ?: name,
                    name = name,
                    baseUrl = "${indexUrl.substringBeforeLast('/')}/$apk",
                    lang = obj.str("lang") ?: "",
                    isNsfw = obj.bool("nsfw") ?: false,
                    version = obj.str("version") ?: "0.0.0",
                    itemType = type,
                    managerId = "aniyomi",
                    supportsLatest = true,
                    supportsPopular = true,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------
// CloudStream
// --------------------------------------------------------------------------

class CloudStreamBackend : ExtensionBackend {
    override val id: String = "cloudstream"
    override val displayName: String = "CloudStream"

    private lateinit var ctx: BackendContext
    private lateinit var app: Context
    private var initialized = false

    override fun capabilities() = BackendCapabilities(
        anime = true, manga = false, novel = false,
        video = true, pages = false, filters = false, preferences = true,
        streaming = true, execution = ExecutionMechanism.DEX_CLASSLOADER,
    )

    override suspend fun attach(context: BackendContext) {
        ctx = context
        app = context.appContext
        withContext(Dispatchers.IO) {
            RuntimeBridge.initialize(app, context.settings)
            File(ctx.filesDir, "cloudstream_plugins").listFiles()?.forEach { file ->
                runCatching { RuntimeBridge.csLoadPlugin(app, file.absolutePath) }
            }
            initialized = true
        }
    }

    private fun providers(): List<Map<String, Any?>> = RuntimeBridge.csGetRegisteredProviders()

    override suspend fun fetchInstalled(type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        if (type != ItemType.ANIME) return@withContext emptyList()
        providers().mapNotNull { it.toSourceOrNull(id) }
    }

    override suspend fun fetchAvailable(type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        if (type != ItemType.ANIME) return@withContext emptyList()
        val repos = repositories(type).map { it.url }.ifEmpty { DEFAULT_REPOS }
        val installed = providers().map { r -> (r["id"] as? String).orEmpty().lowercase() }.toSet()
        repos.flatMap { repo ->
            runCatching { CloudStreamRepoClient(ctx.httpClient).fetchPlugins(repo) }.getOrElse { emptyList() }
        }.filter { it.name.lowercase() !in installed }
    }

    override suspend fun addRepository(url: String, type: ItemType) {
        val existing = ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        if (existing.none { it == url }) ctx.preferences.putString("repos_$id", (existing + url).joinToString("\n"))
    }

    override suspend fun removeRepository(url: String, type: ItemType) {
        val existing = ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        ctx.preferences.putString("repos_$id", existing.filterNot { it == url }.joinToString("\n"))
    }

    override suspend fun repositories(type: ItemType): List<Repo> =
        (ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
            .map { Repo(url = it, managerId = id) }

    override suspend fun install(source: Source): Unit = withContext(Dispatchers.IO) {
        val pluginUrl = source.baseUrl
        val dir = File(ctx.filesDir, "cloudstream_plugins").apply { mkdirs() }
        val fileName = pluginUrl.substringAfterLast('/').ifBlank { "${source.id}.jar" }
        val out = File(dir, fileName)
        val request = Request.Builder().url(pluginUrl).build()
        ctx.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw BackendException("HTTP_ERROR", "Plugin download failed: ${response.code}", id)
            }
            response.body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        if (!RuntimeBridge.csLoadPlugin(app, out.absolutePath)) {
            throw BackendException("PLUGIN_LOAD_FAILED", "CloudStream rejected ${out.name}", id)
        }
    }

    override suspend fun uninstall(source: Source) {
        withContext(Dispatchers.IO) {
            RuntimeBridge.csUnloadPlugin(source.name.ifBlank { source.id })
            File(ctx.filesDir, "cloudstream_plugins").listFiles()
                ?.filter { it.name.contains(source.id.substringAfterLast('/')) }
                ?.forEach { it.delete() }
        }
    }

    override suspend fun update(source: Source): Unit = install(source)

    override suspend fun status(): BackendStatus {
        val count = runCatching { providers().size }.getOrDefault(0)
        return BackendStatus(
            id = id, displayName = displayName, available = true, initialized = initialized,
            installedCount = count,
            execution = ExecutionMechanism.DEX_CLASSLOADER,
            capabilities = capabilities(),
        )
    }

    override fun source(sourceId: String): ExtensionSource? {
        val provider = providers().firstOrNull { (it["id"] as? String)?.equals(sourceId, true) == true }
            ?: providers().firstOrNull { (it["name"] as? String)?.equals(sourceId, true) == true }
            ?: return null
        val descriptor = provider.toSourceOrNull(id) ?: return null
        return CloudStreamSource(descriptor)
    }

    inner class CloudStreamSource(override val source: Source) : ExtensionSource {
        private val apiName: String get() = source.name

        override suspend fun getPopular(page: Int, params: SourceParams?) = getLatestUpdates(page, params)

        override suspend fun getLatestUpdates(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.csSearch(app, "", apiName, page, paramsMap(params)).asPages()
        }

        override suspend fun search(query: String, page: Int, filters: List<Filter>, params: SourceParams?): Pages =
            withContext(Dispatchers.IO) {
                RuntimeBridge.csSearch(app, query, apiName, page, paramsMap(params)).asPages()
            }

        override suspend fun getDetail(media: Media, params: SourceParams?): Media = withContext(Dispatchers.IO) {
            Media.fromCloudStream(
                RuntimeBridge.csGetDetail(app, apiName, media.url ?: "", paramsMap(params)).toJsonObject()
            )
        }

        override suspend fun getPageList(episode: Episode, params: SourceParams?): List<PageUrl> = emptyList()

        override suspend fun getVideoList(episode: Episode, params: SourceParams?): List<Video> =
            withContext(Dispatchers.IO) {
                RuntimeBridge.csGetVideoList(app, apiName, episode.url ?: "", paramsMap(params))
                    .map { Video.fromCloudStream(it.toJsonObject()) }
            }

        override fun getVideoListStream(episode: Episode, params: SourceParams?): Flow<Video> = flow {
            val collected = mutableListOf<Video>()
            withContext(Dispatchers.IO) {
                RuntimeBridge.csGetVideoListStream(
                    app, apiName, episode.url ?: "",
                    onLinkFound = { link -> collected += Video.fromCloudStream(link.toJsonObject()) },
                    parameters = paramsMap(params),
                )
            }
            collected.forEach { emit(it) }
        }.flowOn(Dispatchers.IO)

        override suspend fun cancelRequest(token: String) {
            RuntimeBridge.cancelRequest(token)
        }
    }

    companion object {
        val DEFAULT_REPOS = listOf(
            "https://raw.githubusercontent.com/recloudstream/cloudstream/master/plugins.json",
            "https://recloudstream.github.io/cs-repos/repo.json",
        )
    }
}

class CloudStreamRepoClient(private val client: okhttp3.OkHttpClient) {
    suspend fun fetchPlugins(repoUrl: String): List<Source> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(repoUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val root = BridgeJson.parseToJsonElement(response.body.string()) as? JsonObject
                ?: return@withContext emptyList()
            val plugins = (root["plugins"] as? JsonArray) ?: return@withContext emptyList()
            plugins.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val url = obj.str("internalUrl") ?: obj.str("url") ?: return@mapNotNull null
                Source(
                    id = url.substringAfterLast('/'),
                    name = obj.str("name") ?: url.substringAfterLast('/').substringBefore('.'),
                    baseUrl = url,
                    lang = obj.str("language") ?: obj.str("lang") ?: "",
                    iconUrl = obj.str("iconUrl") ?: "",
                    version = obj.str("version") ?: "1",
                    itemType = ItemType.ANIME,
                    managerId = "cloudstream",
                    supportsLatest = true,
                    supportsPopular = true,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------
// Kotatsu
// --------------------------------------------------------------------------

class KotatsuBackend : ExtensionBackend {
    override val id: String = "kotatsu"
    override val displayName: String = "Kotatsu"

    private lateinit var ctx: BackendContext
    private lateinit var app: Context
    private var initialized = false

    override fun capabilities() = BackendCapabilities(
        anime = false, manga = true, novel = false,
        video = false, pages = true, filters = false, preferences = false,
        execution = ExecutionMechanism.DEX_CLASSLOADER,
    )

    override suspend fun attach(context: BackendContext) {
        ctx = context
        app = context.appContext
        withContext(Dispatchers.IO) {
            io.chibi.runtimehost.kotatsu.KotatsuExtensionLoader.initialize(app)
            initialized = true
        }
    }

    private fun extensionsDir(): String =
        ctx.preferences.getString("kotatsu_extensions_path")
            ?: File(ctx.filesDir, "kotatsu_extensions").apply { mkdirs() }.absolutePath

    private fun load(): List<Map<String, Any?>> = RuntimeBridge.kotatsuLoadExtensions(app, extensionsDir())

    override suspend fun fetchInstalled(type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        if (type != ItemType.MANGA) return@withContext emptyList()
        load().mapNotNull { it.toSourceOrNull(id) }
    }

    /**
     * Kotatsu extensions are local jar archives executed by the ported parser
     * class loader. A remote index is optional in this ecosystem, so what the
     * loader can execute is what this backend reports as available.
     */
    override suspend fun fetchAvailable(type: ItemType): List<Source> = fetchInstalled(type)

    override suspend fun addRepository(url: String, type: ItemType) {
        val existing = ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        if (existing.none { it == url }) ctx.preferences.putString("repos_$id", (existing + url).joinToString("\n"))
    }

    override suspend fun removeRepository(url: String, type: ItemType) {
        val existing = ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        ctx.preferences.putString("repos_$id", existing.filterNot { it == url }.joinToString("\n"))
    }

    override suspend fun repositories(type: ItemType): List<Repo> =
        (ctx.preferences.getString("repos_$id")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
            .map { Repo(url = it, managerId = id) }

    override suspend fun install(source: Source): Unit = withContext(Dispatchers.IO) {
        val jar = File(source.baseUrl)
        if (!jar.exists()) throw BackendException("NOT_FOUND", "Kotatsu jar not found: ${source.baseUrl}", id)
        jar.copyTo(File(extensionsDir(), jar.name), overwrite = true)
        load()
    }

    override suspend fun uninstall(source: Source): Unit = withContext(Dispatchers.IO) {
        File(extensionsDir()).listFiles()
            ?.filter { it.name.contains(source.id.substringAfterLast('/')) }
            ?.forEach { it.delete() }
    }

    override suspend fun update(source: Source): Unit = install(source)

    override suspend fun status(): BackendStatus {
        val count = runCatching { load().size }.getOrDefault(0)
        return BackendStatus(
            id = id, displayName = displayName, available = true, initialized = initialized,
            installedCount = count, execution = ExecutionMechanism.DEX_CLASSLOADER,
            capabilities = capabilities(),
            details = mapOf("extensionsDir" to extensionsDir()),
        )
    }

    override fun source(sourceId: String): ExtensionSource? {
        val descriptor = runCatching {
            load().firstOrNull { it["id"] == sourceId }?.toSourceOrNull(id)
        }.getOrNull() ?: return null
        return KotatsuSource(descriptor)
    }

    inner class KotatsuSource(override val source: Source) : ExtensionSource {
        override suspend fun getPopular(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.kotatsuGetPopular(app, source.id, page).toJsonObject().toPages()
        }

        override suspend fun getLatestUpdates(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.kotatsuGetLatestUpdates(app, source.id, page).toJsonObject().toPages()
        }

        override suspend fun search(query: String, page: Int, filters: List<Filter>, params: SourceParams?): Pages =
            withContext(Dispatchers.IO) {
                RuntimeBridge.kotatsuSearch(app, source.id, query, page).toJsonObject().toPages()
            }

        override suspend fun getDetail(media: Media, params: SourceParams?): Media = withContext(Dispatchers.IO) {
            Media.fromLegacy(
                RuntimeBridge.kotatsuGetDetail(
                    app, source.id, media.url ?: "", media.title ?: "", media.cover ?: ""
                ).toJsonObject()
            )
        }

        override suspend fun getPageList(episode: Episode, params: SourceParams?): List<PageUrl> =
            withContext(Dispatchers.IO) {
                RuntimeBridge.kotatsuGetPageList(app, source.id, episode.url ?: "", episode.name ?: "")
                    .mapNotNull { row -> (row["url"] as? String)?.let { PageUrl(it) } }
            }

        override suspend fun getVideoList(episode: Episode, params: SourceParams?): List<Video> = emptyList()
    }
}
