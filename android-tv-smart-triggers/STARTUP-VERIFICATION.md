# Smart Triggers startup repair verification

This candidate preserves the existing ST production signer and package. It does
not change the standard Android TV app. The companion web changes must be
available to the device before testing; the APK alone cannot repair the shared
web microphone lifecycle.

## Automated evidence

- Native media permissions serialize missing camera/microphone permissions and
  hardware requests, while already-authorized media capture remains available.
- Cancellation, navigation, destruction and permission timeouts do not grant
  old web requests or start an old Bluetooth scan.
- The web startup checks independently cover delivery, model initialization,
  acquisition, non-zero camera frames and first inference.
- Late microphone streams are stopped after cancellation; old contexts and
  detection results cannot replace the current session.
- SDK/runtime resource checks, production signing/lint/artifact validation and
  Android test compilation are separate from physical gesture confirmation.

## Physical test gate (not yet performed)

Use the existing paired ST test device. Do not uninstall, clear data, reset
permissions or change customer assignments. Obtain explicit approval before
changing production trigger settings or remotely restarting the device.

For each of these four configurations, test a cold launch and a player restart:

1. Gesture only.
2. Gesture and button (sensor).
3. Gesture and music (sound).
4. Gesture, button and music together.

During pending startup, enable and disable each additional input, then disable
all inputs and enable gesture again. Check that each startup reaches a non-zero
camera frame and a completed first inference. Perform the configured gesture
and verify both the trigger activation and the assigned target content.
Repeat during normal native video/image playback and check playback stability.

When camera permission/acquisition, camera frames, model delivery or model
initialization is unavailable, verify the reported stage fails within its
deadline instead of claiming Monitoring. Verify a disabled microphone releases
capture and cannot be recreated by a late permission result.

Record only timestamps, version, stage/state/error names, resource-delivery
status and whether content activation occurred. Do not collect imagery,
recordings, device labels, biometric details or secrets. A healthy heartbeat,
model download, empty inference or successful build is not physical proof of
working gesture activation.
