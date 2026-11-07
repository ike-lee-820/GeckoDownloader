# GeckoDownloader

Android app that combines a download manager, a GeckoView browser, and a resource sniffer.
Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, OkHttp, minSdk 26, targetSdk 34,
compileSdk 35 (required by androidx.core 1.15), Java 17.

## Build

This repository does not ship a Gradle Wrapper. AGP 8.5.2 requires Gradle 8.7+, so install
Gradle 8.7 locally (or generate a wrapper once):

```
gradle wrapper --gradle-version 8.7 --distribution-type bin
./gradlew :app:assembleDebug
```

GeckoView is pinned in `gradle/libs.versions.toml` to `120.0.20231113165053`, using the real
stable coordinate `org.mozilla.geckoview:geckoview` from `https://maven.mozilla.org/maven2/`.
The original `geckoview-release:159.0.20261102144711` coordinate does not exist on that
repository and made every CI build fail at dependency resolution; the pinned version is a
verified published AAR whose API/compileSdk 34 requirement matches this project. It can be
overridden with `-PgeckoviewVersion=...` (or the `geckoview_version` workflow input).

The omni AAR contains all ABIs; `abiFilters` keeps only `arm64-v8a` in the packaged APK.

Release builds enable R8 and resource shrinking. `abiFilters` keeps **only `arm64-v8a`**
(ARMv8-A 64-bit). The `geckoview-release` AAR contains all ABIs, and `abiFilters` strips the
others from the packaged APK. `versionName` / `versionCode` default to `26.11.0` / `2611000`
and can be overridden with `-PappVersionName=... -PappVersionCode=...`.

## Release (GitHub Actions)

`.github/workflows/release.yml` builds a signed release APK and publishes it as a GitHub Release.

- **Triggers**: push to `release*` / `release/**` branches, push of a `release*` tag, or a manual
  `workflow_dispatch` run (optionally with an explicit `version` input).
- **Version**: `YY.MM.N` — two-digit year, month, and the release index within that month
  (Asia/Shanghai). The first release in November 2026 is `26.11.0`, the next `26.11.1`, and so on.
  A `release*` tag containing `YY.MM.N` reuses that number. Existing tags are never overwritten.
- **Artifacts**: `GeckoDownloader-<version>-arm64-v8a.apk` plus `SHA256SUMS`, attached to the
  Release whose tag and title are the version. The workflow verifies that no ABI other than
  `arm64-v8a` is packaged.
- **Signing**: zero configuration. `signing/ikelee-release.jks` (alias `ikelee`, store/key
  password `liyikui917`) ships with the repository, so every build uses the same key and
  in-app upgrades install cleanly. The workflow never falls back to a throwaway keystore.
  Each Release's notes include the signing certificate SHA-256 fingerprint — it stays the same
  across versions.

  Because the keystore and its password are public, anyone can produce an APK signed with this
  key. That is an accepted trade-off here: the key exists only to keep upgrades installable,
  not to prove authorship. Do not reuse it for anything that needs trust.

  Optional overrides (Settings → Secrets and variables → Actions): `SIGNING_KEYSTORE_BASE64`
  + `SIGNING_KEYSTORE_PASSWORD` replace the in-repo keystore; `SIGNING_KEY_ALIAS` (default
  `ikelee`) and `SIGNING_KEY_PASSWORD` (default = store password) fine-tune it. Rotating the
  key means existing users must uninstall the old build first.
- **Token**: the workflow uses the built-in `GITHUB_TOKEN` with `contents: write`. No personal
  access token is required. Never put a PAT in a workflow file or in source.
- **First run**: this source tree has not been compiled yet (see Status below). The first workflow
  run may fail at Kotlin/R8/GeckoView resolution; fix those errors from the build log. Also make
  sure the runner can reach `maven.mozilla.org`, which hosts the GeckoView AAR.

## In-app updates

The app checks GitHub Releases on every cold start (toggle in Settings → About) and can also be
checked manually from Settings.

- **Metadata**: `GET https://api.github.com/repos/ike-lee-820/GeckoDownloader/releases/latest`.
  This is a public endpoint: no token, no credentials stored in the app. The `arm64-v8a` APK
  asset is preferred.
- **Version**: `YY.MM.N` is compared numerically against `BuildConfig.APP_VERSION`.
- **Choices**: when a newer release exists the dialog offers **In-app update**,
  **Open in browser**, or **Cancel**.
- **In-app update**: 32 parallel ranged requests (`ApkDownloader`) with live progress
  (bytes / total / percent / active threads), then the package installer is launched through
  `FileProvider` (`REQUEST_INSTALL_PACKAGES`).
