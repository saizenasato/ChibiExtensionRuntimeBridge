# Testing

## What CI verifies

`.github/workflows/artifact.yml` runs on every push to `main` and on pull
requests:

1. `./gradlew testDebugUnitTest` – unit tests of the pure-Kotlin modules
   (models, IPC envelope, Legado rule parsing, JS normaliser).
2. `:runtime-host:assembleDebug` / `assembleRelease` – compiles the headless
   host APK, the ported Aniyomi/CloudStream/Kotatsu sources and the AIDL binder.
3. `:bridge-sdk:assembleRelease`, `:bridge-api:assembleRelease`,
   `:bridge-models:assembleRelease` – the consumable AARs.
4. `:sample-host:assembleDebug` – an app that actually links the SDK AAR.
5. Uploads `runtime-host-apk`, `bridge-sdk-aar`, `sample-host-apk` and the test
   reports as workflow artifacts. It does **not** create releases.

`.github/workflows/release.yml` runs on `v*` tags or manual dispatch: builds
release variants, collects APK/AAR files with `SHA256SUMS.txt`, and publishes a
GitHub Release.

## What is not verified

There is **no Android device or emulator in this environment**, so the
following have not been exercised:

* Loading a real Aniyomi/CloudStream extension APK through `DexClassLoader`.
* Network calls against live sources (popular/search/detail/video stream).
* Binder round-trips between a client app and the installed host APK.
* Rhino-evaluated Sora/LNReader/Mangayomi plugins and Legado rules against real
  site markup.
* TorrServer against a running instance.

Passing the CI build proves the code compiles and links; it does not prove a
backend works against a live extension. Manual verification needs a device:

```bash
adb install runtime-host/build/outputs/apk/debug/runtime-host-debug.apk
adb install sample-host/build/outputs/apk/debug/sample-host-debug.apk
adb install <some-aniyomi-extension>.apk
# open "Chibi Bridge Sample" and press "Connect & list sources"
```
