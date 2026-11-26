/*
 * Conversions between the Map<String, Any?> payloads produced by the ported
 * AnymeX RuntimeBridge facade and the kotlinx.serialization models used by the
 * native SDK. The Dart bridge exchanged exactly these maps over MethodChannels.
 */
package io.chibi.runtimehost

import io.chibi.bridge.models.BridgeJson
import io.chibi.bridge.models.Episode
import io.chibi.bridge.models.Media
import io.chibi.bridge.models.Source
import io.chibi.bridge.models.fromLegacy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Any value returned by the facade -> JsonElement. */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v.toJsonElement() } }.toMap())
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is ByteArray -> JsonPrimitive(size)
    else -> JsonPrimitive(toString())
}

/** JsonElement -> plain Kotlin values, matching what the Dart bridge sent. */
fun JsonElement.toPlainMap(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toDoubleOrNull()?.let { if (it == it.toInt().toDouble()) it.toInt() else it } ?: content
    }
    is JsonObject -> entries.mapValues { it.value.toPlainMap() }
    is JsonArray -> map { it.toPlainMap() }
}

fun JsonObject.toPlain(): Map<String, Any?> = entries.mapValues { it.value.toPlainMap() }

fun Map<String, Any?>.toJsonObject(): JsonObject = buildJsonObject {
    forEach { (k, v) -> put(k, v.toJsonElement()) }
}

/** Legacy map shapes expected by RuntimeBridge for detail/episode calls. */
fun Media.toLegacyMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "url" to url,
    "cover" to cover,
    "description" to description,
    "author" to author,
    "artist" to artist,
    "genre" to genre,
    "thumbnail_url" to cover,
)

fun Episode.toLegacyMap(): Map<String, Any?> = mapOf(
    "url" to url,
    "name" to name,
    "dateUpload" to dateUpload,
    "scanlator" to scanlator,
    "thumbnail" to thumbnail,
    "description" to description,
    "memo" to (memo ?: description),
    "filler" to filler,
    "episodeNumber" to episodeNumber,
    "sortMap" to sortMap,
)

fun Map<String, Any?>.toSourceOrNull(managerId: String): Source? = runCatching {
    val json = toJsonObject()
    Source.fromLegacy(json).copy(managerId = managerId)
}.getOrNull()
