# AGENTS.md

## Project

yt2agent: Android app registered as a share target (`ACTION_SEND`, text/plain). Receives
a URL shared from any app, fetches the video transcript with yt-dlp, and re-shares the
timestamped transcript to any app — typically an AI agent app. Model: Scrambled Exif
(share in → transform → share out). The launcher activity (`MainActivity`) shows the
share options, build info (cache TTL, yt-dlp and app versions), and the cached
transcripts with copy and share buttons; it never fetches.

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

- `app/src/main/java/info/chanu/yt2agent/MainActivity.kt` — launcher screen: the
  app logo and name header, the cached transcripts (`cachedTranscripts()`, most
  recent first; subtitle = cache date + id) with delete/copy/share icon buttons,
  and a collapsed config panel: the share-option checkboxes (same preferences as
  the fetch screen), the transcript-cache TTL dropdown (`KEY_TTL_HOURS`,
  1 h–7 d, default 1 d), and the app/yt-dlp versions.
- `app/src/main/java/info/chanu/yt2agent/ShareActivity.kt` — intent handling, first-URL
  extraction (`findSharedUrl`), playlist detection (`isPlaylistUrl`), share-text building
  (`buildShareText`, `buildPlaylistDocument`, `stripLinkSchemes`), share out.
- `app/src/main/java/info/chanu/yt2agent/TranscriptFetcher.kt` — single yt-dlp pass
  (`--write-info-json` + json3 captions), playlist pass (`fetchPlaylist`), transcript
  cache, `parseJson3` flattening, `insertChapters`, `formatTime`.
- `app/src/test/java/info/chanu/yt2agent/TranscriptTest.kt` — unit tests for the pure
  logic (URL extraction, json3 parsing, chapters, share text).

## Conventions

- Keep the app minimal: two activities (share flow + launcher), single fetch path. No
  database, no dependencies beyond the yt-dlp library. The launcher screen reads the
  cache and displays build info; it never fetches.
- Pure logic (parsing, regexes) goes in top-level `internal` functions so it is unit
  testable without Android.
- `YoutubeDL.init()` and `execute()` are blocking; call them only from a background
  thread. yt-dlp writes the info file at the END of the run, so the early display
  title comes from the first `[download] Destination:` line (the output template
  embeds the title); `destinationTitle` strips the directory, the known subtitle
  extension, then the language code — titles containing dots keep their dots.
- One yt-dlp run per fetch: metadata and subtitles come from the same
  `--write-info-json` + `--write-subs` invocation. Do not reintroduce a second
  extraction pass (it doubles YouTube API pressure and 429 risk).
- Fetch options are load-bearing against YouTube 429s: `--ignore-errors`,
  `--retries/--retry-sleep`. No `player_client` override: android_vr was killed by
  YouTube on 2026-08-17 (yt-dlp dropped it from defaults in 2026.08.19); without a JS
  runtime the defaults extract through visionos, and the 72 h binary update keeps the
  client set current. A fresh install on a stale bundled binary self-heals: the failed
  fetch chain forces an update and attempt 3 runs with the new defaults.
- `--extractor-args "youtube:player_skip=js"`: the n/sig JS challenge is solved per
  video and has become systematic (2026.08.19: even visionos's HLS manifests carry
  the n parameter); solving it only produces googlevideo URLs the app never reads
  (--skip-download, captions come from timedtext). Challenge-bearing formats are
  dropped instead of solved — measured locally: -38% wall time, identical metadata
  and subtitle files; the fetch no longer depends on a JS runtime.
- `updateYoutubeDL(STABLE)` runs at most every 72 hours, AFTER the fetch chain in the
  fetch thread (it never races an execution and the chooser is already up); a failed
  fetch (except "no transcript") forces a redownload and one retry — skipped when the
  binary is less than 12 hours old. `updateBinary()` and `execute` share `ytLock`.
- Subtitle preference: a single `-orig` track is taken as is (genuine ASR); among
  several — YouTube's multi-language ASR emits many, all marked "Original", and the
  spoken language is undetectable — English wins, then the locale; without `-orig`
  tracks, locale then English. `pickSubtitle` logs the available files and the pick.
- Caption formats: json3 preferred, then vtt/srt (`--sub-format "json3/vtt/srt"`);
  `parseVttOrSrt` handles both (shared cue-block shape) and merges rolling
  auto-caption lines (a cue starting with the previous one is its replacement).
  `parseJson3` slices the raw json between `tStartMs` markers — no JSONObject tree,
  which cost 5-10x the file size in heap and OOM'd caption-heavy entries.
  Non-YouTube sites without json3 work through vtt/srt.
- Network frugality: `--sub-langs "en.*-orig,<locale>.*-orig,<locale>,en"` downloads
  only the tracks the selector can pick (multi-ASR videos expose 16 "-orig" tracks);
  `--retries 3 --retry-sleep 10` keeps 429-storm request counts low; transcripts are
  cached (dropdown TTL on the main screen: 1 h–7 d, default 1 d) and no-transcript
  failures 24 h, so cache hits cost zero requests.
- Single-video path: `--no-playlist` + `--playlist-items 1`. Channel URLs
  enumerate every video otherwise (a channel link used to hang the fetch
  indefinitely); any multi-video extraction is capped to its first entry.
