/*
 * Tachiyomi engine exposed to the Tsundoku and iReader backend modules.
 *
 * Both ecosystems ship Tachiyomi-compatible extension packages; the original
 * Dart bridges forwarded their calls into the runtime host's Aniyomi manager.
 * This implementation keeps that behaviour, backed by the ported Aniyomi code in
 * backends/aniyomi via the RuntimeBridge facade.
 */
package io.chibi.runtimehost

import android.content.Context
import com.anymex.runtimehost.RuntimeBridge
import io.chibi.bridge.backend.TachiyomiEngine
import io.chibi.bridge.models.Episode
import io.chibi.bridge.models.Media
import io.chibi.bridge.models.PageUrl
import io.chibi.bridge.models.Pages
import io.chibi.bridge.models.Source
import io.chibi.bridge.models.SourceParams
import io.chibi.bridge.models.fromLegacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HostTachiyomiEngine(private val app: Context, private val managerId: String) : TachiyomiEngine {

    private fun params(p: SourceParams?): Map<String, Any?>? {
        if (p == null) return null
        val map = mutableMapOf<String, Any?>()
        p.cancelToken?.let { map["token"] = it }
        p.customParams.forEach { (k, v) -> map[k] = v }
        return map.ifEmpty { null }
    }

    override suspend fun installed(directory: String): List<Source> = withContext(Dispatchers.IO) {
        RuntimeBridge.getInstalledMangaExtensions(app, directory)
            .mapNotNull { it.toSourceOrNull(managerId) }
    }

    override suspend fun source(sourceId: String): TachiyomiEngine.TachiyomiSource? =
        withContext(Dispatchers.IO) {
            val descriptor = RuntimeBridge.getInstalledMangaExtensions(app, null)
                .firstOrNull { it["id"] == sourceId }
                ?.toSourceOrNull(managerId) ?: return@withContext null
            TachiyomiSourceImpl(descriptor)
        }

    inner class TachiyomiSourceImpl(override val source: Source) : TachiyomiEngine.TachiyomiSource {

        override suspend fun getPopular(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.aniyomiGetPopular(app, source.id, false, page, params(params)).asPages()
        }

        override suspend fun getLatestUpdates(page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.aniyomiGetLatestUpdates(app, source.id, false, page, params(params)).asPages()
        }

        override suspend fun search(query: String, page: Int, params: SourceParams?): Pages = withContext(Dispatchers.IO) {
            RuntimeBridge.aniyomiSearch(app, source.id, false, query, page, null, params(params)).asPages()
        }

        override suspend fun getDetail(media: Media, params: SourceParams?): Media = withContext(Dispatchers.IO) {
            Media.fromLegacy(
                RuntimeBridge.aniyomiGetDetail(app, source.id, false, media.toLegacyMap(), params(params)).toJsonObject()
            )
        }

        override suspend fun getPageList(episode: Episode, params: SourceParams?): List<PageUrl> =
            withContext(Dispatchers.IO) {
                RuntimeBridge.aniyomiGetPageList(app, source.id, false, episode.toLegacyMap(), params(params))
                    .mapNotNull { row -> (row["url"] as? String)?.let { PageUrl(it) } }
            }

        override suspend fun getNovelContent(chapterTitle: String, chapterId: String): String =
            withContext(Dispatchers.IO) {
                // Novel chapters are page lists in Tachiyomi-compatible packages.
                val pages = RuntimeBridge.aniyomiGetPageList(
                    app, source.id, false,
                    mapOf("url" to chapterId, "name" to chapterTitle),
                    null,
                )
                pages.joinToString("\n") { (it["url"] as? String).orEmpty() }
            }

        override suspend fun cancelRequest(token: String) {
            RuntimeBridge.cancelRequest(token)
        }
    }

    private fun Map<String, Any?>.asPages(): Pages = Pages(
        list = (this["list"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()
            ?.map { Media.fromLegacy(it.toJsonObject()) } ?: emptyList(),
        hasNextPage = this["hasNextPage"] as? Boolean ?: false,
    )
}
