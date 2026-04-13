#!/bin/bash
set -e

APP_DIR="/opt/Digipal Player"
RESOURCES_DIR="$APP_DIR/resources"

SYSTEMD_SERVICE_SRC="$RESOURCES_DIR/digipal-player.service"

if [ -f "$SYSTEMD_SERVICE_SRC" ]; then
  echo ""
  echo "=== Digipal Player Installed ==="
  echo ""
  echo "To enable auto-start on boot as a systemd user service, run:"
  echo "  mkdir -p ~/.config/systemd/user"
  echo "  cp \"$SYSTEMD_SERVICE_SRC\" ~/.config/systemd/user/"
  echo "  systemctl --user daemon-reload"
  echo "  systemctl --user enable digipal-player"
  echo "  systemctl --user start digipal-player"
  echo ""
fi

exit 0
