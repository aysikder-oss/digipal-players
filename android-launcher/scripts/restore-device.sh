#!/bin/bash
#
# Digipal Launcher - Device Restore Script
# Re-enables all packages that were disabled by setup-device.sh.
#
# Usage: ./restore-device.sh
#
# Requires: ADB connected to the target device
#

set -e

echo "======================================"
echo "  Digipal Launcher - Device Restore"
echo "======================================"
echo ""

if ! command -v adb &> /dev/null; then
    echo "ERROR: ADB not found. Please install Android SDK Platform Tools."
    exit 1
fi

DEVICE_COUNT=$(adb devices | grep -c "device$")
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: No ADB device connected."
    exit 1
fi

echo "Connected device: $(adb shell getprop ro.product.model)"
echo "Android version:  $(adb shell getprop ro.build.version.release)"
echo ""

RESTORED=()
SKIPPED=()

enable_package() {
    local pkg=$1
    local desc=$2
    if adb shell pm list packages -d | grep -q "$pkg"; then
        adb shell pm enable --user 0 "$pkg" 2>/dev/null && {
            echo "  ✓ Restored: $pkg ($desc)"
            RESTORED+=("$pkg")
        } || {
            echo "  ✗ Failed:   $pkg ($desc)"
            SKIPPED+=("$pkg")
        }
    else
        echo "  - Skipped:  $pkg (already enabled or not installed)"
        SKIPPED+=("$pkg")
    fi
}

echo "--- Restoring Google TV Launchers ---"
enable_package "com.google.android.tvlauncher" "Google TV Home launcher"
enable_package "com.google.android.tvrecommendations" "Google TV content recommendations"
enable_package "com.google.android.leanbacklauncher" "Android TV Leanback launcher"
enable_package "com.google.android.leanbacklauncher.recommendations" "Leanback recommendations"

echo ""
echo "--- Restoring Google TV Apps ---"
enable_package "com.google.android.apps.tv.launcherx" "Google TV launcher (new version)"
enable_package "com.google.android.katniss" "Google TV voice search"
enable_package "com.google.android.tvsuggestions" "TV content suggestions"
enable_package "com.google.android.videos" "Google Play Movies & TV"
enable_package "com.google.android.youtube.tv" "YouTube for TV"
enable_package "com.google.android.youtube.tvmusic" "YouTube Music for TV"
enable_package "com.google.android.play.games" "Google Play Games"
enable_package "com.google.android.apps.tv.dreamx" "Google screensaver/daydream"
enable_package "com.google.android.backdrop" "Google Backdrop (screensaver)"
enable_package "com.android.vending" "Google Play Store"

echo ""
echo "--- Restoring System Apps ---"
enable_package "com.android.providers.calendar" "Calendar provider"
enable_package "com.android.calendar" "Calendar app"
enable_package "com.android.email" "Email app"
enable_package "com.android.contacts" "Contacts app"
enable_package "com.android.dialer" "Phone dialer"
enable_package "com.android.messaging" "Messaging app"
enable_package "com.android.printspooler" "Print service"
enable_package "com.android.gallery3d" "Gallery app"
enable_package "com.android.music" "Music player"

echo ""
echo "--- Restoring Optional Services ---"
enable_package "com.android.bluetooth" "Bluetooth service"
enable_package "com.google.android.apps.tv.remote" "Android TV Remote"
enable_package "com.android.nfc" "NFC service"
enable_package "com.google.android.apps.nbu.files" "Google Files"
enable_package "com.google.android.googlequicksearchbox" "Google app (voice assistant)"
enable_package "com.google.android.tts" "Google Text-to-Speech"
enable_package "com.google.android.apps.googleassistant" "Google Assistant"

echo ""
echo "======================================"
echo "  Restore Summary"
echo "======================================"
echo "  Packages restored: ${#RESTORED[@]}"
echo "  Packages skipped:  ${#SKIPPED[@]}"
echo ""
echo "  To reset the default launcher:"
echo "    adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity"
echo ""
echo "  You may need to reboot the device:"
echo "    adb reboot"
echo "======================================"
