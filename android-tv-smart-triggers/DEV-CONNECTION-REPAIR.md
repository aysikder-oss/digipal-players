# Isolated DEV connection repair

This repair is for `com.nexuscast.player.offlinetest` only. It does not
change the standard Android TV app, the Smart Triggers production server,
production version numbers, or production signing.

The old DEV installer targeted an unavailable task-workspace origin. Its
HTTP 404 response was not handled by the native HTTP 5xx-only handler.

## Build

With Android SDK 36 and Java 17:

```sh
./gradlew --no-daemon --max-workers=2 \
  -PofflineTestServer=https://3922e89f-d02d-4a8b-add9-9f42fce9e5ea-00-sisthqoc0pvv.worf.replit.dev \
  :app:assembleOfflineTest :app:testOfflineTestUnitTest :app:lintOfflineTest
```

The DEV output is version 4.0.8-dev-test, code 49. No explicit port is
needed at the verified main development origin. Revalidate that origin
before future builds: development addresses are not permanent hosting.

The DEV variant catches main-document HTTP 4xx and 5xx errors, retaining
saved-shell recovery first. When recovery is unavailable it shows a native
error with Retry connection and Server settings controls instead of the
server error document. Other build variants retain their previous behavior.

## Verification and limitations

- APK build, all 75 native unit tests, and Android lint passed.
- Package/version, embedded server origin and APK signature were checked.
- A fresh browser session reached an unpaired player screen and connected.
- Fresh APK installation and native button interactions on Android are
  not yet verified. Browser verification is not a substitute for this.
- This is a debug-signed DEV installer. Upgrade signature compatibility
  with an earlier locally built DEV installer has not been established.
  If Android rejects the update, remove only the DEV app before installing.
  Removing it deletes that DEV app's local pairing/cache.
- No claim of complete offline playback validation is made by this repair.
