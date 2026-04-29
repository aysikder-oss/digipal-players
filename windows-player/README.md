# Digipal Player for Windows

A standalone desktop player for Digipal digital signage. Runs fullscreen and connects to your Digipal server automatically.

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
