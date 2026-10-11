<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="yt2agent logo">
</p>

# yt2agent

Android app that appears in the Android share sheet of the YouTube app, downloads the
transcript of the shared video with yt-dlp, and re-shares it as plain text to any app
the user picks — typically an AI agent app. Flow inspired by
[Scrambled Exif](https://gitlab.com/juanitobananas/scrambled-exif/).

YouTube app → Share → yt2agent → transcript → app of your choice.

## How it works

- `ShareActivity` receives an `ACTION_SEND` (text/plain) intent, extracts the video ID
  from the shared text (`youtu.be/`, `watch?v=`, `shorts/`, `embed/`).
- `TranscriptFetcher` runs a single yt-dlp pass that writes both the metadata
  (`--write-info-json`: title, description, URL, chapters) and the json3
  captions (`--write-subs --write-auto-subs --skip-download`), preferring the
  video's original language over YouTube's auto-translations, then the device
  language, then English. Results are cached for 7 days, so sharing the same
  video again is instant and works offline. On failure, an error screen with a
  retry button replaces the old ephemeral toast.
- Any URL accepted by yt-dlp works (YouTube, Vimeo, PeerTube, ...); the first
  URL found in the shared text is used.
- The json3 captions are flattened to timestamped plain text: one `[MM:SS]` line
  per caption, with `[Chapitre]` markers inserted when the video has chapters.
- The title is shown in the progress UI while the transcript downloads.
- The result is re-shared via `ACTION_SEND`:

```
Video title

Video description

Source: youtu.be/dQw4w9WgXcQ

[00:00] [Chapitre] Introduction
[00:00] first line of the transcript
[00:03] second line…
```

The source line is deliberately scheme-less, and any URL inside the title or the
description is scheme-stripped too: agent/chat apps that sniff real URLs would
otherwise discard everything but the link. The full URL is passed as the share
subject (`EXTRA_SUBJECT`).

## Toolchain

JDK 25 (Temurin LTS), Gradle 9.8.1, Android Gradle Plugin 9.4.1 (built-in Kotlin
support — no separate Kotlin plugin), compileSdk 36 / minSdk 24 / targetSdk 35, arm64-v8a + armeabi-v7a only (no x86), R8-minified release.
yt-dlp via [youtubedl-android](https://github.com/junkfood02/youtubedl-android) 0.18.1
(yt-dlp + Python bundled in the AAR, package `com.yausername.youtubedl_android`).

## Build

```
JAVA_HOME=$HOME/Documents/tools/jdk25 ./gradlew :app:assembleDebug
```

Requires the Android SDK with platform 36 (`sdk.dir` is set in `local.properties`,
which is not committed). Output: `app/build/outputs/apk/debug/app-debug.apk`
(~63 MB: yt-dlp and Python are bundled in the app).

## Test

```
JAVA_HOME=$HOME/Documents/tools/jdk25 ./gradlew :app:testDebugUnitTest
```

Unit tests cover the URL extraction, the json3 flattening, and the shared-text
building.

## Notes

- First transcript fetch is slow: the library unpacks yt-dlp and Python on first use.
- The bundled yt-dlp binary is checked for updates every 72 hours; a failed fetch forces a
  redownload and one retry. The fetch also uses
  `--extractor-args youtube:player_client=android_vr` with retries: YouTube blocks
  old or non-impersonated clients with HTTP 429.
- No YouTube Data API key is needed; yt-dlp fetches captions like the web player does.
