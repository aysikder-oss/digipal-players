# Digipal Vega Player

React Native player app for Amazon Fire TV (Vega OS) built with Amazon's Vega SDK (kepler).

## Prerequisites

**Operating system:** macOS 10.15+ or Ubuntu 20.04+
Windows and WSL are not supported by the Vega SDK.

**Required tools:**
- Node.js v16+
- VS Code (recommended — Vega Studio runs as a VS Code extension)
- Amazon Developer account (free) at developer.amazon.com

**macOS additional dependencies:**
```bash
[[ $(arch) == "arm64" ]] && softwareupdate --install-rosetta --agree-to-license
brew update && brew install binutils coreutils gawk findutils grep jq lz4 gnu-sed watchman
```

**Ubuntu additional dependencies:**
```bash
# Remove snap curl if present, install native curl
sudo apt remove curl && sudo apt install curl

# Install Python and lz4
(dpkg -l | grep -q lz4 || sudo apt install -y lz4) && \
sudo add-apt-repository -y ppa:deadsnakes/ppa && \
sudo apt update && \
(dpkg -l | grep -q libpython3.8-dev || sudo apt install -y libpython3.8-dev)

# Install watchman (follow https://facebook.github.io/watchman/docs/install)
```

## Step 1 — Install the Vega SDK

1. Sign in to your Amazon Developer account at [developer.amazon.com](https://developer.amazon.com)
2. Go to the Vega SDK page and download the installer
3. Close VS Code before running the installer
4. Run the installer and follow the on-screen instructions
5. When prompted, press Enter to use the default install path (`~/kepler/sdk/`)
6. After install, add the export commands to your shell config:

```bash
# Add to ~/.zshrc (macOS) or ~/.bashrc (Ubuntu)
export KEPLER_SDK_PATH=~/kepler/sdk/<version-number>
export PATH=$KEPLER_SDK_PATH/bin:$PATH
```

7. Apply the config: `source ~/.zshrc` (or `~/.bashrc`)
8. Verify: `kepler --version`

## Step 2 — Set up the project

```bash
cd vega-player
npm install
```

Confirm the server URL in `src/utils/constants.ts`:
```ts
export const SERVER_URL = 'https://digipalsignage.com';
```

## Step 3 — Build the app

```bash
# Development build / run on connected Fire TV device
kepler run-android

# Production build — produces DigipalPlayer.vpkg
kepler build --release
```

The `.vpkg` file will be in the build output directory. Submit this file to the Amazon Appstore.

## Step 4 — Test on device (sideload)

```bash
# Connect to your Fire TV over network
adb connect <fire-tv-ip-address>

# Install the build
kepler install
```

Or manually via adb:
```bash
adb install <path-to-output>.vpkg
```

## How the app works

1. **First launch** — shows a pairing screen with a 6-character code
2. **Pair** — go to your Digipal dashboard → Screens → Pair New Screen → enter the code
3. **Plays content** — after pairing, loads the Digipal TV player fullscreen
4. **Hidden menu** — tap the screen 5 times quickly to get reload/unpair options
5. **Auto-start on boot** — BootReceiver launches the app when the device powers on

## Project structure

```
vega-player/
├── App.tsx                          # Root component, switches pairing ↔ player
├── src/
│   ├── hooks/
│   │   └── useScreenPairing.ts     # Pairing logic: fetch code, poll, persist
│   ├── screens/
│   │   ├── PairingScreen.tsx       # Pairing code UI
│   │   └── PlayerScreen.tsx        # Fullscreen WebView content player
│   └── utils/
│       └── constants.ts            # Server URL, storage keys
└── android/
    └── app/src/main/
        ├── AndroidManifest.xml     # Permissions, Leanback TV, boot receiver
        └── java/com/digipalvega/
            └── BootReceiver.kt     # Auto-launch on device boot
```

## Vega SDK version

Built against Vega SDK 0.21 (open beta). Check [release notes](https://developer.amazon.com/docs/vega/latest/vega-release-notes.html) for updates when using a newer SDK version.
