import Cocoa
import SwiftUI
import ServiceManagement

class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private var statusItem: NSStatusItem?
    private var isKioskMode = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        setupStatusItem()
        setupKioskModeObserver()
        setupConnectionObservers()

        if AppState.shared.autoStartOnLogin {
            enableAutoStart()
        }

    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return !isKioskMode
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if isKioskMode {
            return .terminateCancel
        }
        MediaManager.shared.cleanup()
        return .terminateNow
    }

    private func setupStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)

        if let button = statusItem?.button {
            button.image = NSImage(systemSymbolName: "tv", accessibilityDescription: "Digipal Player")
        }

        let menu = NSMenu()
        menu.addItem(NSMenuItem(title: "Digipal Player", action: nil, keyEquivalent: ""))
        menu.addItem(NSMenuItem.separator())

        let statusMenuItem = NSMenuItem(title: "Status: Connecting...", action: nil, keyEquivalent: "")
        statusMenuItem.tag = 100
        menu.addItem(statusMenuItem)

        menu.addItem(NSMenuItem.separator())

        menu.addItem(NSMenuItem(title: "Show Player", action: #selector(showPlayer), keyEquivalent: ""))
        menu.addItem(NSMenuItem(title: "Setup Server...", action: #selector(showSetup), keyEquivalent: ""))
        menu.addItem(NSMenuItem(title: "Reset to Auto-Discover", action: #selector(resetToAutoDiscover), keyEquivalent: ""))

        let modeItem = NSMenuItem(title: "Mode: Cloud", action: nil, keyEquivalent: "")
        modeItem.tag = 102
        menu.addItem(modeItem)

        menu.addItem(NSMenuItem.separator())

        let kioskItem = NSMenuItem(title: "Kiosk Mode", action: #selector(toggleKiosk), keyEquivalent: "")
        kioskItem.tag = 101
        menu.addItem(kioskItem)

        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(quitApp), keyEquivalent: "q"))

        statusItem?.menu = menu
    }

    @objc private func showPlayer() {
        NSApp.activate(ignoringOtherApps: true)
        if let window = NSApp.mainWindow {
            window.makeKeyAndOrderFront(nil)
        } else {
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    @objc private func showSetup() {
        AppState.shared.showSetup = true
        showPlayer()
    }

    @objc private func resetToAutoDiscover() {
        AppState.shared.resetToAutoDiscover()
        showPlayer()
    }

    @objc private func toggleKiosk() {
        isKioskMode.toggle()
        AppState.shared.kioskMode = isKioskMode

        if let menuItem = statusItem?.menu?.item(withTag: 101) {
            menuItem.state = isKioskMode ? .on : .off
        }

        if isKioskMode {
            enterKioskMode()
        } else {
            exitKioskMode()
        }
    }

    @objc private func quitApp() {
        isKioskMode = false
        NSApp.terminate(nil)
    }

    private func enterKioskMode() {
        guard let window = NSApp.mainWindow else { return }
        window.level = .floating
        window.collectionBehavior = [.fullScreenPrimary, .canJoinAllSpaces]
        window.styleMask.remove(.closable)
        window.delegate = self
        if !window.styleMask.contains(.fullScreen) {
            window.toggleFullScreen(nil)
        }

        NSApp.presentationOptions = [
            .hideDock,
            .hideMenuBar,
            .disableProcessSwitching,
            .disableForceQuit
        ]
    }

    private func exitKioskMode() {
        guard let window = NSApp.mainWindow else { return }
        window.level = .normal
        window.styleMask.insert(.closable)
        NSApp.presentationOptions = []
    }

    private func setupKioskModeObserver() {
        NotificationCenter.default.addObserver(
            forName: .kioskModeChanged,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let enabled = notification.object as? Bool {
                self?.isKioskMode = enabled
                if enabled {
                    self?.enterKioskMode()
                } else {
                    self?.exitKioskMode()
                }
            }
        }
    }

    private func setupConnectionObservers() {
        NotificationCenter.default.addObserver(
            forName: .playerDidConnect,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updateConnectionStatus(connected: true)
        }

        NotificationCenter.default.addObserver(
            forName: .playerDidDisconnect,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updateConnectionStatus(connected: false)
        }
    }

    private func updateConnectionStatus(connected: Bool) {
        guard let menu = statusItem?.menu,
              let item = menu.item(withTag: 100) else { return }
        if connected {
            let serverUrl = AppState.shared.serverUrl
            let shortUrl = serverUrl.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
            item.title = "Connected: \(shortUrl)"
        } else {
            item.title = "Status: Reconnecting..."
        }

        if let modeItem = menu.item(withTag: 102) {
            let mode = AppState.shared.connectionMode
            let cloudStatus = CloudChannel.shared.isConnected ? " + Cloud Channel" : ""
            modeItem.title = "Mode: \(mode)\(cloudStatus)"
        }
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        return !isKioskMode
    }

    func enableAutoStart() {
        if #available(macOS 13.0, *) {
            do {
                try SMAppService.mainApp.register()
            } catch {
                print("[AutoStart] SMAppService registration failed: \(error)")
            }
        }
    }

    func disableAutoStart() {
        if #available(macOS 13.0, *) {
            do {
                try SMAppService.mainApp.unregister()
            } catch {
                print("[AutoStart] SMAppService unregistration failed: \(error)")
            }
        }
    }
}

extension Notification.Name {
    static let kioskModeChanged = Notification.Name("kioskModeChanged")
    static let playerDidConnect = Notification.Name("playerDidConnect")
    static let playerDidDisconnect = Notification.Name("playerDidDisconnect")
}
