/*
 * Legado (阅读) backend — declarative rule engine over HTTP + HTML/JSON.
 *
 * Port of AnymeX lib/Services/Legado/ (LegadoExtensions.dart,
 * LegadoSourceMethods.dart) onto native Kotlin. Book sources are JSON rule
 * documents (the format Legado itself distributes), repositories are JSON
 * arrays of those documents, and execution happens entirely through
 * [LegadoRuleEngine] — no DEX or JS is required for CSS/JSON/regex sources.
 */
package io.chibi.runtime.backends.legado

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
import io.chibi.bridge.models.Video
import io.chibi.bridge.models.bool
import io.chibi.bridge.models.int
import io.chibi.bridge.models.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest

private fun JsonObject.rule(name: String): JsonObject? = this[name] as? JsonObject

/** One parsed Legado book source document. */
data class LegadoBookSource(
    val raw: JsonObject,
    val name: String,
    val baseUrl: String,
    val group: String,
    val searchUrl: String,
    val exploreUrl: String,
    val enabled: Boolean,
) {
    val searchRule: JsonObject? get() = raw.rule("ruleSearch")
    val exploreRule: JsonObject? get() = raw.rule("ruleExplore")
    val bookInfoRule: JsonObject? get() = raw.rule("ruleBookInfo")
    val tocRule: JsonObject? get() = raw.rule("ruleToc")
    val contentRule: JsonObject? get() = raw.rule("ruleContent")

    fun toSource(): Source = Source(
        id = baseUrl,
        name = name.ifBlank { baseUrl },
        baseUrl = baseUrl,
        lang = raw.str("bookSourceGroup") ?: group,
        iconUrl = raw.str("bookSourceComment") ?: "",
        version = raw.str("bookSourceVersion") ?: "1.0",
        itemType = ItemType.NOVEL,
        managerId = "legado",
        supportsLatest = exploreUrl.isNotBlank(),
        supportsPopular = exploreUrl.isNotBlank(),
    )

    companion object {
        fun from(json: JsonObject): LegadoBookSource {
            val base = json.str("bookSourceUrl") ?: json.str("url") ?: ""
            var explore = json.str("exploreUrl") ?: ""
            // exploreUrl may embed per-category templates separated by && or newlines.
            if (explore.contains("&&")) explore = explore.split("&&").first()
            if (explore.contains("\n")) explore = explore.split("\n").first()
            return LegadoBookSource(
                raw = json,
                name = json.str("bookSourceName") ?: json.str("name") ?: base,
                baseUrl = base,
                group = json.str("bookSourceGroup") ?: "",
                searchUrl = json.str("searchUrl") ?: "",
                exploreUrl = explore.substringBefore(','),
                enabled = json.bool("enabled") ?: true,
            )
        }
    }
}

class LegadoBackend : ExtensionBackend {
    override val id: String = "legado"
    override val displayName: String = "Legado"

    private lateinit var ctx: BackendContext
    private var initialized = false

    override fun capabilities() = BackendCapabilities(
        anime = false, manga = false, novel = true,
        video = false, pages = false, filters = false, preferences = false,
        execution = ExecutionMechanism.RULE_ENGINE,
    )

    override suspend fun attach(context: BackendContext) {
        ctx = context
        sourcesDir().mkdirs()
        initialized = true
    }

    private fun sourcesDir(): File = File(ctx.filesDir, "legado_sources")
    private fun downloadsDir(): File = File(ctx.filesDir, "legado_downloads")

