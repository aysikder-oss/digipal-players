# Digipal Player for Android TV — Smart Triggers Edition

This is the **Smart Triggers** variant of the Digipal Android TV player. It includes hardware sensor and button integration (Bluetooth LE, USB HID, USB serial) on top of the standard digital signage player.

> The standard app-store version of the player is maintained separately. This repository is for deployments that require physical device triggers.

## Features

- Full-screen digital signage playback on Android TV
- Pairing via on-screen code
- Auto-start on boot
- **Smart Triggers**: Bluetooth LE, USB HID, and USB serial device support
- **Camera access**: Granted at runtime for camera-based content and triggers
- Learn mode for discovering new hardware signals

## Building

1. Open this project in Android Studio
2. Sync Gradle
3. Update `SERVER_URL` in `app/build.gradle` to point to your Digipal server
4. Build and install the APK on your Android TV device

## Permissions

This app requests the following permissions:

| Permission | Purpose |
|------------|---------|
| INTERNET, ACCESS_NETWORK_STATE | Connect to Digipal server |
| WAKE_LOCK, RECEIVE_BOOT_COMPLETED | Keep screen on, auto-start on boot |
| BLUETOOTH, BLUETOOTH_SCAN, BLUETOOTH_CONNECT | BLE device detection |
| CAMERA | Camera-based content and triggers |
| USB Host | USB HID/serial device support |

All hardware features are declared as optional (`android:required="false"`) so the app can be installed on devices without those capabilities.
