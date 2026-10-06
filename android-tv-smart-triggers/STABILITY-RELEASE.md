# Smart Triggers production stability release

## Scope and signing

This backport modifies only `android-tv-smart-triggers/**` and its dedicated
build/test/release workflow. The standard Android TV tree is checked against the
captured known-good baseline. Shared Replit application code is read-only.

The product owner confirmed that Smart Triggers has not been used by customers.
Its historical customer-facing downloads were debug builds with differing
certificates. There is therefore no customer migration to execute. Establish a
new persistent **ST-only** signing identity; do not use or change standard-player
signing secrets. Production remains `com.nexuscast.player`; debug/test builds use
`com.nexuscast.player.debug`.

Dedicated `ST_KEYSTORE_*`, `ST_KEY_ALIAS`, `ST_KEY_PASSWORD` and
`ST_SIGNING_CERT_SHA256` secrets persist encrypted in GitHub Actions. Never print
their contents, publish them as artifacts, or regenerate them for each build.
Release gates validate the expected public certificate and refuse missing
signing setup. An authorized owner should arrange a secure vault backup of this
identity before wider customer rollout; GitHub cannot reveal stored secret
values through its API.

An old developer/test APK may reject an in-place update because of its different
signer. No automatic uninstall, data wipe or device command is included.

## Native/trigger behavior

Applicable standard protections are backported into the ST package: authenticated
bridge signatures, scoped image callbacks, revision/fingerprint scheduler,
renderer cleanup/first-frame ownership, offline media/cache/shell recovery,
heartbeat revision consumption, mount acknowledgement, boot/package replacement
recovery, safe files/URLs, diagnostics and mapped crash support.

USB/BLE HardwareManager remains ST-only. The injected smartTriggers adapter uses
the shared player's existing `hw:*` DOM events exactly once. It supplies current
page tokens, keyboard learn capture, configured hardware lifecycle and explicit
BLE scanning. Camera/microphone grants are restricted to the configured trusted
shell origin and granted Android runtime permissions; unsupported resources and
untrusted frames are denied. Sensors are not implicitly enabled by the adapter.

During native playback, ST retains a VISIBLE, transparent main WebView and never
pauses its process-wide timers or recreates its shell per slide. This preserves
camera/audio processing, hardware events and pending trigger cooldown/revert
timers. Existing shared-player ML throttling reduces detection under high
pressure and suspends ML at critical pressure; native renderers retain their
own memory/cache protections. Isolated-renderer rollout capability remains off,
so shared web rendering remains the safe default for design/kiosk/URL/PDF.

## Verification boundary

ST-only CI runs native scheduler/recovery/safety and permission tests, JS adapter
regressions, a real Android emulator WebView bridge test, signed release APK/AAB
builds, protected-tree checks, package/version/SDK checks and APK signing/16-KB
native-library/ZIP checks.

No physical Android TV, USB/BLE peripheral, microphone or camera is attached to
the engineering environment. Physical-device sensor interaction, denied/retried
permissions, reconnects, trigger priority/queue actions, offline evaluation and
low-memory overnight soak behavior must not be claimed from a compiler or
headless screenshot. Validate those on a ST test device before customer rollout.
The APK requires installation; publishing the Replit cloud app is not a native
APK update.
