# Upstream audit

Before any code was written, the reference implementations were cloned locally
into `_ref/` (git-ignored, not shipped) and read:

| Reference | What was audited |
| --- | --- |
| `AnymeXExtensionRuntimeBridge` (Flutter host + Android `RuntimeBridges/Android`) | `RuntimeBridge.kt` public API, plugin dispatch, `RuntimeLoader.kt` (`DexClassLoader` + `libs` extraction), Dart models (`DMedia`, `DEpisode`, `Page`, `Pages`, `Video`, `Source`, `SourceParams`, `SourcePreference`), `SourceMethods`, `Extensions.dart`, `LegadoRuleEngine.dart` |
| `KirokuExtensionRuntimeBridge` | Alternative SDK/IPC design: `KirokuRuntimeBridge.kt` (single facade), `bridge-ipc` `Protocol.kt` / `Models.kt` (JSON protocol without AIDL) |

## Findings that shaped the port

1. **The Android host already is Kotlin.** The Flutter layer only serialised
   calls over `MethodChannel`. Porting therefore means keeping the existing
   JVM code and replacing the channel with a binder, not rewriting extension
   execution.
2. **Package names are load-bearing.** Extension APKs declare classes under
   `eu.kanade.tachiyomi.*` (Aniyomi), `com.lagradost.cloudstream3.*`
   (CloudStream) and reference host-side base classes. Those package names had
   to be preserved, so the host keeps the upstream package layout and only the
   new SDK/IPC surface uses `io.chibi.*`.
3. **Tsundoku and iReader are Tachiyomi-family.** In the upstream Dart bridge
   their handlers call `handleNovelBridge`, which routes to
   `getInstalledMangaExtensions` / `aniyomi*` host methods. They are therefore
   implemented on top of the shared `TachiyomiEngine` rather than as new
   loaders.
4. **Mangayomi and Sora are JavaScript/Dart-script ecosystems.** Their bridge
   handlers evaluate scripted sources; the Kotlin port implements them on top
   of the embedded JS runtime with an explicit set of supported globals.
5. **Legado is rule based**, not code based: the Dart `LegadoRuleEngine.dart`
   was ported to a Kotlin rule engine (CSS selectors, `@json:`, `##regex##`,
   `||` alternatives, URL templates) over Jsoup.
6. **TorrServer is a REST addon** (`/torrents`, `/stream`, `/echo`) reached over
   HTTP; no Go code is needed in the host.
7. **Broken upstream file:** `eu/kanade/tachiyomi/extension/installer/Installer.kt`
   imports `ani.dantotsu.*` classes that are not part of Aniyomi. It is unused
   by the bridge (installation goes through `ExtensionInstallerBridge`), so
   `Installer.kt` and `PackageInstallerInstaller.kt` were dropped from
   `backends/aniyomi`.

## Licences (checked before reuse)

| Project | Licence | Consequence |
| --- | --- | --- |
| AnymeX runtime bridge | UPL (GPL-3.0 + public source) | Reuse allowed; derived work must stay GPL-3.0 and public |
| Aniyomi | Apache-2.0 | Attribution in `NOTICE` |
| CloudStream | GPL-3.0 | Copyleft compatible |
| Kotatsu / `kotatsu_ext` | GPL-3.0 | Kept in `backends/kotatsu` with its `LICENSE` |
| Legado, TorrServer | GPL-3.0 | Copyleft compatible |
| LNReader, Mangayomi, iReader, Sora, Tsundoku | Apache-2.0 / GPL-3.0 | Attribution in `NOTICE` |

Because UPL is GPL-3.0 plus a public-source requirement, the whole project is
licensed **GPL-3.0** and stays public. See `LICENSE_COMPLIANCE.md`.
