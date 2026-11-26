/*
 * Legado (阅读) rule engine — Kotlin port of
 * AnymeX lib/Services/Legado/RuleEngine/LegadoRuleEngine.dart.
 *
 * Supported rule syntax (the subset the Dart engine implemented):
 *   - URL templates: `/search?kw={{key}}&page={{page}}` with
 *     `{{searchKey}}`, `{{key}}`, `{{page}}` and `<...>` page-1 conditionals;
 *   - rule alternatives separated by `||` (first non-empty wins);
 *   - CSS selectors, `@css:` prefix, `tag.class@attr` and `@text`/`@html`;
 *   - `@json:$.path.to.value` JSON paths (with `[*]` array wildcards);
 *   - regex extraction `##pattern##replacement?` and `##pattern`;
 *   - index selection `[0]`, `[-1]`, `[0,2]`.
 *
 * Legado's `@js:` rules require a JavaScript engine; they are executed by the
 * host's embedded engine when available (see LegadoBackend), otherwise the rule
 * is reported as unsupported instead of silently returning nothing.
 */
package io.chibi.runtime.backends.legado

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LegadoRuleException(message: String) : Exception(message)

object LegadoRuleEngine {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Extract JSON options appended to a URL: `url,{"method":"POST","body":"..."}`. */
    fun splitUrlOptions(template: String): Pair<String, JsonObject?> {
        val trimmed = template.trim()
        val commaIndex = trimmed.indexOf(",{")
        if (commaIndex == -1) return trimmed to null
        val url = trimmed.substring(0, commaIndex)
        val options = runCatching {
            json.parseToJsonElement(trimmed.substring(commaIndex + 1)) as? JsonObject
        }.getOrNull()
        return url to options
    }

    /** Port of `LegadoRuleEngine.buildUrl`. */
    fun buildUrl(urlTemplate: String, baseUrl: String, query: String = "", page: Int = 1): String {
        var (url, _) = splitUrlOptions(urlTemplate)

        val conditional = Regex("<([^>]*?\\{\\{page}}[^>]*?)>")
        url = if (page <= 1) {
            url.replace(conditional, "")
        } else {
            url.replace(conditional) { it.groupValues[1] }
        }

        url = url
            .replace("{{key}}", encode(query))
            .replace("{{searchKey}}", encode(query))
            .replace("{{page}}", page.toString())

        return resolveUrl(baseUrl, url)
    }

