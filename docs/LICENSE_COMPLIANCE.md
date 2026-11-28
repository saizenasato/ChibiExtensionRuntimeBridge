# Licence compliance

This project is licensed **GPL-3.0** (`LICENSE`).

## Why GPL-3.0

The upstream AnymeX runtime bridge is released under the **Unlicense Public
License (UPL)**, which is GPL-3.0 plus an additional requirement that the
source stay publicly available. A derived work therefore has to be GPL-3.0 and
public — which this repository is.

## Obligations met

1. **Full source distribution.** Every module, including the ported JVM host
   code, is in this repository. Nothing is shipped as a binary-only blob.
2. **Copyleft notice.** `LICENSE` contains the GPL-3.0 text; `NOTICE` lists the
   upstream projects, their licences and the files derived from them.
3. **Per-project licences preserved.** `backends/kotatsu/parsers` keeps the
   upstream `LICENSE` next to the copied sources.
4. **No additional restrictions.** The additional terms of GPL-3.0 §7 are not
   applied beyond attribution.
5. **Third-party binary compatibility.** Package names from Aniyomi
   (`eu.kanade.tachiyomi.*`), CloudStream (`com.lagradost.cloudstream3.*`) and
   Keiyoushi (`keiyoushi.*`) are retained solely so that existing extension APKs
   link at runtime; this does not imply endorsement by those projects. This
   project's own runtime host uses `io.chibi.runtimehost`, an id distinct from
   AnymeX's `com.anymex.runtimehost`, so neither bridge replaces the other.

## Dependency licences

| Dependency | Licence |
| --- | --- |
| OkHttp / Okio | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| Jsoup | MIT |
| Rhino | MPL-2.0 |
| Jackson | Apache-2.0 |
| NewPipeExtractor | GPL-3.0 |
| NiceHttp | GPL-3.0 |
| injekt | Apache-2.0 |
| AndroidX | Apache-2.0 |

MPL-2.0 (Rhino) and Apache-2.0 dependencies are compatible with a GPL-3.0
application when used unmodified as libraries. GPL-3.0 dependencies
(NewPipeExtractor, NiceHttp) are compatible because the whole project is
GPL-3.0.

## If you redistribute

Keep `LICENSE`, `NOTICE` and the module-level licence files, and publish your
modified source. The Android runtime host APK is a covered work; shipping it
requires the corresponding source to be available.
