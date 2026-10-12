# AGENTS.md

## Project

yt2agent: Android app registered as a share target (`ACTION_SEND`, text/plain). Receives
a URL shared from any app, fetches the video transcript with yt-dlp, and re-shares the
timestamped transcript to any app — typically an AI agent app. Model: Scrambled Exif
(share in → transform → share out). No main UI, no launcher activity.

## Toolchain

- JDK 25 (Temurin LTS) at `$HOME/Documents/tools/jdk25`; pass as `JAVA_HOME`.
- Gradle 9.8.1 via the wrapper; distribution also at `$HOME/Documents/tools/gradle-9.8.1`.
- AGP 9.4.1: built-in Kotlin support is enabled by default — do NOT add the
  `org.jetbrains.kotlin.android` plugin (build fails with a duplicate `kotlin` extension).
- Android SDK at `$HOME/Documents/tools/android-sdk` (platform 36, build-tools 36),
  wired via `local.properties`.
- minSdk 24, targetSdk 35, Java/Kotlin bytecode level 17.
- ABIs limited to arm64-v8a + armeabi-v7a (`ndk.abiFilters`, required to keep the
  universal APK small) plus `splits.abi` with `isUniversalApk`: the release produces
  arm64 (18 MB), v7a (17 MB) and universal (31 MB) APKs. x86 would only serve emulators.
- Release is R8-minified; `proguard-rules.pro` keeps the whole
  `com.yausername.youtubedl_android` tree (native code may reflect into it).
- `packaging.jniLibs.useLegacyPackaging = true` is required: youtubedl-android reads its
  bundled Python environment from extracted `.so` files on disk; without it `init()`
  fails with "failed to initialize" on every run.
- Release signing lives in `keystore.properties` (gitignored, path
  `$HOME/Documents/tools/yt2agent-release.jks`); builds are unsigned without it.

## Stack

- Kotlin, plain `android.app.Activity` (no Compose, no AppCompat, no DI).
- yt-dlp via `io.github.junkfood02.youtubedl-android:library:0.18.1`, classes in
  `com.yausername.youtubedl_android` (underscore package, not `...youtubedl`).
- org.json (Android built-in) for json3 caption and metadata parsing.

## Layout

- `app/src/main/java/info/chanu/yt2agent/ShareActivity.kt` — intent handling, first-URL
  extraction (`findSharedUrl`), share-text building (`buildShareText`,
  `stripLinkSchemes`), share out.
- `app/src/main/java/info/chanu/yt2agent/TranscriptFetcher.kt` — single yt-dlp pass
  (`--write-info-json` + json3 captions), 7-day transcript cache, `parseJson3`
  flattening, `insertChapters`, `formatTime`.
- `app/src/test/java/info/chanu/yt2agent/TranscriptTest.kt` — unit tests for the pure
  logic (URL extraction, json3 parsing, chapters, share text).

## Conventions

- Keep the app minimal: single activity, single fetch path. No settings screen, no
  database, no dependencies beyond the yt-dlp library.
- Pure logic (parsing, regexes) goes in top-level `internal` functions so it is unit
  testable without Android.
- `YoutubeDL.init()` and `execute()` are blocking; call them only from a background
  thread. yt-dlp writes the info file at the END of the run, so the early display
  title comes from the first `[download] Destination:` line (the output template
  embeds the title); `destinationTitle` strips the directory and extensions.
- One yt-dlp run per fetch: metadata and subtitles come from the same
  `--write-info-json` + `--write-subs` invocation. Do not reintroduce a second
  extraction pass (it doubles YouTube API pressure and 429 risk).
- Fetch options are load-bearing against YouTube 429s: `--ignore-errors`,
  `--retries/--retry-sleep`, and `--extractor-args youtube:player_client=android_vr`
  (the library cannot impersonate a browser, so the default client gets blocked).
- `updateYoutubeDL(STABLE)` runs at most every 72 hours, AFTER the fetch chain in the
  fetch thread (it never races an execution and the chooser is already up); a failed
  fetch (except "no transcript") forces a redownload and one retry — skipped when the
  binary is less than 12 hours old. `updateBinary()` and `execute` share `ytLock`.