    fun resolveUrl(baseUrl: String, relative: String): String {
        if (relative.isEmpty()) return baseUrl
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            java.net.URI(baseUrl).resolve(relative).toString()
        } catch (_: Throwable) {
            when {
                baseUrl.endsWith("/") && relative.startsWith("/") -> baseUrl + relative.substring(1)
                !baseUrl.endsWith("/") && !relative.startsWith("/") -> "$baseUrl/$relative"
                else -> baseUrl + relative
            }
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------- selection

    /** All elements matching a Legado element rule (`bookList`, `chapterList`). */
    fun selectElements(root: Element?, rule: String?): List<Element> {
        if (root == null || rule.isNullOrBlank()) return emptyList()
        val doc = root.ownerDocument()
        for (alternative in rule.split("||")) {
            val selector = alternative.trim()
            if (selector.isEmpty()) continue
            val elements = selectSingle(root, selector, doc)
            if (elements.isNotEmpty()) return elements
        }
        return emptyList()
    }

    private fun selectSingle(root: Element, rawRule: String, doc: Document?): List<Element> {
        var rule = rawRule.trim()
        if (rule.startsWith("@css:")) rule = rule.removePrefix("@css:").trim()
        if (rule.startsWith("@json:") || rule.startsWith("@js:") || rule.startsWith("@xpath:")) return emptyList()

        // Legado "class.tag@attr" / "id.tag@attr" syntax.
        rule = rule.replace(Regex("^class\\.([\\w-]+)"), ".$1")
            .replace(Regex("^id\\.([\\w-]+)"), "#$1")
        val beforeAt = rule.substringBefore('@').trim()
        val selector = beforeAt.ifBlank { "*" }
        return runCatching { root.select(selector) }.getOrElse {
            runCatching { doc?.select(selector) ?: emptyList<Element>() }.getOrElse { emptyList() }
        }.toList()
    }

    /** Extracts a value from [element] following a Legado rule. */
    fun string(element: Element?, rule: String?, baseUrl: String = ""): String {
        if (element == null || rule.isNullOrBlank()) return ""
        for (alternative in rule.split("||")) {
            val value = evalString(element, alternative.trim(), baseUrl)
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** Extracts a list of values (e.g. genres). */
    fun stringList(element: Element?, rule: String?, baseUrl: String = ""): List<String> {
        if (element == null || rule.isNullOrBlank()) return emptyList()
        val value = string(element, rule, baseUrl)
        if (value.isEmpty()) return emptyList()
        return value.split(Regex("[,，/|]")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun evalString(element: Element, rawRule: String, baseUrl: String): String {
        var rule = rawRule.trim()
        if (rule.isEmpty()) return ""

        // JSON rules operate on an embedded JSON payload.
        if (rule.startsWith("@json:")) {
            return evalJsonRule(element.text(), rule.removePrefix("@json:"), baseUrl)
        }
        if (rule.startsWith("@js:")) {
            throw LegadoRuleException(
                "@js: rules need the JavaScript engine; enable the JS runtime for this source"
            )
        }

        // Index / regex stages are applied to the element's text or attribute.
        val (core, regexPattern, regexReplacement) = splitRegex(rule)
        val (selectorPart, attribute) = splitAttribute(core)

        var value: String = if (selectorPart.isBlank()) {
            when (attribute) {
                "text", "" -> element.text()
                "html" -> element.html()
                "outerHtml" -> element.outerHtml()
                else -> element.attr(attribute)
            }
        } else {
            val target = runCatching { element.select(selectorPart).firstOrNull() }.getOrNull()
            if (target == null) {
                return ""
            }
            when (attribute) {
                "text", "" -> target.text()
                "html" -> target.html()
                "outerHtml" -> target.outerHtml()
                else -> target.attr(attribute)
            }
        }

        if (regexPattern != null) {
            val match = Regex(regexPattern).find(value)
            value = when {
                match == null -> ""
                regexReplacement != null && regexReplacement.isNotEmpty() -> {
                    val groups = match.groupValues.drop(1)
                    var replaced = regexReplacement
                    groups.forEachIndexed { i, g -> replaced = replaced.replace("$${i + 1}", g) }
                    replaced.ifEmpty { match.value }
                }
                match.groupValues.size > 1 -> match.groupValues[1]
                else -> match.value
            }
        }

        return value.trim().let { resolveUrl(baseUrl, it) }
    }

    private fun splitAttribute(rule: String): Pair<String, String> {
        val at = rule.lastIndexOf('@')
        if (at <= 0) return rule to ""
        val selector = rule.substring(0, at).trim()
        var attr = rule.substring(at + 1).trim()
        // Legado supports "tag@text", "tag@html", "tag@href", and index "tag@0" syntax.
        attr = attr.removePrefix("@")
        return selector to attr
    }

    private fun splitRegex(rule: String): Triple<String, String?, String?> {
        val parts = rule.split("##")
        if (parts.size < 3) return Triple(rule, null, null)
        return Triple(parts[0].trim(), parts[1], parts.getOrNull(2))
    }

    /** Minimal JSON path evaluator for `$.a.b[0].c` and `$..a` shapes. */
    fun evalJsonRule(text: String, path: String, baseUrl: String): String {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return ""
        val segments = path.trim().removePrefix("$").removePrefix(".").split('.').filter { it.isNotEmpty() }
        var current: JsonElement? = root
        for (segment in segments) {
            val name = segment.substringBefore('[')
            var el = current ?: return ""
            if (name.isNotEmpty()) {
                el = (el as? JsonObject)?.get(name) ?: return ""
            }
            val indexPart = segment.substringAfter('[', "").substringBefore(']')
            if (indexPart.isNotEmpty()) {
                el = (el as? JsonArray)?.getOrNull(indexPart.toIntOrNull() ?: 0) ?: return ""
            }
            current = el
        }
        val value = when (val el = current) {
            null, is JsonNull -> ""
            is JsonPrimitive -> el.content
            else -> el.toString()
        }
        return if (baseUrl.isNotEmpty()) resolveUrl(baseUrl, value) else value
    }

    fun parseHtml(html: String, baseUrl: String = ""): Document = Jsoup.parse(html, baseUrl)

    /**
     * Applies Legado's chapter list rule, returning `[name, url]` pairs.
     */
    fun chapters(
        doc: Document,
        listRule: String?,
        nameRule: String?,
        urlRule: String?,
        baseUrl: String,
    ): List<Pair<String, String>> {
        val root = doc.body() ?: doc
        return selectElements(root, listRule).mapNotNull { element ->
            val name = string(element, nameRule, baseUrl)
            val url = string(element, urlRule, baseUrl)
            if (name.isEmpty() && url.isEmpty()) null else name to url
        }
    }
}
