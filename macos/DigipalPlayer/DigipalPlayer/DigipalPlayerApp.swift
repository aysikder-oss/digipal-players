import SwiftUI

@main
struct DigipalPlayerApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var appState = AppState.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
                .frame(minWidth: 800, minHeight: 600)
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(after: .appInfo) {
                Button("Setup Server...") {
                    appState.showSetup = true
                }
                .keyboardShortcut("S", modifiers: [.command, .shift])

                Button("Reset to Auto-Discover") {
                    appState.resetToAutoDiscover()
                }

                Button("Toggle Fullscreen") {
                    toggleFullscreen()
                }
                .keyboardShortcut("F", modifiers: [.command, .shift])

                Button("Web Inspector") {
                    NotificationCenter.default.post(name: .toggleWebInspector, object: nil)
                }
                .keyboardShortcut("I", modifiers: [.command, .shift])
            }
        }
    }

    private func toggleFullscreen() {
        NSApp.mainWindow?.toggleFullScreen(nil)
    }
}

extension Notification.Name {
    static let toggleWebInspector = Notification.Name("toggleWebInspector")
}
