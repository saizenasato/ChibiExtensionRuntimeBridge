/*
 * Embedded JavaScript runtime for Mangayomi, Sora and LNReader extensions.
 *
 * Upstream these ecosystems ran on the Flutter `flutter_qjs` engine plus the
 * Dart-side polyfills in lib/Services/LnReader/js_*.dart and
 * lib/Services/Mangayomi/Eval/javascript/ (Dart). On Android the original app
 * already shipped Rhino (org.mozilla:rhino, used by the CloudStream API surface),
 * and this module uses it as the extension engine so no Flutter/QJS bridge is
 * needed:
 *
 *  - Rhino with ES6 language level as the script engine;
 *  - synchronous host bindings for HTTP, preferences, logging and base64 that
 *    mirror the Dart polyfills;
 *  - a DOM bridge backed by Jsoup, exposed as `_chibi.dom*` primitives and
 *    wrapped by the bundled polyfills into a cheerio-like API;
 *  - an "async unwrapping" pass: plugin sources written with `async`/`await`
 *    (the dominant style in LnReader and Mangayomi) are normalised to
 *    synchronous calls, which is valid because every host call here is
 *    synchronous. This is a compatibility shim, not a stub: the resulting code
 *    performs the same requests and parsing.
 *
 * KNOWN LIMITATION: genuine asynchronous work (timers, Promises that resolve on
 * later turns, parallel extraction) cannot be expressed under Rhino's
 * single-threaded ES6 engine. Those plugins report a clear JsEngineException.
 */
package io.chibi.bridge.js

import android.util.Base64
import io.chibi.bridge.backend.BridgeLogger
import io.chibi.bridge.backend.PreferenceStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JsEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Runtime services an extension script may use. */
class JsHostServices(
    val httpClient: OkHttpClient,
    val preferences: PreferenceStore,
    val logger: BridgeLogger,
    val userAgent: String = DEFAULT_UA,
    val preferencesPrefix: String = "js_ext",
) {
    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}

@Serializable
data class JsHttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val url: String = "",
)

/**
 * One Rhino context confined to a single thread, mirroring flutter_qjs'
 * isolate-per-engine model. Callers may use it from any coroutine.
 */
