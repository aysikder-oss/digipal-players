# Digipal Player for Samsung TV (Tizen)

A standalone digital signage player for Samsung Smart TVs. Runs fullscreen and connects to your Digipal server automatically.

## Requirements

- Samsung Smart TV (2015 or later, Tizen OS)
- [Tizen Studio](https://developer.tizen.org/development/tizen-studio/download) installed on your PC
- Samsung TV and PC on the same network (for sideloading)

## Project Structure

```
samsung-tv-player/
├── config.xml          # Tizen app manifest
├── index.html          # App entry point (loads player from server)
├── icons/
│   └── icon_117x117.png  # App icon
└── README.md
```

## Setup

### 1. Install Tizen Studio

1. Download [Tizen Studio](https://developer.tizen.org/development/tizen-studio/download)
2. During installation, select the **TV Extension** package
3. Launch Tizen Studio after installation

### 2. Import the Project

1. Open Tizen Studio
2. Go to **File > Import > Tizen > Tizen Project**
3. Select this folder as the project root
4. The project should appear in your workspace

### 3. Configure Server URL

Edit `index.html` and change the `SERVER_URL` variable at the top of the script section:

```javascript
var SERVER_URL = 'https://digipal-cms.replit.app';
```

Replace with your Digipal server URL if different.

## Sideloading to Samsung TV

### Enable Developer Mode on the TV

1. Turn on your Samsung TV
2. Go to **Settings > Apps**
3. On the Apps screen, type **12345** using the remote (a dialog will appear)
4. Toggle **Developer Mode** to **ON**
5. Enter your PC's IP address in the **Host PC IP** field
6. Restart the TV

### Connect from Tizen Studio

1. In Tizen Studio, open **Tools > Device Manager**
2. Click **Remote Device Manager** (or the scan icon)
3. Click **+** to add a new device
4. Enter your TV's IP address (find it in TV Settings > General > Network > Network Status)
5. Click **Connect** — the TV should appear as connected

### Install the App

1. Right-click the project in Tizen Studio
2. Select **Run As > Tizen Web Application**
3. Choose your connected Samsung TV as the target
4. The app will be installed and launched automatically

The app will remain installed on the TV even after closing Tizen Studio.

## Building a .wgt Package

To create a distributable `.wgt` package (for app store submission or manual distribution):

1. Right-click the project in Tizen Studio
2. Select **Build Signed Package**
3. Create or select a signing certificate (required for distribution)
4. The `.wgt` file will be created in the project directory

## How It Works

1. The app opens fullscreen on the Samsung TV
2. It loads the Digipal player from your server (`/player.html`)
3. A pairing code is displayed on screen
4. Enter the code in your Digipal dashboard to pair the screen
5. Content starts displaying automatically

## Remote Control Shortcuts

- **Red button** — Reload the player
- **Green button** — Clear cache and reload
- **Back button** — Blocked (prevents accidental exit)

## Features

- Fullscreen display with no browser chrome
- Automatic screen wake lock (prevents TV from sleeping)
- Auto-reconnect if the server connection drops (retries every 10 seconds)
- Loading screen with connection status
- All content types supported: images, videos, websites, widgets, playlists, and live camera streams

## Troubleshooting

### TV not connecting
- Ensure the TV and PC are on the same WiFi network
- Check that Developer Mode is enabled and shows your PC's IP
- Restart both the TV and Tizen Studio

### App not loading content
- Verify the SERVER_URL in index.html is correct
- Check that the server is running and accessible from the TV's network
- Try the Red button on the remote to reload

### Developer Mode keeps turning off
- On some older models, Developer Mode resets after 24 hours
- The app stays installed — you only need Developer Mode to push updates
- For permanent deployment, consider submitting to the Samsung TV app store

## Samsung TV App Store Submission

1. Register at [Samsung Seller Office](https://seller.samsungapps.com/)
2. Create a new TV app listing
3. Upload the signed `.wgt` package
4. Provide screenshots, descriptions, and category information
5. Submit for review (typically 1-2 weeks)
