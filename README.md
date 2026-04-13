# Digipal Players — Monorepo

  All Digipal digital signage player apps in one repository.

  ## Players

  | Platform | Directory | Description |
  |----------|-----------|-------------|
  | **Linux** | `linux/` | Electron-based player (x64 + arm64) |
  | **Windows** | `windows/` | Electron-based player |
  | **Windows (Smart Triggers)** | `windows-smart-triggers/` | Windows + USB/BLE sensor integration |
  | **macOS** | `macos/` | Native SwiftUI player |
  | **Android TV** | `android-tv/` | WebView-based player for Android TV |
  | **Android TV (Smart Triggers)** | `android-tv-smart-triggers/` | Android TV + camera/sensor integration |
  | **Android Launcher** | `android-launcher/` | Dedicated kiosk launcher for Android TV boxes |
  | **Fire TV (Vega)** | `vega/` | React Native player for Amazon Fire TV |
  | **Samsung Tizen** | `samsung-tizen/` | Web app for Samsung Smart TVs |
  | **LG webOS** | `lg-webos/` | Web app for LG Smart TVs |

  ## CI/CD Setup

  Workflow files are in `ci-workflows/`. To activate CI:

  ```bash
  # Clone the repo and copy workflows to the GitHub Actions directory
  git clone https://github.com/aysikder-oss/digipal-players.git
  cd digipal-players
  mkdir -p .github/workflows
  cp ci-workflows/*.yml .github/workflows/
  git add .github/workflows
  git commit -m "ci: activate all platform build workflows"
  git push
  ```

  Each workflow triggers only when files in its platform directory change:
  - Push to `linux/` → builds Linux .deb, .AppImage, .rpm
  - Push to `windows/` → builds Windows .exe installer
  - Push to `macos/` → builds macOS .dmg
  - Push to `android-tv/` → builds Android .apk
  - Push to `android-launcher/` → builds launcher .apk
  - Push to `vega/` → builds Fire TV .vpkg
  - Push to `lg-webos/` → builds webOS .ipk

  ### Creating Releases

  Tag with a platform prefix to trigger a release:
  ```bash
  git tag linux-v1.1.0 && git push origin linux-v1.1.0
  git tag windows-v1.1.0 && git push origin windows-v1.1.0
  git tag macos-v1.1.0 && git push origin macos-v1.1.0
  git tag android-tv-v3.1.0 && git push origin android-tv-v3.1.0
  ```

  ## Related Repositories

  - **Web Dashboard (Cloud):** [Digipal-SignageSoftware-Replit-Files](https://github.com/aysikder-oss/Digipal-SignageSoftware-Replit-Files)
  - **Local Server (Hub):** [digipal-local-server](https://github.com/aysikder-oss/digipal-local-server)
  