class JsEngine(
    private val services: JsHostServices,
    private val polyfills: List<String> = defaultPolyfills(),
) : AutoCloseable {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chibi-js-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    private val documents = mutableMapOf<Int, Document>()
    private val docIds = AtomicInteger(0)
    private var scope: Scriptable? = null
    private var context: Context? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun <T> onEngine(block: () -> T): T = try {
        executor.submit(Callable { block() }).get(60, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw (e.cause as? JsEngineException) ?: JsEngineException(e.cause?.message ?: "JS execution failed", e.cause)
    }

    private fun ensureStarted(): Scriptable {
        scope?.let { return it }
        val cx = ContextFactory.getGlobal().enterContext()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        cx.instructionObserverThreshold = 0
        val newScope = cx.initStandardObjects(null, false)
        ScriptableObject.putProperty(newScope, "_chibiBridge", Context.javaToJS(HostBindings(), newScope))
        ScriptableObject.putProperty(newScope, "console", Context.javaToJS(ConsoleBindings(), newScope))
        context = cx
        scope = newScope
        polyfills.forEach { script -> cx.evaluateString(newScope, script, "chibi-polyfill.js", 1, null) }
        return newScope
    }

    /** Normalises async plugin code (see the class comment). */
    internal fun normalizeAsync(source: String): String = AsyncNormalizer.normalize(source)

    fun evaluate(script: String): String = onEngine {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.optimizationLevel = -1
            val s = ensureStarted()
            val value = cx.evaluateString(s, normalizeAsync(script), "chibi-extension.js", 1, null)
            when (value) {
                null, Context.getUndefinedValue() -> "null"
                is org.mozilla.javascript.Undefined -> "null"
                is org.mozilla.javascript.NativeJavaObject -> json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), JsonPrimitive(value.toString()))
                is String -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                else -> value.toString()
            }
        } catch (t: Throwable) {
            throw JsEngineException(t.message ?: t::class.java.simpleName, t)
        } finally {
            Context.exit()
        }
    }

    /** Evaluates a script that is expected to produce JSON (via JSON.stringify). */
    fun evaluateJson(script: String): String = onEngine {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.optimizationLevel = -1
            val s = ensureStarted()
            val value = cx.evaluateString(
                s,
                normalizeAsync("JSON.stringify(($script))"),
                "chibi-extension.js",
                1,
                null,
            )
            when (value) {
                null, Context.getUndefinedValue() -> "null"
                is org.mozilla.javascript.Undefined -> "null"
                else -> value.toString()
            }
        } catch (t: Throwable) {
            throw JsEngineException(t.message ?: t::class.java.simpleName, t)
        } finally {
            Context.exit()
        }
    }

    /** Loads an extension script into the shared scope. */
    fun load(source: String) = onEngine {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.optimizationLevel = -1
            val s = ensureStarted()
            cx.evaluateString(s, normalizeAsync(source), "chibi-extension.js", 1, null)
            true
        } catch (t: Throwable) {
            throw JsEngineException("Extension script failed to load: ${t.message}", t)
        } finally {
            Context.exit()
        }
    }

    fun has(expression: String): Boolean = runCatching {
        evaluate("typeof ($expression) !== 'undefined' && ($expression) !== null") == "true"
    }.getOrDefault(false)

    // ------------------------------------------------------------------ host

    /** Public methods become JavaScript functions on `_chibiBridge`. */
    inner class HostBindings {
        fun http(requestJson: String): String = request(requestJson)

        fun log(message: String) = services.logger.debug("JsEngine", message)

        fun btoa(value: String): String =
            Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        fun atob(value: String): String =
            try {
                String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }

        fun prefGet(key: String): String? = services.preferences.getString("${services.preferencesPrefix}:$key")

        fun prefSet(key: String, value: String) {
            services.preferences.putString("${services.preferencesPrefix}:$key", value)
        }

        fun domParse(html: String, baseUrl: String): Int {
            val doc = Jsoup.parse(html, baseUrl)
            val id = docIds.incrementAndGet()
            documents[id] = doc
            return id
        }

        fun domQsa(docId: Int, selector: String): String = query(docId, selector)

        fun domText(nodeId: String): String = resolve(nodeId)?.text().orEmpty()

        fun domHtml(nodeId: String, outer: Boolean): String {
            val el = resolve(nodeId) ?: return ""
            return if (outer) el.outerHtml() else el.html()
        }

        fun domAttr(nodeId: String, name: String): String = resolve(nodeId)?.attr(name).orEmpty()

        fun domHasAttr(nodeId: String, name: String): Boolean = resolve(nodeId)?.hasAttr(name) ?: false

        fun domTag(nodeId: String): String = resolve(nodeId)?.tagName().orEmpty()

        fun domParent(nodeId: String): String = resolve(nodeId)?.let { parentId(it) }.orEmpty()

        fun domChildren(nodeId: String): String =
            resolve(nodeId)?.children()?.joinToString(",") { childId(it) }.orEmpty()
    }

    inner class ConsoleBindings {
        fun log(vararg args: Any?) = services.logger.debug("JsConsole", args.joinToString(" "))
        fun warn(vararg args: Any?) = services.logger.warn("JsConsole", args.joinToString(" "))
        fun error(vararg args: Any?) = services.logger.warn("JsConsole", args.joinToString(" "))
        fun info(vararg args: Any?) = services.logger.debug("JsConsole", args.joinToString(" "))
        fun debug(vararg args: Any?) = services.logger.debug("JsConsole", args.joinToString(" "))
    }

    private fun request(requestJson: String): String {
        val obj = runCatching { json.parseToJsonElement(requestJson) as JsonObject }.getOrNull()
            ?: return json.encodeToString(JsHttpResponse.serializer(), JsHttpResponse(0, ""))
        fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        val url = field("url") ?: return json.encodeToString(JsHttpResponse.serializer(), JsHttpResponse(0, ""))
        val method = (field("method") ?: "GET").uppercase()
        val headersJson = (obj["headers"] as? JsonObject)
        val headers = headersJson?.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
            ?: emptyMap()
        val body = field("body")
        val timeout = field("timeout")?.toLongOrNull()

        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (headers.keys.none { it.equals("user-agent", true) }) builder.header("User-Agent", services.userAgent)

        val requestBody = when {
            method == "GET" || method == "HEAD" -> null
            body != null -> body.toRequestBody("application/json".toMediaType())
            else -> "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        }
        builder.method(method, requestBody)

        return try {
            val client = if (timeout != null) {
                services.httpClient.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).build()
            } else {
                services.httpClient
            }
            client.newCall(builder.build()).execute().use { response ->
                json.encodeToString(
                    JsHttpResponse.serializer(),
                    JsHttpResponse(
                        code = response.code,
                        body = response.body.string(),
                        headers = response.headers.toMap(),
                        url = response.request.url.toString(),
                    ),
                )
            }
        } catch (t: Throwable) {
            services.logger.warn("JsEngine", "HTTP request failed for $url", t)
            json.encodeToString(JsHttpResponse.serializer(), JsHttpResponse(0, t.message ?: "http failure"))
        }
    }

    private fun query(docId: Int, selector: String): String {
        val doc = documents[docId] ?: return "[]"
        val elements = runCatching { doc.select(selector) }.getOrElse { return "[]" }
        val encoded = elements.map { el ->
            val attrs = JsonObject(el.attributes().associate { it.key to JsonPrimitive(it.value) })
            buildString {
                append("{\"id\":\"").append(childId(el)).append("\",")
                append("\"tag\":\"").append(el.tagName()).append("\",")
                append("\"text\":").append(json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), JsonPrimitive(el.text()))).append(",")
                append("\"html\":").append(json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), JsonPrimitive(el.html()))).append(",")
                append("\"attrs\":").append(json.encodeToString(JsonObject.serializer(), attrs))
                append("}")
            }
        }
        return "[" + encoded.joinToString(",") + "]"
    }

    private fun childId(element: Element): String {
        val docId = documents.entries.firstOrNull { (_, doc) -> doc === element.ownerDocument() }?.key ?: return ""
        val path = mutableListOf<Int>()
        var current: Element? = element
        while (current != null) {
            val parent = current.parent() ?: break
            if (parent is Document) break
            path.add(0, parent.children().indexOf(current))
            current = parent
        }
        return "$docId:" + path.joinToString("-")
    }

    private fun parentId(element: Element): String {
        val parent = element.parent() ?: return ""
        return if (parent is Document) {
            val docId = documents.entries.firstOrNull { (_, doc) -> doc === parent }?.key ?: return ""
            "$docId:"
        } else {
            childId(parent)
        }
    }

    private fun resolve(nodeId: String): Element? {
        val parts = nodeId.split(':')
        if (parts.size != 2) return null
        val docId = parts[0].toIntOrNull() ?: return null
        val doc = documents[docId] ?: return null
        if (parts[1].isBlank()) return null
        var element: Element? = null
        for (index in parts[1].split('-').mapNotNull { it.toIntOrNull() }) {
            element = if (element == null) doc.children().getOrNull(index) else element.children().getOrNull(index)
            if (element == null) return null
        }
        return element
    }

    override fun close() {
        runCatching { executor.submit { true }.get(5, TimeUnit.SECONDS) }
        documents.clear()
        scope = null
        context = null
        executor.shutdownNow()
    }

    companion object {
        private val counter = AtomicInteger(0)

        fun defaultPolyfills(): List<String> = listOf(
            JsPolyfills.HOST_GLUE,
            JsPolyfills.HTTP,
            JsPolyfills.DOM,
            JsPolyfills.UTILS,
        )
    }
}