- Playlist path (`isPlaylistUrl`: `youtube.com/playlist`, `/@handle`, `/channel/`,
  `/c/`, `/user/`, and `watch?v=…&list=…&index=…` — a share from inside a playlist;
  `watch?v=…&list=…` without an index stays a single video): `--playlist-items 1-30`
  (`PLAYLIST_MAX`), output template `%(playlist_index)03d - %(title)s` so each video's
  info json and captions group by index prefix. Entries without captions are skipped,
  not fatal. The document is a playlist header plus `[i/N]` blocks (title, info line,
  description when the "Include the description" checkbox is on, transcript); a
  video the fetch could not fill gets a `[i/N] <no transcript>` marker line instead
  of being silently omitted. Per-video sources stay out. Videos that failed
  permanently (deleted/private, counted from yt-dlp ERROR lines via `deadVideoRegex`)
  count as expected absences, so their playlist stays cacheable. Oversized files
  (past `MAX_PARSE_BYTES`, 10 MB) are pruned from the subs dir after the parse.
  Resume: `--download-archive`
  + `--force-download-archive` (required with `--skip-download`) in the subs dir; a
  `session.txt` marker (same playlist, < 1 h old) keeps the dir across retries — the
  fetch chain AND the Retry button continue after the failed entry instead of
  restarting. Files past 10 MB (`MAX_PARSE_BYTES`) are never read — one such file
  OOM'd the heap mid-parse (org.json needs the whole string): an oversized caption
  track is skipped (the single-video path picks a smaller track instead), and an
  oversized info json (a multi-ASR entry embeds ~25k signed caption URLs, tens of MB
  of ballast) only thins the entry's metadata — the title falls back to the file
  name and the entry still ships. `--ignore-errors` swallows entry failures
  silently: when the info-json count is short of the reported entry count, one extra
  pass refetches the dropped entries, and a still-partial result
  (`PlaylistTranscript.complete = false`) is never cached — a re-share resumes from
  the archive. Cache key prefix v7.
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
- "Share as file" (4th checkbox, unchecked by default): the same text goes out as
  a .txt attachment (EXTRA_STREAM) via androidx FileProvider (`shared/` cache dir,
  one file kept, name `yyyyMMdd-HHmm_yt2agent.txt` — the title rides in EXTRA_SUBJECT).
  Playlist shares force it on (checked+greyed, preference untouched).
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
  false) and filters at share time — retroactive on cached entries. Key prefix v7
  (bumped with the playlist path).
- Cache: chapters ARE cached (JSONArray of {ms, t}).
- Cache: `cacheDir/transcripts/<md5-of-url>.json`, TTL from the main-screen dropdown
  (`KEY_TTL_HOURS`, default 1 d; short-term de-dup, not a store);
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

## Review process

- Load the `/kotlin` (Android Kotlin style guide) skill before writing or
  reviewing code; it is not restated here.
- Run a `/ponytail-review` pass over the diff before every commit:
  over-engineering only — no one-implementation abstractions, stdlib first,
  deletion over addition. Mark deliberate shortcuts with a `ponytail:` comment
  naming the ceiling.

## Build and test

```
JAVA_HOME=$HOME/Documents/tools/jdk25 ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

If dependency resolution hangs (dead sockets to dl.google.com), re-run with
`-Dorg.gradle.internal.http.socketTimeout=30000 -Dorg.gradle.internal.http.connectionTimeout=30000`.

## Releases

- Strict semver (minor = features, patch = fixes); `versionCode` +1 per release;
  three APKs per ABI (arm64, armeabi-v7a, universal); release notes in English;
  one release per tag; validate on the device before publishing.
- Keep the signed APKs in a dedicated stock directory outside the repo
  (`<releases-dir>/<version>/`), NOT in `app/build/outputs/`: AGP purges that
  directory of unexpected files — APKs stored there were deleted by a build and
  had to be rebuilt from the tag.
- Upload the assets after tagging:
  `gh release upload v<version> <releases-dir>/v<version>/*.apk --clobber`.

## Rejected by the user — do not re-propose

- Truncating long transcripts.
- Downloading yt-dlp post-install (it ships bundled; the 72 h update covers it).
- Clickable per-timecode links (destination agent apps link-sniff and would
  discard the rest of the text).

## Rejected after analysis — do not re-analyze

- Tightening `--sub-langs`: every pattern in the selector is load-bearing
  (`en-GB-orig` exists, `${locale},${en}` catch manual subtitles without
  `-orig`, and the downloaded multi-ASR tracks all serve as pick candidates);
  tightening loses real videos. The real lever would be picking before
  downloading, which needs a second yt-dlp pass — forbidden by convention.
- The library's output accumulation: a real run's output is the visible lines
  (tens of KB); the 2026-10-12 OOM came from `readText` + the org.json tree
  (both fixed), not the StringWriter. Revisit only if a run produces megabytes
  of output.

## Known ceilings

- Subtitle language: device locale then English, no picker (`ponytail:` in
  TranscriptFetcher).
- Bot detection: if YouTube blocks the default yt-dlp client, update the bundled
  yt-dlp via `updateYoutubeDL`.