- **Open in browser**: an `ACTION_VIEW` / `CATEGORY_BROWSABLE` intent handed to the system
  default browser.
- **v6.github.com proxy**: every release page and APK download URL is rewritten to
  `v6.github.com` by `UpdateConfig.proxy`. If the proxy ignores `Range`, fails, or returns an
  HTML page instead of an APK, the downloader retries the original `github.com` URL and
  validates the result (size + ZIP containing `AndroidManifest.xml`) before offering install.

**Signing note**: in-app installation only works when the new APK has the same signature as the
installed one. The fixed keystore is committed in `signing/`, so the workflow always signs with
the same key and prints the certificate SHA-256 in every Release. If you ever rotate the
keystore, users must uninstall the old build first.

## Status

This is a first full source pass. It has NOT been compiled, run on a device, or tested.
Known gaps are listed below. Treat the acceptance list in the spec as open until each item is
verified on hardware.

Implemented in source:
- Download engine: HTTP ranged chunks (1 to 32 threads, `Range` requests), per-chunk persistence in
  Room, resume after restart, retry with exponential backoff, size check against the probe.
- Output to app storage or to a SAF tree URI through positional writes.
- Global concurrency (1 to 8 tasks), pause, resume, cancel, retry, priority ordering.
- HLS: master playlist variant selection (highest bandwidth), media playlist parsing,
  AES-128 decryption, parallel segment fetch with `.part` then rename for resume, ordered
  concatenation to MPEG-TS. Init segments from EXT-X-MAP are prepended.
- Foreground service (dataSync) with a persistent notification, boot receiver,
  WorkManager recovery (Hilt worker plus periodic job).
- System handler: VIEW intents for common file suffixes over http, https and ftp, SEND text,
  custom ADD_DOWNLOAD action.
- GeckoView browser: one GeckoRuntime, one GeckoSession per tab, `setSession` on switch,
  navigation, progress and content delegates, `onNewSession` for `window.open`, private tabs
  via private-mode sessions with storage cleared when the last private tab closes.
- Sniffer: built-in WebExtension (MV2, `webRequest`, `webRequestBlocking`, `<all_urls>`) that
  reports media by MIME type and URL suffix, with method, URL, headers and body presence.
  Native aggregator de-duplicates per tab and attaches HLS segments to their playlist.
- Compose UI: bottom navigation (Downloads, Browser, Settings), browser toolbar with sniffed
  resource badge and a ModalBottomSheet to queue downloads, Material You theme with fallback.

Known gaps and deviations from the spec:
- Not compiled. The GeckoView API calls were written from memory of the API shape and must be
  checked against the pinned version (for example `WebExtension.setMessageDelegate`,
  `GeckoSessionSettings.Builder.usePrivateMode`, the `NavigationDelegate` signatures).
- Speed display is not wired. `DownloadEngine.speeds` is never populated and the progress
  callback in `runHttp` is a no-op. Bytes done are written to Room per chunk update.
- Incognito uses private-mode sessions on the shared runtime, not a separate GeckoRuntime
  or storage directory.
- The sniffer cannot attribute a request to a specific tab. It uses the currently active tab.
  Blocking and rewriting requests are not implemented; the listener only observes.
- Headers are read from `onBeforeSendHeaders`, because the WebExtension API does not expose
  request headers in `onBeforeRequest`.
- MPD (DASH) and byte-range HLS segments are not downloaded. HLS output is `.ts`, not MP4.
- Not implemented: `ACTION_DOWNLOAD_COMPLETED` receiver, Wi-Fi-only enforcement and network
  change pause/resume, per-task rate limit UI, custom UA settings UI, bookmarks screen,
  tab list screen with close-all, OEM auto-start guidance pages, battery-optimization prompt,
  find-in-page, desktop UA toggle beyond the settings switch, context menu delegate.
- Settings UI covers the main download, browser and appearance switches only.
- Launcher icon is a placeholder vector.

## Verification checklist (from the spec)

Each item needs a device run. Expected results are in the spec.
1. Render a page on Android 8.0 and Android 14. Navigation load start, finish and error fire.
2. Open an m3u8 test page. Check the sniffer list, then queue an HLS download.
3. Check that a POST request shows in the sniffer with its URL and headers.
4. Switch tabs. Page state holds and no session is closed unexpectedly.
5. Build release, check the single-ABI APK size (target 50 to 70 MB, limit 100 MB).
6. Confirm resume after killing the process mid-download.
7. Confirm private tab storage is cleared after the last private tab closes.
8. Check that address bar, back, forward, reload and tab switching work.
9. Confirm the service survives a process kill and restarts after reboot.
10. Share a direct https link to the app and check that the task appears.
