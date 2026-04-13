# Digipal Linux Player

Electron-based digital signage player for Linux, targeting Ubuntu, Debian, Fedora, and Raspberry Pi.

## Requirements

- Node.js 20+
- Linux x64 or arm64 (Raspberry Pi 4/5)

## Installation

### Ubuntu / Debian (.deb)
```bash
sudo dpkg -i digipal-player_1.0.0_amd64.deb
sudo apt-get install -f
```

### Fedora / RHEL (.rpm)
```bash
sudo rpm -i digipal-player-1.0.0.x86_64.rpm
```

### AppImage (any distro)
```bash
chmod +x Digipal-Player-1.0.0.AppImage
./Digipal-Player-1.0.0.AppImage
```

### Raspberry Pi (.deb arm64)
```bash
sudo dpkg -i digipal-player_1.0.0_arm64.deb
sudo apt-get install -f
```

## Building from Source

```bash
npm install
npm run build          # All Linux targets
npm run build:deb      # Debian/Ubuntu only
npm run build:appimage # AppImage only
npm run build:rpm      # RPM only
npm run build:arm64    # ARM64 targets (Raspberry Pi)
```

## Features

- **Electron Player** — Loads the Digipal `/tv` route with full web player functionality
- **Bonjour/mDNS Discovery** — Auto-discovers local Digipal Hubs (`_digipal._tcp`) on the LAN via `bonjour-service`
- **Local Media Caching** — Downloads content to `~/.config/digipal-linux-player/media/` for offline playback
- **Kiosk Mode** — Fullscreen with always-on-top, hides taskbar, prevents Alt+F4 (Ctrl+Alt+K)
- **Cursor Auto-Hide** — Cursor hides after 3 seconds of inactivity
- **JavaScript Bridge** — Exposes `window.Android` API for media management (same protocol as Windows/Android)
- **System Tray** — Status icon with server info, kiosk toggle, reload, and quit
- **Auto-Start** — XDG autostart desktop entry for unattended signage
- **Auto-Retry** — Reconnects with exponential backoff and countdown overlay on connection failure

## Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| F11 | Toggle fullscreen |
| F5 | Reload page |
| Ctrl+Shift+I | Developer tools |
| Ctrl+Shift+S | Change server URL |
| Ctrl+Alt+K | Toggle kiosk mode |
| Escape | Exit fullscreen (non-kiosk) |

## Architecture

```
linux-player/
├── main.js                    # Electron main process (window, tray, kiosk, IPC, cursor auto-hide)
├── preload.js                 # Context bridge exposing window.Android JS bridge
├── media-manager.js           # Local media cache (download, manifest, LRU eviction)
├── bonjour-browser.js         # mDNS browser for _digipal._tcp hub discovery
├── prompt.html                # Server URL setup UI with discovered hubs list
├── digipal-player.service     # systemd user service for auto-start
├── digipal-player.desktop     # XDG desktop entry
├── icon.png                   # App icon
└── package.json               # Build config for deb/AppImage/rpm on x64 and arm64
```

## Auto-Start Setup

### XDG Autostart (Desktop environments)
The app can create an XDG autostart entry automatically when auto-start is enabled via the web admin panel. Alternatively, copy the desktop entry manually:

```bash
mkdir -p ~/.config/autostart
cp /usr/share/applications/digipal-linux-player.desktop ~/.config/autostart/digipal-player.desktop
```

### systemd User Service (Headless/kiosk)
For kiosk deployments without a full desktop environment:

```bash
mkdir -p ~/.config/systemd/user
cp digipal-player.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable digipal-player
systemctl --user start digipal-player
```

## Raspberry Pi Setup

### Recommended Hardware
- Raspberry Pi 4 (4GB+) or Raspberry Pi 5
- Use the arm64 .deb package or AppImage
- 64-bit Raspberry Pi OS recommended

### GPU Memory Split
Increase GPU memory for smooth video playback. Edit `/boot/config.txt`:
```
gpu_mem=256
```

### Disable Screen Blanking
Prevent the display from sleeping:
```bash
# X11
xset s off
xset -dpms
xset s noblank

# Or add to /etc/xdg/lxsession/LXDE-pi/autostart:
@xset s off
@xset -dpms
@xset s noblank
```

### Disable Screen Saver
```bash
# Edit /etc/lightdm/lightdm.conf, under [Seat:*]:
xserver-command=X -s 0 -dpms
```

### Auto-Login (Kiosk Deployment)
```bash
sudo raspi-config
# > System Options > Boot / Auto Login > Desktop Autologin
```

### Hardware Video Acceleration
Electron uses Chromium's hardware acceleration. On Raspberry Pi:
```bash
# Verify GPU acceleration is available
glxinfo | grep "direct rendering"

# If using Wayland (Pi 5), XWayland is used automatically
```

### Boot Config for Signage
Add to `/boot/config.txt` for optimal signage performance:
```
gpu_mem=256
disable_overscan=1
hdmi_force_hotplug=1
hdmi_group=2
hdmi_mode=82
```
