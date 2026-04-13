# Digipal Launcher for Android TV

A dedicated Android launcher app that replaces the default home screen on Android TV boxes, booting directly into the Digipal signage player. This launcher:

- **Registers as a device launcher** — Android offers it as the default home screen
- **Uses the system WebView** — lightweight (~5 MB APK), leverages the device's Chrome/WebView for modern rendering
- **Maximizes hardware resources** — includes scripts to disable competing Google services and bloatware
- **Kiosk mode** — full immersive mode with button blocking, wake lock, and auto-relaunch

The goal is to turn any cheap Android TV box into a dedicated signage appliance where 85-90% of device resources go to the player.

## Hardware Requirements

- Android TV box running Android 5.0+ (API 21+)
- Minimum 1 GB RAM (2 GB recommended)
- Network connection (WiFi or Ethernet)
- ADB access for installation and setup
- Chrome or Android System WebView updated to a recent version (one-time setup)

## APK Size

The APK is approximately **5 MB** — it uses the system WebView rather than bundling a separate browser engine.

## Building

### Prerequisites
- JDK 17
- Android SDK with API 34

### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/aysikder-oss/digipal-android-launcher.git
   cd digipal-android-launcher
   ```

2. Build the debug APK:
   ```bash
   chmod +x gradlew
   ./gradlew assembleDebug
   ```

3. The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

4. For a release build:
   ```bash
   ./gradlew assembleRelease
   ```

## Installation via ADB

1. Connect to your Android TV box via ADB:
   ```bash
   adb connect <device-ip>:5555
   ```

2. Install the APK:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Set as the default launcher:
   ```bash
   adb shell cmd package set-home-activity com.nexuscast.launcher/.MainActivity
   ```

   Alternatively, press the Home button on your remote — Android will ask which launcher to use. Select "Digipal Launcher" and choose "Always".

## Device Setup Script

The setup script disables Google TV services and bloatware to free RAM and CPU for signage playback.

### Running the Setup

```bash
cd scripts

# Basic setup - disables Google TV launchers and common bloatware
./setup-device.sh

# Full setup - also disables Bluetooth, NFC, and microphone/assistant
./setup-device.sh --all

# Selective optional disabling
./setup-device.sh --disable-bluetooth --disable-nfc
```

### What Gets Disabled

**Google TV Launchers:**
- `com.google.android.tvlauncher` — Google TV Home
- `com.google.android.tvrecommendations` — Content recommendations
- `com.google.android.leanbacklauncher` — Leanback launcher

**Google TV Bloatware:**
- YouTube, YouTube Music, Play Movies, Play Games
- Google Play Store, Voice Search, TV Suggestions
- Screensavers and daydream services

**System Bloatware:**
- Calendar, Email, Contacts, Dialer, Messaging
- Gallery, Music player, Print service

**Optional Services (with flags):**
- Bluetooth and TV Remote (`--disable-bluetooth`)
- NFC and Google Files (`--disable-nfc`)
- Google Assistant and TTS (`--disable-microphone`)

## Restoring the Device

To re-enable all disabled packages and restore the device to its original state:

```bash
cd scripts
./restore-device.sh
```

Then reboot the device:
```bash
adb reboot
```

## Diagnostics Screen

A built-in diagnostics screen is accessible via hidden gestures:

- **Touch:** 5 rapid taps on the top-right corner of the screen
- **D-pad/Remote:** Press the UP button 5 times rapidly (within 3 seconds)

The diagnostics screen shows:
- Browser engine (Android WebView)
- Android version and API level
- Device manufacturer and model
- Available / total RAM
- GPU renderer info
- Network status (WiFi/Ethernet/None)
- Player URL
- App version

From the diagnostics screen, you can also access **WiFi Settings** to change the device's WiFi network without needing ADB or a laptop.

A **WiFi Settings** button also appears on the error screen when the device loses network connectivity, so users can fix their WiFi connection directly.

Press the "Close" button to return to the player.

## Features

- **Kiosk Mode** — Full immersive mode with no status bar, navigation bar, or way to exit without ADB
- **Wake Lock** — Screen stays on 24/7 with `SCREEN_BRIGHT_WAKE_LOCK`
- **Sustained Performance** — Requests sustained performance mode to prevent thermal throttling
- **Button Blocking** — Back, Home, Recent Apps, and Menu buttons are all intercepted
- **Auto-Relaunch** — Watchdog mechanism restarts the app if it crashes (AlarmManager-based)
- **Boot on Power** — Automatically launches on device boot via `BOOT_COMPLETED` receiver
- **Network Resilience** — Detects network loss, shows offline screen, auto-reloads when network returns
- **WebView Engine** — Uses the system WebView (backed by Chrome) for lightweight, modern web rendering

## Architecture

```
android-launcher-app/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml        # Launcher intent filters, permissions
│       ├── java/com/nexuscast/launcher/
│       │   ├── MainActivity.java      # Main launcher with WebView, diagnostics, kiosk mode
│       │   ├── BootReceiver.java      # Auto-start on device boot
│       │   └── WatchdogService.java   # Background crash recovery service
│       └── res/
│           └── values/
│               ├── styles.xml         # App themes
│               └── strings.xml        # App strings
├── scripts/
│   ├── setup-device.sh               # Disable bloatware and Google TV services
│   └── restore-device.sh             # Re-enable all disabled packages
├── .github/workflows/
│   └── build.yml                     # GitHub Actions CI for automated APK builds
├── build.gradle                      # Root Gradle config
├── app/build.gradle                  # App Gradle config
└── README.md                         # This file
```

## GitHub Repository

This code is mirrored at: https://github.com/aysikder-oss/digipal-android-launcher

## Comparison with Standard Player App

| Feature | Standard Player (`android-tv-app`) | Launcher (`android-launcher-app`) |
|---------|-----------------------------------|----------------------------------|
| Browser Engine | System WebView | System WebView |
| APK Size | ~5 MB | ~5 MB |
| Launcher Registration | No (app only) | Yes (replaces home screen) |
| Device Setup Scripts | No | Yes |
| Boot Behavior | Optional auto-relaunch | Always launches on boot |
| Kiosk Mode | Basic (blocks Back) | Full (blocks Back, Home, Recent, Menu) |
| Network Monitor | Basic retry | ConnectivityManager callback + retry |
| Diagnostics | No | Yes (5-tap or D-pad gesture) |
| Watchdog Service | No | Yes (background crash recovery) |

## License

Proprietary — Digipal Signage Platform
