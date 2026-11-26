/*
 * Lenient JSON parsing for payloads produced by the original Dart bridge and by
 * third-party extensions. The Dart models accepted several aliases per field
 * (see lib/Models/DMedia.dart, DEpisode.dart, Video.dart, Source.dart); those
 * aliases are preserved here so existing extensions keep working unchanged.
 */
package io.chibi.bridge.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared JSON configuration. Mirrors the Dart side (`explicitNulls: false`). */
val BridgeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

fun JsonObject.str(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key] ?: continue
        if (value is JsonNull) continue
        val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        if (text.isNotEmpty()) return text
    }
    return null
}

fun JsonObject.bool(vararg keys: String): Boolean? {
    for (key in keys) {
        val value = this[key] ?: continue
        if (value is JsonNull) continue
        (value as? JsonPrimitive)?.booleanOrNull?.let { return it }
        val text = (value as? JsonPrimitive)?.contentOrNull
        if (text != null) {
            if (text.equals("true", true) || text == "1") return true
            if (text.equals("false", true) || text == "0") return false
        }
    }
    return null
}

fun JsonObject.int(vararg keys: String): Int? {
    for (key in keys) {
        val value = this[key] ?: continue
        (value as? JsonPrimitive)?.intOrNull?.let { return it }
        (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { return it }
    }
    return null
}

fun JsonObject.obj(vararg keys: String): JsonObject? {
    for (key in keys) {
        val value = this[key]
        if (value is JsonObject) return value
    }
    return null
}

fun JsonObject.stringList(vararg keys: String): List<String> {
    for (key in keys) {
        val value = this[key]
        if (value is JsonArray) return value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
    return emptyList()
}

fun JsonObject.stringMap(vararg keys: String): Map<String, String>? {
    for (key in keys) {
        val value = this[key]
        if (value is JsonObject) {
            return value.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
        }
    }
    return null
}

private fun JsonObject.arrayObjects(vararg keys: String): List<JsonObject> {
    for (key in keys) {
        val value = this[key]
        if (value is JsonArray) return value.mapNotNull { it as? JsonObject }
    }
    return emptyList()
}

/** Dart `DEpisode.fromJson`. */
fun Episode.Companion.fromLegacy(json: JsonObject): Episode {
    val episodeNum = json.str("episodeNumber", "episode_number")?.toDoubleOrNull()
    val episodeStr = when {
        episodeNum == null -> ""
        episodeNum == episodeNum.toInt().toDouble() -> episodeNum.toInt().toString()
        else -> episodeNum.toString()
    }
    return Episode(
        url = json.str("url"),
        name = json.str("name"),
        dateUpload = json.str("dateUpload", "date_upload") ?: "",
        scanlator = json.str("scanlator"),
        thumbnail = json.str("thumbnail"),
        description = json.str("description"),
        memo = json.str("memo", "description"),
        filler = json.bool("filler"),
        episodeNumber = episodeStr,
        sortMap = json.stringMap("sortMap") ?: mapOf("season" to (json.str("season") ?: "")),
    )
}

/** Dart `DEpisode.fromCs` (CloudStream payloads). */
fun Episode.Companion.fromCloudStream(json: JsonObject): Episode = Episode(
    url = json.str("dataUrl", "url"),
    name = json.str("name"),
    dateUpload = json.str("dateUpload", "date_upload") ?: "",
    scanlator = json.str("scanlator"),
    thumbnail = json.str("thumbnail", "posterUrl") ?: json.obj("extraData")?.str("thumbnail"),
    description = json.str("description"),
    memo = json.str("memo", "description"),
    filler = json.bool("filler"),
    episodeNumber = json.str("episodeNumber", "episode") ?: "1",
    sortMap = mapOf(
        "season" to (json.obj("extraData")?.str("season") ?: ""),
        "type" to (json.obj("extraData")?.str("episodeGroup") ?: ""),
    ),
)

/** Dart `DMedia.fromJson`. */
fun Media.Companion.fromLegacy(json: JsonObject): Media {
    val poster = json.str("cover", "posterUrl", "thumbnail_url")
    return Media(
        title = json.str("title", "name"),
        url = json.str("url"),
        cover = json.str("thumbnail_url") ?: poster,
        description = json.str("description"),
        author = json.str("author"),
        artist = json.str("artist"),
        genre = json.stringList("genre"),
        episodes = json.arrayObjects("episodes").map { Episode.fromLegacy(it) },
    )
}

/** Dart `DMedia.fromCs`. */
fun Media.Companion.fromCloudStream(json: JsonObject): Media {
    val title = json.str("title", "name")
    val episodes = json.arrayObjects("episodes").map { raw ->
        var ep = raw
        val urlData = raw.str("url", "data")
        if (title != null && urlData != null && urlData.trim().startsWith("{")) {
            runCatching { BridgeJson.parseToJsonElement(urlData).jsonObject }.getOrNull()?.let { decoded ->
                if (!decoded.containsKey("title")) {
                    val injected = BridgeJson.encodeToString(JsonObject.serializer(), JsonObject(decoded + ("title" to JsonPrimitive(title))))
                    ep = JsonObject(
                        raw + ("url" to JsonPrimitive(injected)) +
                            (if (raw.containsKey("data")) mapOf("data" to JsonPrimitive(injected)) else emptyMap())
                    )
                }
            }
        }
        Episode.fromCloudStream(ep)
    }
    val ordered = if (episodes.isEmpty() || episodes.first().episodeNumber == "0" || episodes.first().episodeNumber == "1") {
        episodes
    } else {
        episodes.reversed()
    }
    return Media(
        title = title,
        url = json.str("url"),
        cover = json.str("cover", "thumbnail_url"),
        description = json.str("description"),
        author = json.str("author"),
        artist = json.str("artist"),
        genre = json.stringList("genre"),
        episodes = ordered,
    )
}

/** Dart `Pages.fromJson`. */
fun Pages.Companion.fromLegacy(json: JsonObject): Pages = Pages(
    list = json.arrayObjects("list").map { Media.fromLegacy(it) },
    hasNextPage = json.bool("hasNextPage") ?: false,
)

/** Dart `Video.fromJson`. */
fun Video.Companion.fromLegacy(json: JsonObject): Video {
    val title = json.str("title")
    val rawQuality = json.str("quality")
    var quality = rawQuality ?: title ?: "Default"
    val videoTitle = title ?: rawQuality ?: "Default"
    if (title != null) {
        val titleLower = title.lowercase()
        val qualityLower = quality.lowercase()
        if ((titleLower.contains("dub") && !qualityLower.contains("dub")) ||
            (titleLower.contains("sub") && !qualityLower.contains("sub"))
        ) {
            quality = title
        }
    }
    val subtitles = (json["subtitles"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { t ->
        Track(file = t.str("file"), label = t.str("label"))
    } ?: emptyList()
    val audios = (json["audios"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { t ->
        Track(file = t.str("file"), label = t.str("label"))
    } ?: emptyList()
    return Video(
        title = videoTitle,
        url = json.str("url") ?: "",
        quality = quality,
        headers = normalizeHeaders(json.stringMap("headers")),
        subtitles = subtitles,
        audios = audios,
        extraData = json,
    )
}

/** Dart `Video.fromCs` — CloudStream keeps request headers under extraData.allHeaders. */
fun Video.Companion.fromCloudStream(json: JsonObject): Video {
    val base = Video.fromLegacy(json)
    return base.copy(headers = normalizeHeaders(json.obj("extraData")?.stringMap("allHeaders") ?: base.headers))
}

/** Dart `normalizeHeaders`: a Referer must end with a slash. */
fun normalizeHeaders(headers: Map<String, String>?): Map<String, String>? {
    if (headers == null) return null
    val updated = headers.toMutableMap()
    val refererKey = updated.keys.firstOrNull { it.equals("referer", true) } ?: return updated
    val value = updated[refererKey]
    if (value != null && !value.endsWith("/")) updated[refererKey] = "$value/"
    return updated
}

/** Dart `Source.fromJson`. */
fun Source.Companion.fromLegacy(json: JsonObject): Source {
    val isLnReader = json.containsKey("site") && json.containsKey("url") && json.str("sourceCodeLanguage") == null
    val itemType = when {
        isLnReader -> ItemType.NOVEL
        else -> ItemType.fromIndex(json.int("itemType"))
    }
    val shared = json.bool("isShared")
    return Source(
        id = json.str("id") ?: "",
        name = json.str("name") ?: "",
        baseUrl = json.str("baseUrl", "site") ?: "",
        lang = json.str("lang") ?: "",
        isNsfw = json.bool("isNsfw") ?: false,
        iconUrl = json.str("iconUrl") ?: "",
        version = json.str("version") ?: "0.0.1",
        versionLast = json.str("versionLast") ?: "0.0.1",
        itemType = itemType,
        repo = json.str("repo"),
        managerId = json.str("managerId"),
        hasUpdate = json.bool("hasUpdate") ?: false,
        isPrivate = json.bool("isPrivate") ?: shared?.let { !it },
        supportsLatest = json.bool("supportsLatest") ?: false,
        supportsPopular = json.bool("supportsPopular") ?: false,
    )
}

/** Dart `Repo.fromJson`. */
fun Repo.Companion.fromLegacy(json: JsonObject): Repo {
    val extensions = when (val raw = json["extensions"]) {
        is JsonArray -> raw.joinToString(",") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        null, is JsonNull -> null
        else -> (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
    }
    return Repo(
        url = json.str("url") ?: "",
        name = json.str("name"),
        iconUrl = json.str("iconUrl"),
        extensions = extensions,
        managerId = json.str("managerId"),
    )
}

/** Dart `Extension.isMatchingRepoUrl` — used to reconcile repository URLs. */
fun isMatchingRepoUrl(sourceRepo: String?, targetRepoUrl: String): Boolean {
    if (sourceRepo.isNullOrBlank()) return false
    fun clean(input: String): String = input.trim().lowercase()
        .replace(Regex("^https?://"), "")
        .replace(Regex("/(index\\.min\\.json|index\\.json|index\\.pb\\.gz|index\\.pb|manifest\\.json|plugin\\.jar|plugins\\.json)$"), "")
        .replace(Regex("/+$"), "")

    val a = clean(sourceRepo)
    val b = clean(targetRepoUrl)
    if (a == b) return true
    return a.startsWith("$b/") || b.startsWith("$a/")
}

fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
