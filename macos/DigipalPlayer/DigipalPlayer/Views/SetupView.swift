import SwiftUI

struct SetupView: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var bonjourBrowser = BonjourBrowser()
    @StateObject private var networkScanner = NetworkScanner()
    @State private var urlInput: String = ""
    @State private var errorMessage: String = ""
    @State private var isValidating = false
    @State private var scanStatus: String = "Scanning for local hubs..."
    @State private var hasScanned = false

    var body: some View {
        ZStack {
            Color(red: 0.059, green: 0.090, blue: 0.165)
                .edgesIgnoringSafeArea(.all)

            VStack(spacing: 0) {
                Spacer()

                VStack(spacing: 24) {
                    VStack(spacing: 8) {
                        Text("Digipal Player")
                            .font(.system(size: 24, weight: .bold))
                            .foregroundColor(.white)
                        Text("Enter your server URL to get started")
                            .font(.system(size: 14))
                            .foregroundColor(Color(red: 0.58, green: 0.64, blue: 0.72))
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Server URL")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(Color(red: 0.58, green: 0.64, blue: 0.72))

                        TextField("https://your-app.replit.app", text: $urlInput)
                            .textFieldStyle(.plain)
                            .font(.system(size: 14))
                            .foregroundColor(.white)
                            .padding(EdgeInsets(top: 10, leading: 14, bottom: 10, trailing: 14))
                            .background(Color(red: 0.059, green: 0.090, blue: 0.165))
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(Color(red: 0.2, green: 0.255, blue: 0.333), lineWidth: 1)
                            )
                            .onSubmit { connect() }

                        if !errorMessage.isEmpty {
                            Text(errorMessage)
                                .font(.system(size: 12))
                                .foregroundColor(Color(red: 0.973, green: 0.443, blue: 0.443))
                        }
                    }

                    Button(action: connect) {
                        HStack {
                            if isValidating {
                                ProgressView()
                                    .scaleEffect(0.8)
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            }
                            Text(isValidating ? "Connecting..." : "Connect")
                                .font(.system(size: 14, weight: .semibold))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Color(red: 0.231, green: 0.510, blue: 0.965))
                        .foregroundColor(.white)
                        .cornerRadius(8)
                    }
                    .buttonStyle(.plain)
                    .disabled(isValidating)

                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            if networkScanner.isScanning || (!hasScanned && bonjourBrowser.discoveredServers.isEmpty) {
                                ProgressView()
                                    .scaleEffect(0.6)
                                    .progressViewStyle(CircularProgressViewStyle(tint: Color(red: 0.58, green: 0.64, blue: 0.72)))
                            }
                            Text(scanStatus)
                                .font(.system(size: 12))
                                .foregroundColor(Color(red: 0.58, green: 0.64, blue: 0.72))
                            Spacer()
                            Button(action: scanAgain) {
                                HStack(spacing: 4) {
                                    Image(systemName: "arrow.clockwise")
                                        .font(.system(size: 11))
                                    Text("Scan Again")
                                        .font(.system(size: 12))
                                }
                                .foregroundColor(Color(red: 0.231, green: 0.510, blue: 0.965))
                            }
                            .buttonStyle(.plain)
                            .disabled(networkScanner.isScanning)
                        }

                        if !bonjourBrowser.discoveredServers.isEmpty {
                            ForEach(bonjourBrowser.discoveredServers, id: \.url) { server in
                                hubRow(name: server.name, url: server.url, source: "mDNS")
                            }
                        }

                        if !networkScanner.foundHubs.isEmpty {
                            ForEach(networkScanner.foundHubs) { hub in
                                hubRow(name: "Hub at \(hub.ip)", url: hub.url, source: "Network Scan")
                            }
                        }
                    }

                    if appState.manualOverride {
                        Button(action: {
                            appState.resetToAutoDiscover()
                        }) {
                            Text("Reset to Auto-Discover")
                                .font(.system(size: 13))
                                .foregroundColor(Color(red: 0.231, green: 0.510, blue: 0.965))
                        }
                        .buttonStyle(.plain)
                    }

                    Text("You can change this later with \u{2318}\u{21E7}S")
                        .font(.system(size: 11))
                        .foregroundColor(Color(red: 0.396, green: 0.455, blue: 0.525))
                }
                .padding(32)
                .frame(width: 440)
                .background(Color(red: 0.118, green: 0.161, blue: 0.231))
                .cornerRadius(12)
                .shadow(color: Color.black.opacity(0.5), radius: 30)

                Spacer()
            }
        }
        .onAppear {
            urlInput = appState.serverUrl
            startDiscovery()
        }
        .onDisappear {
            bonjourBrowser.stopBrowsing()
            networkScanner.stop()
        }
        .onChange(of: bonjourBrowser.discoveredServers) { servers in
            updateScanStatus()
        }
        .onChange(of: networkScanner.foundHubs) { hubs in
            updateScanStatus()
        }
        .onChange(of: networkScanner.isScanning) { scanning in
            updateScanStatus()
        }
    }

    @ViewBuilder
    private func hubRow(name: String, url: String, source: String) -> some View {
        Button(action: {
            urlInput = url
            connect()
        }) {
            HStack {
                Image(systemName: "network")
                    .foregroundColor(Color(red: 0.231, green: 0.510, blue: 0.965))
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.white)
                    HStack(spacing: 4) {
                        Text(url)
                            .font(.system(size: 11))
                            .foregroundColor(Color(red: 0.58, green: 0.64, blue: 0.72))
                        Text("(\(source))")
                            .font(.system(size: 10))
                            .foregroundColor(Color(red: 0.396, green: 0.455, blue: 0.525))
                    }
                }
                Spacer()
                Image(systemName: "arrow.right.circle.fill")
                    .foregroundColor(Color(red: 0.231, green: 0.510, blue: 0.965))
            }
            .padding(10)
            .background(Color(red: 0.059, green: 0.090, blue: 0.165))
            .cornerRadius(8)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color(red: 0.2, green: 0.255, blue: 0.333), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func startDiscovery() {
        hasScanned = false
        scanStatus = "Scanning for local hubs..."
        bonjourBrowser.startBrowsing()

        DispatchQueue.main.asyncAfter(deadline: .now() + 8.0) { [self] in
            if bonjourBrowser.discoveredServers.isEmpty {
                NSLog("[SetupView] Bonjour found nothing, starting network scan")
                Task {
                    let _ = await networkScanner.scan()
                    await MainActor.run { hasScanned = true; updateScanStatus() }
                }
            } else {
                hasScanned = true
                updateScanStatus()
            }
        }
    }

    private func scanAgain() {
        hasScanned = false
        scanStatus = "Scanning for local hubs..."
        bonjourBrowser.stopBrowsing()
        networkScanner.stop()
        DispatchQueue.main.async {
            bonjourBrowser.startBrowsing()
        }
        Task {
            let _ = await networkScanner.scan()
            await MainActor.run { hasScanned = true; updateScanStatus() }
        }
    }

    private func updateScanStatus() {
        let totalFound = bonjourBrowser.discoveredServers.count + networkScanner.foundHubs.count
        if networkScanner.isScanning {
            scanStatus = "Scanning local network..."
        } else if totalFound > 0 {
            scanStatus = "\(totalFound) hub\(totalFound == 1 ? "" : "s") found"
        } else if hasScanned {
            scanStatus = "No hubs found -- enter URL manually"
        } else {
            scanStatus = "Scanning for local hubs..."
        }
    }

    private func connect() {
        let url = urlInput.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        guard !url.isEmpty else {
            errorMessage = "Please enter a server URL"
            return
        }

        guard url.hasPrefix("http://") || url.hasPrefix("https://") else {
            errorMessage = "URL must start with http:// or https://"
            return
        }

        guard URL(string: url) != nil else {
            errorMessage = "Please enter a valid URL"
            return
        }

        errorMessage = ""
        isValidating = true

        Task {
            let isReachable = await validateServer(url: url)
            await MainActor.run {
                isValidating = false
                if isReachable {
                    appState.connectTo(url: url, mode: isLocalUrl(url) ? "LOCAL" : "CLOUD", manual: true)
                } else {
                    errorMessage = "Could not reach server. Connecting anyway..."
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        appState.connectTo(url: url, mode: isLocalUrl(url) ? "LOCAL" : "CLOUD", manual: true)
                    }
                }
            }
        }
    }

    private func isLocalUrl(_ url: String) -> Bool {
        guard let components = URLComponents(string: url),
              let host = components.host else { return false }
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host.hasPrefix("192.168.") ||
            host.hasPrefix("10.") ||
            host.hasPrefix("172.") ||
            host.hasSuffix(".local")
    }

    private func validateServer(url: String) async -> Bool {
        guard let serverUrl = URL(string: "\(url)/api/health") else { return false }
        var request = URLRequest(url: serverUrl)
        request.timeoutInterval = 5
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch {
            return false
        }
    }
}