- Subtitle preference: a single `-orig` track is taken as is (genuine ASR); among
  several — YouTube's multi-language ASR emits many, all marked "Original", and the
  spoken language is undetectable — English wins, then the locale; without `-orig`
  tracks, locale then English. `pickSubtitle` logs the available files and the pick.
- Caption formats: json3 preferred, then vtt/srt (`--sub-format "json3/vtt/srt"`);
  `parseVttOrSrt` handles both (shared cue-block shape). Non-YouTube sites without
  json3 work through vtt/srt.
- Network frugality: `--sub-langs "en.*-orig,<locale>.*-orig,<locale>,en"` downloads
  only the tracks the selector can pick (multi-ASR videos expose 16 "-orig" tracks);
  `--retries 3 --retry-sleep 10` keeps 429-storm request counts low; transcripts are
  cached 7 days and no-transcript failures 24 h, so cache hits cost zero requests.
- `--no-playlist` + `--playlist-items 1`: one video per share. Channel URLs
  enumerate every video otherwise (a channel link used to hang the fetch
  indefinitely); any multi-video extraction is capped to its first entry.
- Cache hits hold the fetch screen for one second so the checkboxes are
  reachable (`MIN_FETCH_DISPLAY_MILLIS`).
- Fetch failures show `activity_error` (message + Copy details + Retry + Cancel);
  "Copy details" puts the full stack trace on the clipboard for bug reports.
- Day/night: `AppTheme` has a light parent in `values/themes.xml` and a dark one in
  `values-night/themes.xml`; no hardcoded colors in layouts.
- Progress: yt-dlp output lines flow to the `operation` TextView via the
  `execute` callback (WARNING lines filtered); no percentage exists because
  extraction, the slow part, has none. `operationStringRes` maps known lines to
  localized labels.
- i18n: default strings are English; `values-fr`, `values-de`, `values-es` are full
  translations. Keep them in sync when adding strings.
- Shared text: title, metadata line (`buildInfoLine`: channel, duration,
  upload date), capped description, source, chapter summary, transcript.
  Descriptions capped at 1000 chars (`capDescription`).
- Error path order: plain retry first (transient DNS/429 errors resolve alone),
  forced binary update + retry only on a second failure — the update download
  is paid only when the binary is the plausible suspect.
- `--no-warnings` keeps the ffmpeg/po_token noise out of the pipes.
- The fetch screen ticks an elapsed timer (`elapsed` TextView), interrupted by
  the fetch thread's finally block.
- SponsorBlock: `--sponsorblock-mark sponsor,selfpromo,interaction` — the mark
  list filters what lands in sponsorblock_chapters (verified), so it is exactly
  the removable set. Segments are cached ({s, e, c}); key prefix v6. The checkbox
  on the fetch screen persists via SharedPreferences (remove_sponsors, default
  false) and filters at share time — retroactive on cached entries.
- Cache: chapters ARE cached (JSONArray of {ms, t}).
- Cache: `cacheDir/transcripts/<md5-of-url>.json`, 1-hour TTL (short-term de-dup);
  read before init, write after a successful fetch. Negative cache: `missing-<key>.json`,
  24-hour TTL. `pruneCache()` drops expired entries and enforces a 30 MB cap on every
  write.
- Error surface is a Toast + `finish()`. Toast text is filtered to the first
  `ERROR` line (`firstErrorLine`), because `YoutubeDLException` messages embed the
  whole yt-dlp stderr including harmless ffmpeg warnings.
- Shared text contains no clickable URL: destination agent apps link-sniff and
  discard the rest. `stripLinkSchemes` + scheme-less `Source:` line handle this.
- Deliberate shortcuts are marked with `ponytail:` comments.
- Do not trim text inside `aAppend` continuation events: the leading space is
  significant in auto-captions.

## Build and test

```
JAVA_HOME=$HOME/Documents/tools/jdk25 ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

If dependency resolution hangs (dead sockets to dl.google.com), re-run with
`-Dorg.gradle.internal.http.socketTimeout=30000 -Dorg.gradle.internal.http.connectionTimeout=30000`.

## Known ceilings

- Subtitle language: device locale then English, no picker (`ponytail:` in
  TranscriptFetcher).
- Bot detection: if YouTube blocks the default yt-dlp client, update the bundled
  yt-dlp via `updateYoutubeDL`.
