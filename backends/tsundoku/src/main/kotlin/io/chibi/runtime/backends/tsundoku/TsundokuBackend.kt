/*
 * Tsundoku backend.
 *
 * Upstream (AnymeX lib/Services/Tsundoku/) Tsundoku extensions are
 * Tachiyomi-compatible packages: the Dart bridge forwarded every call to the
 * runtime host's Aniyomi manager over the `tsundokuExtensionBridge` channel
 * ("getInstalledMangaExtensions", "aniyomiGetPopular", ...). This port keeps the
 * same execution path by delegating to the shared [TachiyomiEngine], and adds the
 * native repository handling the Dart layer provided.
 */
package io.chibi.runtime.backends.tsundoku

import io.chibi.bridge.backend.BackendContext
import io.chibi.bridge.backend.BackendException
import io.chibi.bridge.backend.ExtensionBackend
import io.chibi.bridge.backend.ExtensionSource
import io.chibi.bridge.backend.TachiyomiEngine
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
import io.chibi.bridge.models.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.File

class TsundokuBackend : ExtensionBackend {
    override val id: String = "tsundoku"
    override val displayName: String = "Tsundoku"

    private lateinit var ctx: BackendContext
    private var initialized = false

    override fun capabilities() = BackendCapabilities(
        anime = false, manga = true, novel = true,
        video = false, pages = true, filters = false, preferences = false,
        execution = ExecutionMechanism.DEX_CLASSLOADER,
    )

    override suspend fun attach(context: BackendContext) {
        ctx = context
        initialized = true
    }

    private fun engine(): TachiyomiEngine = ctx.tachiyomi ?: throw BackendException(
        "ENGINE_UNAVAILABLE", "The Tachiyomi execution engine is not available in this host", id
    )

    private fun extensionsDir(): String =
        ctx.preferences.getString("tsundoku_extensions_path")
            ?: File(ctx.filesDir, "tsundoku_extensions").apply { mkdirs() }.absolutePath

    override suspend fun fetchInstalled(type: ItemType): List<Source> =
        if (type == ItemType.ANIME) emptyList() else engine().installed(extensionsDir())

    override suspend fun fetchAvailable(type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        if (type == ItemType.ANIME) return@withContext emptyList()
        val repos = repositories(type).map { it.url }.ifEmpty { DEFAULT_REPOS }
        val installed = runCatching { engine().installed(extensionsDir()).map { it.name.lowercase() }.toSet() }
            .getOrDefault(emptySet())
        repos.flatMap { repo -> runCatching { TachiyomiIndex.fetch(ctx, repo, type) }.getOrElse { emptyList() } }
            .filter { it.name.lowercase() !in installed }
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
        val dir = File(ctx.filesDir, "extension_downloads").apply { mkdirs() }
        val out = TachiyomiIndex.download(ctx, source, dir)
        if (!io.chibi.bridge.backend.ExtensionInstallerBridge.install(ctx.appContext, out.absolutePath)) {
            throw BackendException("INSTALL_FAILED", "Package installer rejected ${out.name}", id)
        }
    }

    override suspend fun uninstall(source: Source) {
        io.chibi.bridge.backend.ExtensionInstallerBridge.uninstall(ctx.appContext, source.id.substringBeforeLast('/'))
    }

    override suspend fun update(source: Source) = install(source)

    override suspend fun status(): BackendStatus {
        val count = runCatching { engine().installed(extensionsDir()).size }.getOrElse { 0 }
        return BackendStatus(
            id = id, displayName = displayName, available = ctx.tachiyomi != null, initialized = initialized,
            installedCount = count, execution = ExecutionMechanism.DEX_CLASSLOADER,
            capabilities = capabilities(),
            message = if (ctx.tachiyomi == null) "Tachiyomi engine unavailable" else null,
        )
    }

    override fun source(sourceId: String): ExtensionSource? {
        val engine = ctx.tachiyomi ?: return null
        val delegate = kotlinx.coroutines.runBlocking { engine.source(sourceId) } ?: return null
        return TsundokuSource(delegate)
    }

    inner class TsundokuSource(private val delegate: TachiyomiEngine.TachiyomiSource) : ExtensionSource {
        override val source: Source get() = delegate.source
        override suspend fun getPopular(page: Int, params: SourceParams?) = delegate.getPopular(page, params)
        override suspend fun getLatestUpdates(page: Int, params: SourceParams?) = delegate.getLatestUpdates(page, params)
        override suspend fun search(query: String, page: Int, filters: List<Filter>, params: SourceParams?) =
            delegate.search(query, page, params)
        override suspend fun getDetail(media: Media, params: SourceParams?) = delegate.getDetail(media, params)
        override suspend fun getPageList(episode: Episode, params: SourceParams?) = delegate.getPageList(episode, params)
        override suspend fun getVideoList(episode: Episode, params: SourceParams?) = emptyList<Video>()
        override suspend fun getNovelContent(chapterTitle: String, chapterId: String, params: SourceParams?) =
            delegate.getNovelContent(chapterTitle, chapterId)
        override suspend fun cancelRequest(token: String) = delegate.cancelRequest(token)
    }

    companion object {
        /** Tsundoku novel extension index (Tachiyomi repo layout). */
        val DEFAULT_REPOS = listOf(
            "https://raw.githubusercontent.com/tsundoku-otaku/extensions/repo/index.min.json",
        )
    }
}

/** Shared Tachiyomi `index.min.json` handling for Tsundoku and iReader. */
object TachiyomiIndex {

    suspend fun fetch(ctx: BackendContext, repoUrl: String, type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        val indexUrl = if (repoUrl.contains("index")) repoUrl else "${repoUrl.trimEnd('/')}/index.min.json"
        val request = Request.Builder().url(indexUrl).build()
        ctx.httpClient.newCall(request).execute().use { response ->
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
                    managerId = "tsundoku",
                    supportsLatest = true,
                    supportsPopular = true,
                )
            }
        }
    }

    suspend fun download(ctx: BackendContext, source: Source, dir: File): File = withContext(Dispatchers.IO) {
        val out = File(dir, "${source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")
        val request = Request.Builder().url(source.baseUrl).build()
        ctx.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BackendException("HTTP_ERROR", "Download failed: ${response.code}", source.managerId)
            response.body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        out
    }
}
