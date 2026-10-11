# AGENTS.md

## Project

yt2agent: Android app registered as a share target (`ACTION_SEND`, text/plain). Receives
a YouTube link shared from the YouTube app, fetches the video transcript with yt-dlp,
and re-shares the plain-text transcript to any app. Model: Scrambled Exif (share in →
transform → share out). No main UI, no launcher activity.

## Toolchain

- JDK 25 (Temurin LTS) at `$HOME/Documents/tools/jdk25`; pass as `JAVA_HOME`.
- Gradle 9.8.1 via the wrapper; distribution also at `$HOME/Documents/tools/gradle-9.8.1`.
- AGP 9.4.1: built-in Kotlin support is enabled by default — do NOT add the
  `org.jetbrains.kotlin.android` plugin (build fails with a duplicate `kotlin` extension).
- Android SDK at `$HOME/Documents/tools/android-sdk` (platform 36, build-tools 36),
  wired via `local.properties`.
- minSdk 26, targetSdk 35, Java/Kotlin bytecode level 17.
- `packaging.jniLibs.useLegacyPackaging = true` is required: youtubedl-android reads its
  bundled Python environment from extracted `.so` files on disk; without it `init()`
  fails with "failed to initialize" on every run.

## Stack

- Kotlin, plain `android.app.Activity` (no Compose, no AppCompat, no DI).
- yt-dlp via `io.github.junkfood02.youtubedl-android:library:0.18.1`, classes in
  `com.yausername.youtubedl_android` (underscore package, not `...youtubedl`).
- org.json (Android built-in) for json3 caption parsing.

## Layout

- `app/src/main/java/dev/glucose/yt2agent/ShareActivity.kt` — intent handling, URL
  extraction (`findVideoUrl`), share out.
- `app/src/main/java/dev/glucose/yt2agent/TranscriptFetcher.kt` — yt-dlp invocation and
  `parseJson3` flattening to `[MM:SS]`-timestamped lines.
- `app/src/test/java/dev/glucose/yt2agent/TranscriptTest.kt` — unit tests for the pure
  logic (URL regex, json3 parsing).

## Conventions

- Keep the app minimal: single activity, single fetch path. No settings screen, no
  database, no dependencies beyond the yt-dlp library.
- Pure logic (parsing, regexes) goes in top-level `internal` functions so it is unit
  testable without Android.
- `YoutubeDL.init()` and `execute()` are blocking; call them only from a background
  thread.
- Fetch options are load-bearing against YouTube 429s: `--ignore-errors`,
  `--retries/--retry-sleep`, and `--extractor-args youtube:player_client=android_vr`
  (the library cannot impersonate a browser, so the default client gets blocked).
  `updateYoutubeDL(STABLE)` runs before each fetch to keep the binary current.
- Error surface is a Toast + `finish()`. Toast text is filtered to the first
  `ERROR` line (`firstErrorLine`), because `YoutubeDLException` messages embed the
  whole yt-dlp stderr including harmless ffmpeg warnings.
- Deliberate shortcuts are marked with `ponytail:` comments.
- Do not trim text inside `aAppend` continuation events: the leading space is
  significant in auto-captions.

## Build and test

```
JAVA_HOME=$HOME/Documents/tools/jdk25 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

If dependency resolution hangs (dead sockets to dl.google.com), re-run with
`-Dorg.gradle.internal.http.socketTimeout=30000 -Dorg.gradle.internal.http.connectionTimeout=30000`.

## Known ceilings

- Subtitle language: device locale then English, no picker (`ponytail:` in
  TranscriptFetcher).
- Bot detection: if YouTube blocks the default yt-dlp client, update the bundled
  yt-dlp via `updateYoutubeDL`.
