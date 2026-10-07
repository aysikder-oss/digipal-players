# Gesture runtime compatibility

ST 4.0.3 packages the **0.10.32** MediaPipe runtime used with the deployed
`@mediapipe/tasks-vision` JavaScript SDK. Both SIMD and non-SIMD loaders/binaries
are original files from the immutable npm package. The manifest records provenance,
package integrity, resource sizes and SHA-256 hashes. The upstream Apache 2.0
license is included.

Only the four exact HTTPS jsDelivr runtime URLs under
`@mediapipe/tasks-vision@latest/wasm/` or `@mediapipe/tasks-vision@0.10.32/wasm/`
are intercepted. Other SDK versions, query strings, hosts, model files, media and
shell resources use the previous routing. Only the trusted main ST document's
subresources with trusted Origin/Referer headers can receive the packaged runtime.
Missing assets return 503, unauthorized requests return 403, and unsupported
methods return 405; none falls back to CDN latest. No WebView API is called from
the interception background thread.

`preBuild` runs `python3 scripts/check-gesture-runtime.py` for every build.
`GestureRuntimeAssetsTest` checks URL scope, origin gates, types, packaging and
explicit failures. Production artifact verification checks the four APK entries
against the same hashes. JavaScript/WASM pairing is additionally exercised by
`node --test tests/gesture-runtime.test.cjs` (no network needed).

## Compatibility changes

Review this ST runtime pin whenever the deployed JavaScript SDK version changes.
Do not silently map requests for another explicit SDK version to 0.10.32. Updating
the package assets, manifest and compatibility checks must be a deliberate ST
release, not a shared player or standard Android TV change.

## Verification boundary

Build and emulator results are not proof of camera-based gesture recognition.
No physical ST hardware or customer camera access is available in this workspace.
Release delivery therefore does **not** claim recognizer startup or trigger
activation has been verified on a physical Android ST device.

On an authorized ST test device with a camera and configured thumbs-up/peace
trigger, install the production ST APK without uninstalling or clearing data:

1. Confirm camera readiness (nonzero dimensions) and the successful recognizer
   initialization log, with no MediaPipe export-assignment error.
2. Present thumbs-up or peace; confirm the configured target content activates.
3. Remove the gesture; confirm normal/fallback playback returns.
4. Repeat inside the configured cooldown; confirm it does not activate again.
5. Repeat after cooldown; confirm activation and normal playback recovery.
6. Repeat through native image/video transitions; verify detection remains active.

Record device/WebView version, APK version, camera readiness, startup, activation,
cooldown and recovery outcomes separately. Do not access customer camera images
or issue production device commands without separate authorization.