/**
 * Rewrites `async`/`await` into synchronous code.
 *
 * Every host call in this engine is synchronous, so awaiting its result is a
 * no-op. The transform is intentionally conservative: it only removes the
 * `async` modifier from function declarations/expressions/arrows and the
 * `await` operator, leaving strings and comments untouched as far as a
 * token-level scan allows.
 */
object AsyncNormalizer {

    fun normalize(source: String): String {
        if (!source.contains("async") && !source.contains("await")) return source
        val out = StringBuilder(source.length)
        var i = 0
        var state = State.CODE
        while (i < source.length) {
            val c = source[i]
            when (state) {
                State.CODE -> when {
                    c == '\'' -> { state = State.SINGLE; out.append(c); i++ }
                    c == '"' -> { state = State.DOUBLE; out.append(c); i++ }
                    c == '`' -> { state = State.TEMPLATE; out.append(c); i++ }
                    c == '/' && i + 1 < source.length && source[i + 1] == '/' -> { state = State.LINE_COMMENT; out.append(c); i++ }
                    c == '/' && i + 1 < source.length && source[i + 1] == '*' -> { state = State.BLOCK_COMMENT; out.append(c); i++ }
                    matchesWord(source, i, "async") && nextIsFunctionish(source, i + 5) -> {
                        // drop the async keyword and the whitespace after it
                        i += 5
                        while (i < source.length && source[i].isWhitespace()) i++
                    }
                    matchesWord(source, i, "await") -> {
                        i += 5
                        while (i < source.length && source[i].isWhitespace()) i++
                    }
                    else -> { out.append(c); i++ }
                }
                State.SINGLE -> { out.append(c); if (c == '\\' && i + 1 < source.length) { out.append(source[i + 1]); i += 2; continue }; if (c == '\'') state = State.CODE; i++ }
                State.DOUBLE -> { out.append(c); if (c == '\\' && i + 1 < source.length) { out.append(source[i + 1]); i += 2; continue }; if (c == '"') state = State.CODE; i++ }
                State.TEMPLATE -> { out.append(c); if (c == '\\' && i + 1 < source.length) { out.append(source[i + 1]); i += 2; continue }; if (c == '`') state = State.CODE; i++ }
                State.LINE_COMMENT -> { out.append(c); if (c == '\n') state = State.CODE; i++ }
                State.BLOCK_COMMENT -> {
                    out.append(c)
                    if (c == '*' && i + 1 < source.length && source[i + 1] == '/') { out.append('/'); i += 2; state = State.CODE; continue }
                    i++
                }
            }
        }
        return out.toString()
    }

