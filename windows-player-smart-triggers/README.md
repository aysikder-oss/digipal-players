# Digipal Player for Windows — Smart Triggers Edition

This is the **Smart Triggers** variant of the Digipal Windows player. It includes hardware sensor and button integration (USB HID, serial port, Bluetooth LE via Web Bluetooth) on top of the standard Electron-based digital signage player.

> The standard app-store version of the player is maintained separately. This repository is for deployments that require physical device triggers.

## Quick Start (No Build Required)

1. Install [Node.js](https://nodejs.org/) (v18 or later)
2. Open a terminal in this folder
3. Run:
   ```
   npm install
   npm start
   ```
4. The player launches fullscreen and shows a pairing code
5. Go to your Digipal dashboard > Screens > Pair New Screen

## Build a Standalone .exe

To create an installer or portable .exe that doesn't require Node.js:

1. Install [Node.js](https://nodejs.org/) (v18 or later)
2. Open a terminal in this folder
3. Run:
   ```
   npm install
   npm run build
   ```
4. Find the installer in the `dist/` folder

For a portable .exe (no installation needed):
```
npm run build:portable
```

## Features

- Full-screen digital signage playback
- Pairing via on-screen code
- **Smart Triggers**: USB HID, serial port, MIDI (e.g. Playtronica), and Bluetooth LE device support
- **Camera & microphone access**: Automatically granted for media-based content and triggers
- Learn mode for discovering new hardware signals

## Keyboard Shortcuts

- **F11** — Toggle fullscreen
- **F5** — Refresh player
- **Ctrl+Shift+I** — Open developer tools
- **Escape** — Exit fullscreen (when not in kiosk mode)

## Configuration

The server URL is pre-configured in `main.js`. If you need to change it, edit the `SERVER_URL` variable at the top of the file.

## Auto-Start on Boot

To make the player start automatically when Windows boots:

1. Press `Win + R`, type `shell:startup`, press Enter
2. Create a shortcut to `DigipalPlayer.exe` (or the `npm start` command) in this folder
3. Paste the shortcut into the startup folder