    private fun fileNameFor(baseUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(baseUrl.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".json"
    }

    private fun readSources(): List<LegadoBookSource> = sourcesDir().listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { file ->
            runCatching {
                LegadoBookSource.from(BridgeJson.parseToJsonElement(file.readText()) as? JsonObject ?: return@runCatching null)
            }.getOrNull()
        }
        ?: emptyList()

    private fun writeSource(json: String, expectedBase: String? = null): LegadoBookSource? {
        val obj = runCatching { BridgeJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val parsed = LegadoBookSource.from(obj)
        if (parsed.baseUrl.isBlank()) return null
        val target = File(sourcesDir(), fileNameFor(parsed.baseUrl))
        target.writeText(BridgeJson.encodeToString(JsonObject.serializer(), obj))
        return parsed
    }

    private suspend fun fetch(url: String, body: String? = null, baseUrl: String = ""): String =
        withContext(Dispatchers.IO) {
            val resolved = if (url.startsWith("http")) url else LegadoRuleEngine.resolveUrl(baseUrl, url)
            val builder = Request.Builder().url(resolved)
            if (body != null) {
                builder.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            }
            ctx.httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw BackendException("HTTP_ERROR", "Legado request failed: ${response.code}", id)
                response.body.string()
            }
        }

    override suspend fun fetchInstalled(type: ItemType): List<Source> =
        if (type == ItemType.NOVEL) readSources().map { it.toSource() } else emptyList()

    override suspend fun fetchAvailable(type: ItemType): List<Source> = withContext(Dispatchers.IO) {
        if (type != ItemType.NOVEL) return@withContext emptyList()
        val repos = repositories(type).map { it.url }
        val installed = readSources().map { it.baseUrl }.toSet()
        repos.flatMap { repo ->
            runCatching {
                parseRepo(repo, fetch(repo))
            }.getOrElse { emptyList() }
        }.filter { it.baseUrl !in installed }.map { it.toSource() }
    }

    private fun parseRepo(repoUrl: String, body: String): List<LegadoBookSource> {
        val element = runCatching { BridgeJson.parseToJsonElement(body.trim()) }.getOrNull() ?: return emptyList()
        val objects: List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                val list = (element["sources"] ?: element["bookSources"]) as? JsonArray
                list?.mapNotNull { it as? JsonObject } ?: listOf(element)
            }
            else -> emptyList()
        }
        return objects.map { LegadoBookSource.from(it) }.filter { it.enabled && it.baseUrl.isNotBlank() }
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
        val raw = when {
            source.baseUrl.startsWith("http") && source.name.isBlank() -> fetch(source.baseUrl)
            else -> fetch(source.baseUrl)
        }
        val parsed = writeSource(raw) ?: throw BackendException(
            "INVALID_SOURCE", "Not a Legado book source document: ${source.baseUrl}", id
        )
        ctx.logger.info("Legado", "Installed book source ${parsed.name}")
    }

    override suspend fun uninstall(source: Source): Unit = withContext(Dispatchers.IO) {
        File(sourcesDir(), fileNameFor(source.baseUrl)).delete()
    }

    override suspend fun update(source: Source) = install(source)

    override suspend fun status(): BackendStatus {
        val count = runCatching { readSources().size }.getOrDefault(0)
        return BackendStatus(
            id = id, displayName = displayName, available = true, initialized = initialized,
            installedCount = count,
            execution = ExecutionMechanism.RULE_ENGINE,
            capabilities = capabilities(),
            details = mapOf("sourcesDir" to sourcesDir().absolutePath),
        )
    }

    override fun source(sourceId: String): ExtensionSource? {
        val bookSource = readSources().firstOrNull { it.baseUrl == sourceId || it.name == sourceId } ?: return null
        return LegadoSource(bookSource.toSource(), bookSource)
    }

    inner class LegadoSource(override val source: Source, private val book: LegadoBookSource) : ExtensionSource {

        private suspend fun explore(page: Int): Pages {
            val template = book.exploreUrl.ifBlank { book.searchUrl }
            if (template.isBlank()) return Pages.empty()
            val url = LegadoRuleEngine.buildUrl(template, book.baseUrl, "", page)
            val body = fetch(url, baseUrl = book.baseUrl)
            val doc = LegadoRuleEngine.parseHtml(body, book.baseUrl)
            val rules = book.exploreRule ?: book.searchRule
            val listRule = rules?.str("bookList") ?: return Pages.empty()
            val list = LegadoRuleEngine.selectElements(doc.body(), listRule).map { element -> element.toMedia(rules) }
            return Pages(list = list, hasNextPage = list.isNotEmpty())
        }

        private fun org.jsoup.nodes.Element.toMedia(rules: JsonObject?): Media = Media(
            title = LegadoRuleEngine.string(this, rules?.str("name"), book.baseUrl),
            url = LegadoRuleEngine.string(this, rules?.str("bookUrl"), book.baseUrl),
            cover = LegadoRuleEngine.string(this, rules?.str("coverUrl"), book.baseUrl),
            description = LegadoRuleEngine.string(this, rules?.str("intro")),
            author = LegadoRuleEngine.string(this, rules?.str("author")),
            genre = LegadoRuleEngine.stringList(this, rules?.str("kind")),
        )

        override suspend fun getPopular(page: Int, params: SourceParams?): Pages = explore(page)

        override suspend fun getLatestUpdates(page: Int, params: SourceParams?): Pages = explore(page)

        override suspend fun search(query: String, page: Int, filters: List<Filter>, params: SourceParams?): Pages {
            if (book.searchUrl.isBlank()) return Pages.empty()
            val (template, options) = LegadoRuleEngine.splitUrlOptions(book.searchUrl)
            val url = LegadoRuleEngine.buildUrl(template, book.baseUrl, query, page)
            val method = options?.str("method") ?: "GET"
            val bodyText = when (method.uppercase()) {
                "POST" -> options?.str("body")
                    ?.replace("{{key}}", query)
                    ?.replace("{{page}}", page.toString())
                else -> null
            }
            val body = fetch(url, bodyText, book.baseUrl)
            val doc = LegadoRuleEngine.parseHtml(body, book.baseUrl)
            val rules = book.searchRule
            val listRule = rules?.str("bookList") ?: return Pages.empty()
            val list = LegadoRuleEngine.selectElements(doc.body(), listRule).map { it.toMedia(rules) }
            return Pages(list = list, hasNextPage = list.isNotEmpty())
        }

        override suspend fun getDetail(media: Media, params: SourceParams?): Media {
            val url = media.url ?: return media
            val body = fetch(url, baseUrl = book.baseUrl)
            val doc = LegadoRuleEngine.parseHtml(body, book.baseUrl)
            val root = doc.body() ?: return media
            val rules = book.bookInfoRule
            val detail = media.copy(
                title = LegadoRuleEngine.string(root, rules?.str("name")) .ifEmpty { media.title ?: "" },
                author = LegadoRuleEngine.string(root, rules?.str("author")).ifEmpty { media.author ?: "" },
                description = LegadoRuleEngine.string(root, rules?.str("intro")).ifEmpty { media.description ?: "" },
                cover = LegadoRuleEngine.string(root, rules?.str("coverUrl"), book.baseUrl).ifEmpty { media.cover ?: "" },
                genre = LegadoRuleEngine.stringList(root, rules?.str("kind")).ifEmpty { media.genre },
            )
            val tocUrl = LegadoRuleEngine.string(root, rules?.str("tocUrl"), book.baseUrl).ifEmpty { url }
            val tocBody = if (tocUrl != url) fetch(tocUrl, baseUrl = book.baseUrl) else body
            val tocDoc = if (tocUrl != url) LegadoRuleEngine.parseHtml(tocBody, book.baseUrl) else doc
            val chapters = LegadoRuleEngine.chapters(
                tocDoc,
                book.tocRule?.str("chapterList"),
                book.tocRule?.str("chapterName"),
                book.tocRule?.str("chapterUrl"),
                book.baseUrl,
            )
            return detail.copy(
                episodes = chapters.mapIndexed { index, (name, chapterUrl) ->
                    Episode(url = chapterUrl, name = name, episodeNumber = "${index + 1}")
                },
            )
        }

        override suspend fun getPageList(episode: Episode, params: SourceParams?): List<PageUrl> = emptyList()

        override suspend fun getVideoList(episode: Episode, params: SourceParams?): List<Video> = emptyList()

        override suspend fun getNovelContent(chapterTitle: String, chapterId: String, params: SourceParams?): String {
            if (chapterId.isBlank()) return ""
            val body = fetch(chapterId, baseUrl = book.baseUrl)
            val doc = LegadoRuleEngine.parseHtml(body, book.baseUrl)
            val root = doc.body() ?: return ""
            val contentRule = book.contentRule?.str("content") ?: ""
            if (contentRule.isBlank()) return root.text()
            val html = LegadoRuleEngine.selectElements(root, contentRule)
                .joinToString("\n") { it.text() }
            return html.ifBlank { LegadoRuleEngine.string(root, contentRule) }
        }

        override suspend fun getFilterList(): List<Filter> = emptyList()

        override suspend fun cancelRequest(token: String) = Unit
    }
}