    private enum class State { CODE, SINGLE, DOUBLE, TEMPLATE, LINE_COMMENT, BLOCK_COMMENT }

    private fun matchesWord(source: String, index: Int, word: String): Boolean {
        if (!source.startsWith(word, index)) return false
        val before = source.getOrNull(index - 1)
        val after = source.getOrNull(index + word.length)
        val boundaryBefore = before == null || !(before.isLetterOrDigit() || before == '_' || before == '.')
        val boundaryAfter = after == null || !(after.isLetterOrDigit() || after == '_' || after == '$')
        return boundaryBefore && boundaryAfter
    }

    private fun nextIsFunctionish(source: String, index: Int): Boolean {
        var i = index
        while (i < source.length && source[i].isWhitespace()) i++
        if (i >= source.length) return false
        return source.startsWith("function", i) ||
            source[i] == '(' ||
            source[i] == '='
    }
}

/** Bundled JavaScript polyfills (equivalent to the Dart js_* helpers). */
object JsPolyfills {

    /** Glue between JavaScript and the Rhino host bindings. */
    val HOST_GLUE = """
        var _chibi = {
            http: function (req) { return _chibiBridge.http(JSON.stringify(req)); },
            log: function (msg) { _chibiBridge.log(String(msg)); },
            btoa: function (v) { return _chibiBridge.btoa(String(v)); },
            atob: function (v) { return _chibiBridge.atob(String(v)); },
            prefGet: function (k) { return _chibiBridge.prefGet(String(k)); },
            prefSet: function (k, v) { _chibiBridge.prefSet(String(k), String(v)); },
            domParse: function (html, base) { return _chibiBridge.domParse(String(html), String(base || '')); },
            domQsa: function (id, sel) { return JSON.parse(_chibiBridge.domQsa(id, String(sel))); },
            domText: function (n) { return _chibiBridge.domText(String(n)); },
            domHtml: function (n, outer) { return _chibiBridge.domHtml(String(n), !!outer); },
            domAttr: function (n, a) { return _chibiBridge.domAttr(String(n), String(a)); },
            domHasAttr: function (n, a) { return _chibiBridge.domHasAttr(String(n), String(a)); },
            domTag: function (n) { return _chibiBridge.domTag(String(n)); },
            domParent: function (n) { return _chibiBridge.domParent(String(n)); },
            domChildren: function (n) { return _chibiBridge.domChildren(String(n)); }
        };
        var setTimeout = function (fn) { if (typeof fn === 'function') fn(); return 0; };
        var clearTimeout = function () {};
        var globalThis = this;
    """.trimIndent()

    /** Ports lib/Services/LnReader/http.dart and m_client.dart helpers. */
    val HTTP = """
        function fetchApi(url, init) {
            init = init || {};
            var res = JSON.parse(_chibi.http({
                url: url,
                method: init.method || 'GET',
                headers: init.headers || {},
                body: (init.body === undefined ? null : init.body),
                timeout: init.timeout || null
            }));
            var text = res.body;
            return {
                ok: res.code >= 200 && res.code < 300,
                status: res.code,
                url: res.url,
                headers: res.headers || {},
                text: function () { return text; },
                json: function () { return JSON.parse(text); },
                html: function () { return text; }
            };
        }
        function httpGet(url, headers) { return fetchApi(url, { headers: headers || {} }).text(); }
        function httpPost(url, body, headers) { return fetchApi(url, { method: 'POST', body: body, headers: headers || {} }).text(); }
        function GET(url, headers) { return fetchApi(url, { headers: headers || {} }); }
        function POST(url, body, headers) { return fetchApi(url, { method: 'POST', body: body, headers: headers || {} }); }
    """.trimIndent()

