# The Hand

An Android video and audio downloader powered by yt-dlp.

The Hand can receive shared links or search pasted URLs, offers a concise quality list, downloads in the background, and keeps a local download history. DASH and HLS streams use parallel fragment downloads.

## Build

```bash
JAVA_HOME=/opt/jdk-21 ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug
```

The project targets Android 14 and builds separate APKs for arm64-v8a, armeabi-v7a, x86, and x86_64.

## Source and license

The Hand is a GPL-3.0 fork of [yausername/dvd](https://github.com/yausername/dvd), built on [youtubedl-android](https://github.com/yausername/youtubedl-android). The original project history and attribution are retained.
