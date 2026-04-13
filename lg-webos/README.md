# Digipal Player — LG webOS TV App

A native LG webOS TV application that runs the Digipal digital signage player on LG Smart TVs.

## Overview

This app is a lightweight webOS wrapper that loads the Digipal web player (`/tv` route) in a full-screen view on LG Smart TVs. It provides:

- First-run setup screen for entering your Digipal server URL
- Full-screen signage playback with auto-reconnect on failure
- LG remote control (Magic Remote and D-pad) navigation support
- Screen keep-alive to prevent the TV from sleeping during playback
- Proper app lifecycle handling (suspend/resume/relaunch)

## Prerequisites

1. **Node.js** (v16 or later)
2. **webOS CLI tools**: Install with `npm install -g @webos-tools/cli`
3. **LG Developer Account**: Sign up at [webOS TV Developer](https://webostv.developer.lge.com/)
4. **LG TV in Developer Mode**: Enable via the Developer Mode app from the LG Content Store

## Project Structure

```
lg-webos-app/
├── appinfo.json          # webOS app manifest
├── index.html            # App entry point (setup + player shell)
├── app.js                # Application logic
├── webOS.js              # webOS API stub (real SDK provides this on device)
├── icons/
│   ├── icon80.png        # App icon (80x80)
│   ├── icon130.png       # Large app icon (130x130)
│   └── splash.png        # Splash/background image (1920x1080)
├── .github/
│   └── workflows/
│       └── build-ipk.yml # GitHub Actions CI for automated IPK builds
└── README.md
```

## Local Development

### Testing in a Browser

Open `index.html` directly in Chrome or any modern browser. The `webOS.js` stub provides mock APIs so the app runs outside of an actual LG TV.

### Building the IPK

```bash
# From the lg-webos-app directory
ares-package . -o output/
```

This creates `com.digipal.player.webos_1.0.0_all.ipk` in the `output/` directory.

## Installing on an LG TV

### 1. Enable Developer Mode on Your TV

1. Install the **Developer Mode** app from the LG Content Store on your TV
2. Sign in with your LG developer account
3. Enable **Dev Mode** and note the IP address shown
4. Restart your TV if prompted

### 2. Set Up the CLI Connection

```bash
# Add your TV as a device
ares-setup-device

# Follow the prompts:
#   Device Name: MyTV
#   IP: <your TV's IP address>
#   Port: 9922
#   SSH User: prisoner

# Generate and install the SSH key
ares-novacom --device MyTV --getkey
```

### 3. Install the App

```bash
# Package (if not already done)
ares-package . -o output/

# Install onto your TV
ares-install --device MyTV output/com.digipal.player.webos_1.0.0_all.ipk

# Launch the app
ares-launch --device MyTV com.digipal.player.webos
```

### 4. First-Run Setup

When the app launches for the first time, you'll see a setup screen:
1. Use the LG remote to navigate to the URL input field
2. Enter your Digipal server URL (e.g., `https://your-app.replit.app`)
3. Press the **Connect** button

The app will save this URL and automatically connect on future launches.

## Updating the Server URL

To change the server URL after initial setup:
1. Clear the app's data from the TV settings, or
2. Uninstall and reinstall the app

## Automated Builds (GitHub Actions)

The included GitHub Actions workflow (`.github/workflows/build-ipk.yml`) automatically:
1. Installs the webOS CLI tools
2. Generates placeholder icons if real ones aren't provided
3. Packages the IPK
4. Creates a GitHub Release with the IPK attached

Builds trigger on pushes to `main` or via manual dispatch.

## App Icons

Replace the placeholder icons in the `icons/` directory with your actual branded assets:

| File | Size | Purpose |
|------|------|---------|
| `icon80.png` | 80×80 px | App icon in launcher |
| `icon130.png` | 130×130 px | Large icon in app details |
| `splash.png` | 1920×1080 px | Background/splash screen |

## Remote Control Keys

| Key | Action |
|-----|--------|
| Arrow Keys | Navigate UI elements |
| OK/Enter | Select / Confirm |
| Back | Return to error screen → setup (blocked during playback) |
| Play/Stop | Intercepted — does not affect the app |

## Supported webOS Versions

This app targets webOS TV 3.0+ (2016 LG Smart TVs and newer). It uses standard web technologies and should work on most recent LG TV models.

## Limitations

The following features from the Digipal player are **not available** on LG webOS TVs:
- **Smart Triggers** (hardware): Web Bluetooth, Web Serial, Web MIDI, and Web HID APIs are not supported by webOS
- **WebRTC Casting**: Not supported on the webOS browser engine
- **Camera/Microphone triggers**: Not available in webOS app context

All other player features work normally, including:
- Image, video, and PDF content playback
- Playlists and scheduling
- WebSocket real-time updates
- Offline caching
- Video wall support
- DOOH ad integration
- Broadcast/emergency alerts

## Troubleshooting

**App doesn't connect to server:**
- Verify the server URL is correct and accessible from the TV's network
- Check that the TV is connected to the internet
- The app retries every 5 seconds automatically

**Developer Mode keeps turning off:**
- LG Developer Mode expires every 50 hours. Re-enable it and restart.
- For permanent installation, submit the app to the LG Content Store.

**App crashes or shows blank screen:**
- Check the webOS TV firmware is up to date
- Use `ares-inspect --device MyTV com.digipal.player.webos` to open DevTools for debugging
