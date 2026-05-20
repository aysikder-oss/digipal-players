#!/bin/bash
# First-launch helper for Digipal Player
# Removes the macOS quarantine flag so Gatekeeper won't block the app,
# then opens it. Double-click this file on your first install.

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
APP="$SCRIPT_DIR/Digipal Player.app"

if [ ! -d "$APP" ]; then
  osascript -e 'display alert "Digipal Player not found" message "Make sure Digipal Player.app is in the same folder as this script." as critical'
  exit 1
fi

# Remove quarantine attribute added by macOS when downloading unsigned apps
xattr -d com.apple.quarantine "$APP" 2>/dev/null || true
xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true

open "$APP"
