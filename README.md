# The Hand

An Android video and audio downloader powered by yt-dlp.

The Hand can receive shared links or search pasted URLs, offers a concise quality list, downloads in the background, and keeps a local download history. DASH and HLS streams use parallel fragment downloads.

## Build

```bash
JAVA_HOME=/opt/jdk-21 ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug
```

The project targets Android 14 and builds separate APKs for arm64-v8a, armeabi-v7a, x86, and x86_64.

## Web app

The focused web version lives in [`web/`](web/README.md). It provides URL lookup, curated video qualities, MP3 extraction, progress, cancellation, and temporary browser downloads.

Run it locally from the repository root:

```bash
node web/server/server.mjs
# open http://127.0.0.1:8787
```

Or build the isolated container with `docker compose -f web/compose.yaml up --build`. The Compose port binds only to localhost by default; put an authenticated private proxy or Tailscale Serve in front of it rather than exposing it directly to the public internet.

## Source and license

The Hand is a GPL-3.0 fork of [yausername/dvd](https://github.com/yausername/dvd), built on [youtubedl-android](https://github.com/yausername/youtubedl-android). The original project history and attribution are retained.
