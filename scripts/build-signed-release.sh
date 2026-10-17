#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

: "${JAVA_HOME:?JAVA_HOME must point to a JDK}"
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
: "${THE_HAND_SIGNING_ENV_FILE:?THE_HAND_SIGNING_ENV_FILE must point to the signing environment file}"

./gradlew clean testDebugUnitTest assembleRelease --no-daemon

UNSIGNED="app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk"
[[ -f "$UNSIGNED" ]] || { echo "Expected release APK not found: $UNSIGNED" >&2; exit 1; }
VERSION_NAME=$("$ANDROID_HOME/build-tools/$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -1)/aapt2" dump badging "$UNSIGNED" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
[[ -n "$VERSION_NAME" ]] || { echo "Could not read APK versionName." >&2; exit 1; }
RELEASE_NUMBER=${VERSION_NAME#1.}
OUTPUT="release/The-Hand-r${RELEASE_NUMBER}-arm64-v8a.apk"

scripts/sign-release-apk.sh "$UNSIGNED" "$OUTPUT"
echo "Signed release: $OUTPUT"
