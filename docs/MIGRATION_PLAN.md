# Migration plan: Dart/Flutter → Kotlin

## Call path

| Layer | Upstream | This project |
| --- | --- | --- |
| UI / app | Flutter | any Android app using `bridge-sdk` |
| Transport | `MethodChannel("anymex/runtime")` | AIDL `IChibiRuntime` binder, JSON payloads |
| Facade | `RuntimeBridge.kt` (object, single dispatcher) | same class, kept in `runtime-host` |
| Loader | `RuntimeLoader.kt` | `runtime-loader/RuntimeLoader.kt` (unchanged mechanics) |
| Models | Dart `DMedia`, `DEpisode`, `Page(s)`, `Video`, `Source`, `SourcePreference` | `bridge-models/Models.kt` (kotlinx.serialization) |

## Model mapping

| Dart | Kotlin (`bridge-models`) | Notes |
| --- | --- | --- |
| `DMedia` | `Media` | `url`, `title`, `cover`, `description`, `status`, `genres`, `author`, `artist`, `episodes` |
| `DEpisode` | `Episode` | `url`, `name`, `number`, `season`, `scanlator`, `dateUpload` |
| `Page` / `Pages` | `Page` / `Pages` | `page` is 1-based, as upstream |
| `Video` | `Video` | `url`, `quality`, `headers`, `subtitles` |
| `Source` | `Source` | `id`, `name`, `lang`, `version`, `iconUrl`, `baseUrl`, `isInstalled` |
| `SourceParams` | `SourceParams` | preserved verbatim as JSON for backend use |
| `SourcePreference` | `SourcePreference` | `type` + `value` map |

## Method mapping

`SourceMethods` keys (`initialize`, `sources.list`, `sources.install`,
`sources.uninstall`, `sources.update`, `sources.repositories.*`, `popular`,
`latest`, `search`, `detail`, `videos`, `videoStream`, `novel.*`, `prefs.*`)
map one-to-one onto `BridgeRequest.method` strings in `bridge-api/Methods.kt`.
Each backend also exposes `torrserver.*` RPCs for the addon client.

## What was replaced, not ported

* `MethodChannel` plumbing and the Dart `Extensions` class → AIDL service +
  `ChibiRuntime` suspend API.
* `quickjs` host-callback RPC (Flutter side drove JS callbacks) → an in-process
  Rhino engine (`javascript-runtime`) with synchronous host callbacks and an
  `AsyncNormalizer` that strips `async`/`await`.
* Dart-side `LegadoRuleEngine` → Kotlin `LegadoRuleEngine` (Jsoup).

## Deliberate deviations

1. Aniyomi, CloudStream and Kotatsu adapter classes live in `runtime-host`
   (`JvmBackends.kt`) instead of their modules, because they wrap the
   process-wide `RuntimeBridge` and would otherwise create a module cycle.
2. The SDK is a separate AAR (`bridge-sdk`) rather than a plugin, so other
   Kotlin apps can consume the bridge without Flutter.
3. The Kotatsu parser module keeps its own Gradle project (`:backends:kotatsu-parsers`)
   to preserve the upstream `kotatsu_ext` source set layout.
