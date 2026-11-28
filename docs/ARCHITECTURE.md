# Architecture

```
client app ── bridge-sdk (ChibiRuntime) ──┐
                                          │ AIDL binder, JSON BridgeRequest/Response
                                          ▼
                       runtime-host APK (io.chibi.runtimehost)
                       ├── ChibiRuntimeService  (IChibiRuntime.Stub)
                       ├── HostBridge           (method dispatch, BackendRegistry)
                       ├── JvmBackends          (Aniyomi / CloudStream / Kotatsu adapters)
                       ├── HostTachiyomiEngine  (Tsundoku, iReader, novel routes)
                       ├── RuntimeBridge        (upstream facade, JVM dispatcher)
                       └── RuntimeLoader        (DexClassLoader + native lib extraction)
                                          │
      ┌───────────────┬───────────────────┼────────────────┬──────────────┐
      ▼               ▼                   ▼                ▼              ▼
 backends/aniyomi backends/cloudstream backends/kotatsu backends/legado  backends/{sora,
 (Dex-loaded APK) (Dex-loaded APK)      (parsers)        (Jsoup rules)    mangayomi} (JS)
```

## Process model

The host runs in its own process-independent package and is started by binding
the exported service; it has **no launcher activity**. Clients bind with:

```kotlin
Intent("io.chibi.bridge.action.RUNTIME_HOST").setPackage("io.chibi.runtimehost")
```

Binder calls are synchronous, so SDK methods run on `Dispatchers.IO` and expose
`suspend` functions. Streaming (`videoStream`) uses
`IChibiStreamCallback` for progressive URL resolution.

## Extension loading

`RuntimeLoader` creates a `DexClassLoader` over the extension APK's
`classes.dex`, extracts `lib/` payloads to the host's cache directory, and
instantiates the extension's factory entry point (`SourceFactory` for Aniyomi,
`CloudstreamPlugin` for CloudStream). Kotatsu parsers are compiled into
`:backends:kotatsu-parsers`. Each extension gets its own class loader so two
extensions with the same classes cannot collide.

## Backend contract

`backend-api` defines:

```kotlin
interface ExtensionBackend {
    val id: String
    suspend fun status(ctx: BackendContext): BackendStatus
    suspend fun sources(ctx: BackendContext, type: ItemType, installedOnly: Boolean): List<Source>
    suspend fun popular(...); suspend fun latest(...); suspend fun search(...)
    suspend fun detail(...); suspend fun videos(...); suspend fun videoStream(...)
    suspend fun install(...); suspend fun uninstall(...); suspend fun update(...)
    suspend fun preferences(...)
}
```

`HostBridge` routes every `BridgeRequest` by `backendId` through the registry and
serialises failures into `BridgeResponse.error` with a stable `BridgeError` code
(`HOST_NOT_INSTALLED`, `BACKEND_UNAVAILABLE`, `SOURCE_NOT_INSTALLED`,
`UNSUPPORTED`, `NETWORK`, `PARSE`, `INTERNAL`).

## JS runtime

`javascript-runtime` embeds Rhino with:

* console, `fetch`/`XMLHttpRequest` shims bridged to OkHttp,
* `document`/`DOMParser`/`Element` shims over Jsoup,
* `AsyncNormalizer`, which rewrites `async`/`await` to synchronous calls.

This covers the common Sora/LNReader/Mangayomi script shapes but **not** real
promise semantics; see `BACKENDS.md`.
