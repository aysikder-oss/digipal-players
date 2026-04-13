import SwiftUI

struct SearchingView: View {
    var body: some View {
        ZStack {
            Color(red: 0.059, green: 0.090, blue: 0.165)
                .edgesIgnoringSafeArea(.all)

            VStack(spacing: 24) {
                ProgressView()
                    .scaleEffect(1.5)
                    .progressViewStyle(CircularProgressViewStyle(tint: Color(red: 0.231, green: 0.510, blue: 0.965)))

                Text("Searching for server…")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(.white)

                Text("Looking for a Digipal Hub on your network")
                    .font(.system(size: 14))
                    .foregroundColor(Color(red: 0.58, green: 0.64, blue: 0.72))
            }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        ZStack {
            if appState.isSearching {
                SearchingView()
            } else if appState.hasServerUrl && !appState.showSetup {
                PlayerWebView()
                    .environmentObject(appState)
                    .edgesIgnoringSafeArea(.all)
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            if let window = NSApp.mainWindow,
                               !window.styleMask.contains(.fullScreen) {
                                window.toggleFullScreen(nil)
                            }
                        }
                    }
            } else {
                SetupView()
                    .environmentObject(appState)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}
