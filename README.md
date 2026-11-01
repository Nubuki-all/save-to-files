# Save to files

A small Android app that adds **Save to files** to the share sheet and the
"Open with" list. Pick it, choose a folder in the system file picker, and the
shared file (or shared text, as a `.txt`) is copied there.

- Handles single and multiple shared items, one picker per item.
- Remembers the last folder per sending app and media type (image, video,
  audio, document), for locations on the device's own storage.

Requires Android 7.0 (API 24) or later.

## Building

```sh
./gradlew assembleRelease
```

Release signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` from the environment; without `KEYSTORE_FILE` the APK is left
unsigned.

CI (`.github/workflows/build.yml`) builds a signed APK on every push to `main`.
Running the workflow by hand also drafts a GitHub release. It needs these
repository secrets:

| Secret | Value |
| ------ | ----- |
| `ANDROID_KEYSTORE_BASE64` | the keystore, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |
