# Digipal macOS Player

Native macOS digital signage player app built with Swift/SwiftUI and WKWebView.

## Requirements

- macOS 13.0 (Ventura) or later
- Xcode 15.0 or later (for building)

## Building

### From Xcode
1. Open `DigipalPlayer/DigipalPlayer.xcodeproj` in Xcode
2. Select the "DigipalPlayer" scheme
3. Build & Run (⌘R)

### From Command Line
```bash
cd DigipalPlayer
xcodebuild -project DigipalPlayer.xcodeproj \
  -scheme DigipalPlayer \
  -configuration Release \
  archive
```

## Features

- **WKWebView Player** — Loads the Digipal `/tv` route with full web player functionality
- **Bonjour Discovery** — Auto-discovers local Digipal Hubs (`_digipal._tcp`) on the LAN
- **Local Media Caching** — Downloads content to `~/Library/Application Support/DigipalPlayer/media/` for offline playback
- **Kiosk Mode** — Fullscreen with hidden menu bar/dock, prevents quit shortcuts
- **JavaScript Bridge** — Exposes `window.Android` API (compatible with existing player protocol) for media management
- **System Tray** — Status bar icon with connection status, quick access to setup and kiosk mode
- **Auto-Start** — Optional login item to start on boot

## Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| ⌘⇧S | Open server setup |
| ⌘⇧F | Toggle fullscreen |
| ⌘⇧I | Web inspector |

## Architecture

```
DigipalPlayer/
├── DigipalPlayerApp.swift    # App entry point, window and menu config
├── AppDelegate.swift         # System tray, kiosk mode, auto-start
├── AppState.swift            # Observable app state (server URL, connection)
├── Views/
│   ├── ContentView.swift     # Root view (setup vs player)
│   ├── SetupView.swift       # Server URL input + Bonjour browser
│   └── PlayerWebView.swift   # WKWebView wrapper + JS bridge + URL scheme handler
└── Services/
    ├── MediaManager.swift    # Local media cache (download, manifest, LRU eviction)
    └── BonjourBrowser.swift  # mDNS browser for _digipal._tcp
```

## Distribution

GitHub Actions builds unsigned `.dmg` and `.app` bundles. For signed distribution:
1. Add an Apple Developer account ($99/yr)
2. Configure code signing in Xcode project settings
3. Add notarization step to the GitHub Actions workflow
