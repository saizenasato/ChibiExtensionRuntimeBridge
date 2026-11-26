# ChibiExtensionRuntimeBridge

A **native Android/Kotlin** re-implementation of the AnymeX *Extension Runtime Bridge*.
It replaces the Flutter/Dart bridge with a reusable Kotlin SDK plus a separately
installable, headless **runtime host APK** that loads and executes third-party
extension binaries in a sandboxed class loader.

* Runtime host: `com.anymex.runtimehost` (kept for extension binary compatibility)
* SDK: `io.chibi.bridge.sdk.ChibiRuntime` (suspend API + `Flow`, shipped as an AAR)
* IPC: AIDL binder service `io.chibi.bridge.action.RUNTIME_HOST` carrying JSON
  `BridgeRequest` / `BridgeResponse` payloads
* Ten backends: Aniyomi, CloudStream, Mangayomi, Sora, Kotatsu, Legado, LNReader,
  Tsundoku, iReader, TorrServer

No Flutter, no Dart and no React Native anywhere in the build.

## Modules

| Module | Type | Purpose |
| --- | --- | --- |
| `bridge-models` | library | Unified models (`Media`, `Episode`, `Video`, `Pages`, `Source`, `SourcePreference`), error codes, IPC envelope, legacy JSON helpers |
| `bridge-api` | library | AIDL interface, protocol constants, shared JSON serializers |
| `backend-api` | library | `ExtensionBackend`, `ExtensionSource`, `BackendContext`, `BackendRegistry`, `TachiyomiEngine`, installer bridge |
| `runtime-core` | library | Logging, activity/host resolution helpers |
| `runtime-loader` | library | `DexClassLoader` based extension loader, host discovery |
| `runtime-host` | application | Headless host APK: binder service, dispatch table, JVM-backed Aniyomi / CloudStream / Kotatsu adapters |
| `backends/*` | library | One module per ecosystem (`aniyomi`, `cloudstream`, `kotatsu` + `kotatsu-parsers`, `legado`, `lnreader`, `tsundoku`, `ireader`, `sora`, `mangayomi`) |
| `javascript-runtime` | library | Rhino based JS sandbox with DOM/`fetch` shims for JS extension formats |
| `torrserver` | library | TorrServer REST addon client |
| `bridge-sdk` | library | Public Kotlin SDK (`ChibiRuntime`) |
| `sample-host` | application | Minimal consumer app proving the SDK integrates |

## Using the SDK

```kotlin
val runtime = ChibiRuntime(context)          // discovers the installed host APK
val status = runtime.connect()               // binds the AIDL service
runtime.initialize()

val sources = runtime.installedSources("aniyomi", ItemType.ANIME)
val pages   = runtime.search("aniyomi", sources.first().id, "one piece", page = 1)
val detail  = runtime.detail("aniyomi", sources.first().id, pages.items.first().url)
val videos  = runtime.videos("aniyomi", sources.first().id, detail.episodes.first())
val stream  = runtime.videoStream("aniyomi", sources.first().id, videos.first())
```

`ChibiRuntime.state` is a `StateFlow<RuntimeState>`; every call is `suspend` and
throws `ChibiBridgeException` carrying a `BridgeError` code.

The host APK must be installed on the device. `installedHosts()`,
`hostInstallIntent(apk)` and `stageHostApkFromAssets(name)` help an app ship and
install it (the SDK manifest declares the required `<queries>` and a
`FileProvider` for the installer handoff).

## Build

```bash
./gradlew :runtime-host:assembleDebug :bridge-sdk:assembleRelease :sample-host:assembleDebug
```

Requires JDK 17 and Android SDK platform 36 (`compileSdk 36`, `minSdk 26`).
CI builds and tests everything on GitHub Actions; see
`.github/workflows/artifact.yml` (artifacts only) and
`.github/workflows/release.yml` (tagged release of the bridge app).

To produce signed release APKs, add repository secrets
`CHIBI_KEYSTORE_BASE64`, `CHIBI_KEYSTORE_PASSWORD`, `CHIBI_KEY_ALIAS`,
`CHIBI_KEY_PASSWORD`. Without them release artifacts are unsigned.

## Documentation

* [`docs/AUDIT.md`](docs/AUDIT.md) – upstream audit, licences, reusable code
* [`docs/MIGRATION_PLAN.md`](docs/MIGRATION_PLAN.md) – Dart → Kotlin mapping
* [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) – process model, IPC, loader
* [`docs/BACKENDS.md`](docs/BACKENDS.md) – per-backend mechanism and limitations
* [`docs/LICENSE_COMPLIANCE.md`](docs/LICENSE_COMPLIANCE.md) – copyleft obligations
* [`docs/TESTING.md`](docs/TESTING.md) – what CI verifies, what is still unverified

## Licence

GPL-3.0. Portions are derived from the AnymeX runtime bridge (UPL / GPL-3.0 +
public source) and from Aniyomi, CloudStream, Kotatsu, Legado, LNReader,
Mangayomi, iReader, Sora, Tsundoku and TorrServer. See `NOTICE`.
