import SwiftUI

struct SearchingView: View {
    @State private var dot1Opacity: Double = 0.3
    @State private var dot2Opacity: Double = 0.3
    @State private var dot3Opacity: Double = 0.3
    @State private var dot1Scale: CGFloat = 0.85
    @State private var dot2Scale: CGFloat = 0.85
    @State private var dot3Scale: CGFloat = 0.85

    private let teal = Color(red: 0.082, green: 0.718, blue: 0.647)
    private let bg   = Color(red: 0.059, green: 0.090, blue: 0.165)

    var body: some View {
        ZStack {
            bg.edgesIgnoringSafeArea(.all)

            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    Text("Digipal")
                        .font(.system(size: 36, weight: .heavy))
                        .foregroundColor(Color(red: 0.945, green: 0.961, blue: 0.980))
                    Text(" Player")
                        .font(.system(size: 36, weight: .heavy))
                        .foregroundColor(teal)
                }
                .tracking(-1)

                Text("Starting up\u{2026}")
                    .font(.system(size: 16))
                    .foregroundColor(Color(red: 0.282, green: 0.337, blue: 0.412))
                    .padding(.top, 20)
                    .padding(.bottom, 24)

                HStack(spacing: 10) {
                    Circle()
                        .fill(teal)
                        .frame(width: 10, height: 10)
                        .opacity(dot1Opacity)
                        .scaleEffect(dot1Scale)
                    Circle()
                        .fill(teal)
                        .frame(width: 10, height: 10)
                        .opacity(dot2Opacity)
                        .scaleEffect(dot2Scale)
                    Circle()
                        .fill(teal)
                        .frame(width: 10, height: 10)
                        .opacity(dot3Opacity)
                        .scaleEffect(dot3Scale)
                }
            }
        }
        .onAppear { animateDots() }
    }

    private func animateDots() {
        let duration = 0.6
        let delay    = 0.2

        func pulse(opacityBinding: Binding<Double>, scaleBinding: Binding<CGFloat>, after: Double) {
            DispatchQueue.main.asyncAfter(deadline: .now() + after) {
                withAnimation(.easeInOut(duration: duration)) {
                    opacityBinding.wrappedValue = 1.0
                    scaleBinding.wrappedValue   = 1.0
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + duration) {
                    withAnimation(.easeInOut(duration: duration)) {
                        opacityBinding.wrappedValue = 0.3
                        scaleBinding.wrappedValue   = 0.85
                    }
                }
            }
        }

        func loop() {
            let cycle = (duration * 2) + delay * 3 + 0.1
            pulse(opacityBinding: $dot1Opacity, scaleBinding: $dot1Scale, after: 0)
            pulse(opacityBinding: $dot2Opacity, scaleBinding: $dot2Scale, after: delay)
            pulse(opacityBinding: $dot3Opacity, scaleBinding: $dot3Scale, after: delay * 2)
            DispatchQueue.main.asyncAfter(deadline: .now() + cycle) { loop() }
        }

        loop()
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
                        var token: NSObjectProtocol?
                        token = NotificationCenter.default.addObserver(
                            forName: NSWindow.didBecomeMainNotification,
                            object: nil,
                            queue: .main
                        ) { _ in
                            if let window = NSApp.mainWindow,
                               !window.styleMask.contains(.fullScreen) {
                                window.toggleFullScreen(nil)
                            }
                            if let t = token {
                                NotificationCenter.default.removeObserver(t)
                            }
                            token = nil
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
