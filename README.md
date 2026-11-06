# GeckoDownloader

Android app that combines a download manager, a GeckoView browser, and a resource sniffer.
Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, OkHttp, minSdk 26, targetSdk 34, Java 17.

## Build

```
./gradlew :app:assembleDebug
```

GeckoView is pinned in `gradle/libs.versions.toml` to `159.0.20261102144711` from
`https://maven.mozilla.org/maven2/` (the `geckoview-release` artifact). The version was supplied
by the project owner. It was not resolved by the author because the build environment blocks
maven.mozilla.org. Resolve it once before the first build.

Release builds enable R8 and resource shrinking. `abiFilters` keeps only `arm64-v8a` and
`armeabi-v7a`. The `geckoview-release` AAR contains all ABIs, and `abiFilters` strips the others
from the packaged APK.

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
