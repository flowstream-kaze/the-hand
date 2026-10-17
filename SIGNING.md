# Android release signing

The Hand's direct APK releases use an Android signing lineage so production-signed releases can update existing debug-signed installations without uninstalling them.

## Signers

- Original signer: the preserved Android debug key used for releases through r12.
- Current signer: `CN=The Hand, O=flowstream-kaze` (RSA 4096, valid through 2054).
- Current certificate SHA-256: `AD:CB:F7:28:9F:BC:7B:3C:AE:90:C5:4C:65:62:76:E3:5F:F3:0D:32:73:2C:FB:09:1E:4B:A7:F3:1D:95:0C:16`.
- Rotation starts at API 28. Android 9+ uses the production signer through APK Signature Scheme v3; Android 6–8 receives the original signer through v1/v2 for update compatibility.

The keystores, lineage, and passwords are never stored in Git.

## Signing environment

Create a root-only shell file outside the repository containing:

```bash
THE_HAND_OLD_KEYSTORE='/secure/path/debug.keystore'
THE_HAND_OLD_KEY_ALIAS='androiddebugkey'
THE_HAND_OLD_STORE_PASSWORD='...'
THE_HAND_OLD_KEY_PASSWORD='...'
THE_HAND_RELEASE_KEYSTORE='/secure/path/the-hand-release.p12'
THE_HAND_RELEASE_KEY_ALIAS='the-hand-release'
THE_HAND_RELEASE_STORE_PASSWORD='...'
THE_HAND_RELEASE_KEY_PASSWORD='...'
THE_HAND_SIGNING_LINEAGE='/secure/path/the-hand-signing-lineage.bin'
```

Protect it and both keystores with permissions `0600`. Keep encrypted offline backups of the production keystore, its password, the original keystore, and the lineage. Losing any required signing material can make future upgrades impossible.

## Build a signed arm64 release

```bash
export JAVA_HOME=/opt/jdk-21
export ANDROID_HOME=/opt/android-sdk
export THE_HAND_SIGNING_ENV_FILE=/secure/path/the-hand-android-signing.env
scripts/build-signed-release.sh
```

The output is written under `release/`. The script:

1. Runs unit tests and builds unsigned release APKs.
2. Verifies or applies ZIP alignment.
3. Signs with the original and production signers plus the rotation lineage.
4. Enables APK Signature Schemes v1, v2, and v3.
5. Verifies all schemes and the expected production certificate fingerprint.
6. Prints the final SHA-256 digest.

To sign an individual aligned or unaligned APK:

```bash
scripts/sign-release-apk.sh input-unsigned.apk output-signed.apk
```

Never sign a published version with a different lineage, replace the production key, commit credentials, or overwrite the only copy of signing material.
