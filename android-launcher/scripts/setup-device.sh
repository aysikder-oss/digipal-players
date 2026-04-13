#!/bin/bash
#
# Digipal Launcher - Device Setup Script
# Disables Google TV services and bloatware to free RAM/CPU for signage playback.
#
# Usage: ./setup-device.sh [OPTIONS]
#   --disable-bluetooth    Also disable Bluetooth services
#   --disable-nfc          Also disable NFC services
#   --disable-microphone   Also disable microphone/assistant services
#   --all                  Disable all optional services
#
# Requires: ADB connected to the target device
#

set -e

DISABLE_BLUETOOTH=false
DISABLE_NFC=false
DISABLE_MICROPHONE=false

for arg in "$@"; do
    case $arg in
        --disable-bluetooth) DISABLE_BLUETOOTH=true ;;
        --disable-nfc) DISABLE_NFC=true ;;
        --disable-microphone) DISABLE_MICROPHONE=true ;;
        --all) DISABLE_BLUETOOTH=true; DISABLE_NFC=true; DISABLE_MICROPHONE=true ;;
        --help|-h)
            echo "Usage: ./setup-device.sh [OPTIONS]"
            echo "  --disable-bluetooth    Disable Bluetooth services"
            echo "  --disable-nfc          Disable NFC services"
            echo "  --disable-microphone   Disable microphone/assistant services"
            echo "  --all                  Disable all optional services"
            exit 0
            ;;
    esac
done

echo "======================================"
echo "  Digipal Launcher - Device Setup"
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

DISABLED=()
SKIPPED=()

disable_package() {
    local pkg=$1
    local desc=$2
    if adb shell pm list packages | grep -q "$pkg"; then
        adb shell pm disable-user --user 0 "$pkg" 2>/dev/null && {
            echo "  ✓ Disabled: $pkg ($desc)"
            DISABLED+=("$pkg")
        } || {
            echo "  ✗ Failed:   $pkg ($desc)"
            SKIPPED+=("$pkg")
        }
    else
        echo "  - Skipped:  $pkg (not installed)"
        SKIPPED+=("$pkg")
    fi
}

echo "--- Disabling Google TV Launchers ---"
disable_package "com.google.android.tvlauncher" "Google TV Home launcher"
disable_package "com.google.android.tvrecommendations" "Google TV content recommendations"
disable_package "com.google.android.leanbacklauncher" "Android TV Leanback launcher"
disable_package "com.google.android.leanbacklauncher.recommendations" "Leanback recommendations"

echo ""
echo "--- Disabling Google TV Bloatware ---"
disable_package "com.google.android.apps.tv.launcherx" "Google TV launcher (new version)"
disable_package "com.google.android.katniss" "Google TV voice search"
disable_package "com.google.android.tvsuggestions" "TV content suggestions"
disable_package "com.google.android.videos" "Google Play Movies & TV"
disable_package "com.google.android.youtube.tv" "YouTube for TV"
disable_package "com.google.android.youtube.tvmusic" "YouTube Music for TV"
disable_package "com.google.android.play.games" "Google Play Games"
disable_package "com.google.android.apps.tv.dreamx" "Google screensaver/daydream"
disable_package "com.google.android.backdrop" "Google Backdrop (screensaver)"
disable_package "com.android.vending" "Google Play Store"

echo ""
echo "--- Disabling System Bloatware ---"
disable_package "com.android.providers.calendar" "Calendar provider"
disable_package "com.android.calendar" "Calendar app"
disable_package "com.android.email" "Email app"
disable_package "com.android.contacts" "Contacts app"
disable_package "com.android.dialer" "Phone dialer"
disable_package "com.android.messaging" "Messaging app"
disable_package "com.android.printspooler" "Print service"
disable_package "com.android.gallery3d" "Gallery app"
disable_package "com.android.music" "Music player"

if [ "$DISABLE_BLUETOOTH" = true ]; then
    echo ""
    echo "--- Disabling Bluetooth Services ---"
    disable_package "com.android.bluetooth" "Bluetooth service"
    disable_package "com.google.android.apps.tv.remote" "Android TV Remote"
fi

if [ "$DISABLE_NFC" = true ]; then
    echo ""
    echo "--- Disabling NFC Services ---"
    disable_package "com.android.nfc" "NFC service"
    disable_package "com.google.android.apps.nbu.files" "Google Files"
fi

if [ "$DISABLE_MICROPHONE" = true ]; then
    echo ""
    echo "--- Disabling Microphone/Assistant Services ---"
    disable_package "com.google.android.googlequicksearchbox" "Google app (voice assistant)"
    disable_package "com.google.android.tts" "Google Text-to-Speech"
    disable_package "com.google.android.apps.googleassistant" "Google Assistant"
fi

echo ""
echo "======================================"
echo "  Setup Summary"
echo "======================================"
echo "  Packages disabled: ${#DISABLED[@]}"
echo "  Packages skipped:  ${#SKIPPED[@]}"
echo ""
echo "  To set Digipal Launcher as default:"
echo "    adb shell cmd package set-home-activity com.nexuscast.launcher/.MainActivity"
echo ""
echo "  To restore disabled packages, run:"
echo "    ./restore-device.sh"
echo "======================================"
