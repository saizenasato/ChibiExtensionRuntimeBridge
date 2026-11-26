# Backends

All ten backends are wired into `HostBridge` and selectable through
`ChibiRuntime` by id. "Mechanism" describes how extension code is executed.

| id | Ecosystem | Mechanism | Status |
| --- | --- | --- | --- |
| `aniyomi` | Aniyomi / Tachiyomi | `DexClassLoader` over the extension APK; `eu.kanade.tachiyomi.animesource` and `tachiyomi.source` base classes are compiled into `:backends:aniyomi` | Source-complete |
| `cloudstream` | CloudStream 3 | `DexClassLoader`; `com.lagradost.cloudstream3` providers, `MainAPI`, `ExtractorApi` compiled into `:backends:cloudstream` | Source-complete |
| `kotatsu` | Kotatsu | Parsers compiled into `:backends:kotatsu-parsers` (`kotatsu_ext` sources); Manga/Anime parser API compiled into `:backends:kotatsu` | Source-complete |
| `legado` | Legado (阅读) | Native Kotlin rule engine over Jsoup: CSS selectors, `@json:`, `##regex##`, `||` alternatives, URL templates, `{{ }}` variables | Source-complete; `@js:` rules return `UNSUPPORTED` |
| `lnreader` | LNReader | JS plugin evaluated in the Rhino runtime (`javascript-runtime`) | Source-complete |
| `sora` | Sora (Dartagnan) | JS module evaluated in the Rhino runtime | Source-complete |
| `tsundoku` | Tsundoku | Tachiyomi-family: routed through `TachiyomiEngine` (`HostTachiyomiEngine`), same loader as Aniyomi | Source-complete |
| `ireader` | iReader | Tachiyomi-family: routed through `TachiyomiEngine` | Source-complete |
| `mangayomi` | Mangayomi | JS sources evaluated in the Rhino runtime | Partial – see blockers |
| `torrserver` | TorrServer | REST addon over HTTP (`/torrserver/*` RPCs: `echo`, `add`, `list`, `get`, `rem`, `drop`, `stream`) | Source-complete; requires a TorrServer instance |

## Known limitations and blockers

These are real technical constraints, not omissions of wiring:

1. **Mangayomi's native source format is Dart** (`.dart` files evaluated by a
   Dart VM / `dart_eval`). Android has no embeddable, production-ready Dart
   evaluator, so the Mangayomi backend executes the **JavaScript** source
   variant of Mangayomi extensions. Dart-only extensions cannot run without
   shipping a Dart VM and will report `UNSUPPORTED`.
2. **Rhino has no true async.** `AsyncNormalizer` strips `async`/`await` and
   `Promise` chains are resolved eagerly by the `fetch` shim. Plugins that rely
   on real promise ordering, `setTimeout` semantics or `await` inside loops may
   misbehave.
3. **TorrServer binary is not embedded.** The Go server must run separately
   (default `http://127.0.0.1:8090`, configurable). Bundling a Go binary for
   every ABI was out of scope for the host APK.
4. **Kotatsu "available" equals "installed".** Kotatsu parsers are compiled
   into the host, so `availableSources` returns the parser set and there is no
   per-source install step.
5. **Legado `@js:` rules** need a JS bridge with Legado's `java.*` object model;
   they currently raise `UNSUPPORTED` rather than silently returning nothing.
6. **No device verification.** Extension execution requires an Android device
   with real extension APKs; see `TESTING.md`.