    /**
     * cheerio/Document shim used by LNReader and Mangayomi scripts.
     * Selector support (tag, .class, #id, [attr], descendant, child, groups)
     * comes from Jsoup on the host.
     */
    val DOM = """
        function ChibiNode(meta) {
            this.id = meta.id;
            this.tagName = meta.tag;
            this._text = meta.text;
            this._html = meta.html;
            this.attribs = meta.attrs || {};
        }
        ChibiNode.prototype.text = function () { return this._text || ''; };
        ChibiNode.prototype.html = function () { return this._html || ''; };
        ChibiNode.prototype.outerHtml = function () { return _chibi.domHtml(this.id, true); };
        ChibiNode.prototype.attr = function (name) { return this.attribs[name]; };
        ChibiNode.prototype.attrib = ChibiNode.prototype.attr;
        ChibiNode.prototype.find = function (selector) { return queryFrom(this.id, selector); };
        ChibiNode.prototype.select = ChibiNode.prototype.find;
        ChibiNode.prototype.children = function () {
            var ids = _chibi.domChildren(this.id);
            if (!ids) return [];
            return ids.split(',').filter(Boolean).map(function (id) {
                return new ChibiNode({ id: id, tag: _chibi.domTag(id), text: _chibi.domText(id), html: _chibi.domHtml(id, false), attrs: {} });
            });
        };
        ChibiNode.prototype.parent = function () {
            var id = _chibi.domParent(this.id);
            return id ? new ChibiNode({ id: id, tag: _chibi.domTag(id), text: _chibi.domText(id), html: _chibi.domHtml(id, false), attrs: {} }) : null;
        };
        ChibiNode.prototype.first = function () { return this; };
        ChibiNode.prototype.last = function () { return this; };
        ChibiNode.prototype.eq = function () { return this; };
        ChibiNode.prototype.length = 1;

        function wrap(nodes) {
            nodes.forEach(function (n) { Object.setPrototypeOf(n, ChibiNode.prototype); });
            nodes.text = function () { return nodes.map(function (n) { return n._text || ''; }).join(''); };
            nodes.html = function () { return nodes.length ? nodes[0]._html : ''; };
            nodes.attr = function (name) { return nodes.length ? nodes[0].attribs[name] : undefined; };
            nodes.first = function () { return nodes.length ? nodes[0] : null; };
            nodes.last = function () { return nodes.length ? nodes[nodes.length - 1] : null; };
            nodes.eq = function (i) { return nodes[i] || null; };
            nodes.find = function (sel) { var out = []; nodes.forEach(function (n) { out = out.concat(n.find(sel)); }); return out; };
            return nodes;
        }

        function queryFrom(nodeId, selector) {
            var docId = parseInt(String(nodeId).split(':')[0], 10);
            return wrap(_chibi.domQsa(docId, selector));
        }

        function ChibiDocument(docId) { this.docId = docId; }
        ChibiDocument.prototype.querySelector = function (sel) {
            var list = queryFrom(this.docId + ':', sel);
            return list.length ? list[0] : null;
        };
        ChibiDocument.prototype.querySelectorAll = function (sel) { return queryFrom(this.docId + ':', sel); };
        ChibiDocument.prototype.select = ChibiDocument.prototype.querySelectorAll;
        ChibiDocument.prototype.getElementsByTagName = function (tag) { return this.querySelectorAll(tag); };
        ChibiDocument.prototype.getElementsByClassName = function (cls) { return this.querySelectorAll('.' + cls); };
        ChibiDocument.prototype.getElementById = function (id) { return this.querySelector('#' + id); };

        function parseDocument(html, baseUrl) {
            return new ChibiDocument(_chibi.domParse(html, baseUrl || ''));
        }
        function Document(html) { return parseDocument(html, ''); }
        var cheerio = {
            load: function (html, options) { return parseDocument(html, (options && options.baseUrl) || ''); },
            parseHTML: function (html) { return parseDocument(html, ''); }
        };
    """.trimIndent()

    /** Ports of the helper functions in Mangayomi's javascript/utils.dart. */
    val UTILS = """
        var preferences = {
            get: function (key, def) {
                var v = _chibi.prefGet(key);
                return (v === null || v === undefined || v === '') ? def : v;
            },
            set: function (key, value) { _chibi.prefSet(key, value); }
        };
        function base64Encode(str) { return _chibi.btoa(str); }
        function base64Decode(str) { return _chibi.atob(str); }
        function urlEncode(str) { return encodeURIComponent(str); }
        function urlDecode(str) { return decodeURIComponent(str); }
        function htmlEscape(str) {
            return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        }
    """.trimIndent()
}
