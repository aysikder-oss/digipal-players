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

## First Launch (Unsigned Build)

Because the app is not signed or notarized, macOS Gatekeeper will block it on first run. The DMG includes **"Open Digipal Player.command"** — a helper script that clears the quarantine flag automatically.

**Quickest method — use the included launcher:**
1. Open the DMG and drag `Digipal Player.app` to your Applications folder (or keep it on your Desktop).
2. Copy `Open Digipal Player.command` to the **same folder** as the app.
3. Double-click `Open Digipal Player.command`. The app will open immediately.
4. After the first successful launch, you can open the app normally.

**Manual method — Terminal:**
```bash
xattr -dr com.apple.quarantine "/Applications/Digipal Player.app"
open "/Applications/Digipal Player.app"
```

**Manual method — Right-click:**
1. Right-click (or Control-click) `Digipal Player.app`
2. Choose **Open** from the context menu
3. Click **Open** in the Gatekeeper dialog

> You only need to do this once. After the first approved launch, macOS remembers the exception.

## Distribution

GitHub Actions builds unsigned `.dmg` and `.app` bundles. For signed distribution:
1. Add an Apple Developer account ($99/yr)
2. Configure code signing in Xcode project settings
3. Add notarization step to the GitHub Actions workflow
