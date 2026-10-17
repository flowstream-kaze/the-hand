#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 2 ]]; then
  echo "Usage: THE_HAND_SIGNING_ENV_FILE=/secure/path/signing.env $0 INPUT_UNSIGNED_APK OUTPUT_SIGNED_APK" >&2
  exit 2
fi

INPUT=$(realpath "$1")
OUTPUT=$(realpath -m "$2")
ENV_FILE=${THE_HAND_SIGNING_ENV_FILE:-}

if [[ -z "$ENV_FILE" || ! -f "$ENV_FILE" ]]; then
  echo "THE_HAND_SIGNING_ENV_FILE must point to the root-only signing environment file." >&2
  exit 2
fi
if [[ ! -f "$INPUT" ]]; then
  echo "Input APK not found: $INPUT" >&2
  exit 2
fi
if [[ "$INPUT" == "$OUTPUT" ]]; then
  echo "Input and output APK paths must differ." >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

required=(
  THE_HAND_OLD_KEYSTORE THE_HAND_OLD_KEY_ALIAS
  THE_HAND_OLD_STORE_PASSWORD THE_HAND_OLD_KEY_PASSWORD
  THE_HAND_RELEASE_KEYSTORE THE_HAND_RELEASE_KEY_ALIAS
  THE_HAND_RELEASE_STORE_PASSWORD THE_HAND_RELEASE_KEY_PASSWORD
  THE_HAND_SIGNING_LINEAGE
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing signing variable: $name" >&2
    exit 2
  fi
done
for file in "$THE_HAND_OLD_KEYSTORE" "$THE_HAND_RELEASE_KEYSTORE" "$THE_HAND_SIGNING_LINEAGE"; do
  if [[ ! -f "$file" ]]; then
    echo "Signing file not found: $file" >&2
    exit 2
  fi
done

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
: "${JAVA_HOME:?JAVA_HOME must point to a JDK}"
BUILD_TOOLS=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -1)
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS/apksigner"
ZIPALIGN="$ANDROID_HOME/build-tools/$BUILD_TOOLS/zipalign"
[[ -x "$APKSIGNER" && -x "$ZIPALIGN" ]] || { echo "Android build tools are incomplete." >&2; exit 2; }

mkdir -p "$(dirname "$OUTPUT")"
TEMP_APK=$(mktemp --suffix=.apk)
SIGNED_APK=$(mktemp --suffix=.apk)
trap 'rm -f "$TEMP_APK" "$SIGNED_APK"' EXIT
if "$ZIPALIGN" -c -P 16 4 "$INPUT" >/dev/null 2>&1; then
  cp "$INPUT" "$TEMP_APK"
else
  "$ZIPALIGN" -f -P 16 4 "$INPUT" "$TEMP_APK"
fi
rm -f "$OUTPUT" "$OUTPUT.idsig"

PATH="$JAVA_HOME/bin:$PATH" "$APKSIGNER" sign \
  --ks "$THE_HAND_OLD_KEYSTORE" \
  --ks-key-alias "$THE_HAND_OLD_KEY_ALIAS" \
  --ks-pass env:THE_HAND_OLD_STORE_PASSWORD \
  --key-pass env:THE_HAND_OLD_KEY_PASSWORD \
  --next-signer \
  --ks "$THE_HAND_RELEASE_KEYSTORE" \
  --ks-type PKCS12 \
  --ks-key-alias "$THE_HAND_RELEASE_KEY_ALIAS" \
  --ks-pass env:THE_HAND_RELEASE_STORE_PASSWORD \
  --key-pass env:THE_HAND_RELEASE_KEY_PASSWORD \
  --lineage "$THE_HAND_SIGNING_LINEAGE" \
  --rotation-min-sdk-version 28 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$SIGNED_APK" "$TEMP_APK"

VERIFY_OUTPUT=$(PATH="$JAVA_HOME/bin:$PATH" "$APKSIGNER" verify --verbose --print-certs "$SIGNED_APK")
printf '%s\n' "$VERIFY_OUTPUT"
grep -q 'Verified using v1 scheme (JAR signing): true' <<<"$VERIFY_OUTPUT"
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$VERIFY_OUTPUT"
grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$VERIFY_OUTPUT"
grep -qi 'Signer #1 certificate SHA-256 digest: adcbf7289fbc7b3cae90c54c656276e35ff30d32732cfb091e4ba7f31d950c16' <<<"$VERIFY_OUTPUT"
LEGACY_VERIFY=$(PATH="$JAVA_HOME/bin:$PATH" "$APKSIGNER" verify \
  --min-sdk-version 23 --max-sdk-version 27 --print-certs "$SIGNED_APK")
grep -qi 'Signer #1 certificate SHA-256 digest: 2540c872a67c6a92112526642c2d5bedf71a1bacfc0aad7349edbecedc48a939' <<<"$LEGACY_VERIFY"
echo "Verified legacy signer for Android 6-8."

mv "$SIGNED_APK" "$OUTPUT"
sha256sum "$OUTPUT"
