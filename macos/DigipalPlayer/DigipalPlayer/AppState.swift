import Foundation
import Combine
import AppKit

class AppState: ObservableObject {
    static let shared = AppState()

    @Published var serverUrl: String {
        didSet {
            UserDefaults.standard.set(serverUrl, forKey: "serverUrl")
        }
    }

    @Published var showSetup: Bool = false
    @Published var isSearching: Bool = false
    @Published var kioskMode: Bool = false
    @Published var isConnected: Bool = false
    @Published var connectionMode: String = "CLOUD"

    @Published var manualOverride: Bool {
        didSet {
            UserDefaults.standard.set(manualOverride, forKey: "manualOverride")
        }
    }

    @Published var autoStartOnLogin: Bool {
        didSet {
            UserDefaults.standard.set(autoStartOnLogin, forKey: "autoStartOnLogin")
        }
    }

    var deviceId: String {
        if let existing = UserDefaults.standard.string(forKey: "deviceId"), !existing.isEmpty {
            return existing
        }
        let newId = UUID().uuidString
        UserDefaults.standard.set(newId, forKey: "deviceId")
        return newId
    }

    var hasServerUrl: Bool {
        !serverUrl.isEmpty
    }

    var playerUrl: String {
        guard hasServerUrl else { return "" }
        return "\(serverUrl)/tv?platform=macos"
    }

    private let defaultCloudUrl = "https://app.digipal.app"
    private let discoveryTimeoutSeconds: Double = 8.0
    private let healthCheckIntervalSeconds: Double = 30.0
    private let reconnectBaseSeconds: Double = 5.0
    private let reconnectMaxSeconds: Double = 60.0
    private let maxRetryBeforeSetup: Int = 3

    private var bonjourBrowser: BonjourBrowser?
    private var networkScanner: NetworkScanner?
    private var discoveryTimer: Timer?
    private var healthCheckTimer: Timer?
    private var localHealthFailures: Int = 0
    private var localRediscoveryTimer: Timer?
    private var localRediscoveryAttempts: Int = 0
    private var neitherReachableTimer: Timer?
    private var neitherReachableAttempts: Int = 0
    private var cancellables = Set<AnyCancellable>()

    private init() {
        self.serverUrl = UserDefaults.standard.string(forKey: "serverUrl") ?? ""
        self.manualOverride = UserDefaults.standard.bool(forKey: "manualOverride")
        self.autoStartOnLogin = UserDefaults.standard.bool(forKey: "autoStartOnLogin")

        if self.manualOverride && !self.serverUrl.isEmpty {
            self.showSetup = false
            self.isSearching = false
            self.connectionMode = isLocalUrl(serverUrl) ? "LOCAL" : "CLOUD"
            startDualMode()
        } else {
            self.showSetup = false
            self.isSearching = true
            startAutoDiscovery()
        }
    }

    func startAutoDiscovery() {
        isSearching = true
        showSetup = false
        neitherReachableAttempts = 0

        bonjourBrowser = BonjourBrowser()
        bonjourBrowser?.startBrowsing()

        bonjourBrowser?.$discoveredServers
            .filter { !$0.isEmpty }
            .first()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] servers in
                guard let self = self, self.isSearching else { return }
                if let server = servers.first {
                    self.discoveryTimer?.invalidate()
                    self.connectTo(url: server.url, mode: "LOCAL", manual: false)
                }
            }
            .store(in: &cancellables)

        discoveryTimer = Timer.scheduledTimer(withTimeInterval: discoveryTimeoutSeconds, repeats: false) { [weak self] _ in
            guard let self = self, self.isSearching else { return }

            if let server = self.bonjourBrowser?.discoveredServers.first {
                self.connectTo(url: server.url, mode: "LOCAL", manual: false)
            } else {
                self.checkCloudAndConnect()
            }
        }
    }

    private func checkCloudAndConnect() {
        Task {
            let scanner = NetworkScanner()
            self.networkScanner = scanner
            print("[AppState] Bonjour discovery failed, trying network scan...")
            let found = await scanner.scan()
            if let hub = found.first {
                await MainActor.run {
                    self.neitherReachableAttempts = 0
                    self.connectTo(url: hub.url, mode: "LOCAL", manual: false)
                }
                return
            }

            guard let url = URL(string: "\(defaultCloudUrl)/api/health") else {
                await MainActor.run { self.handleConnectionFailure() }
                return
            }

            var request = URLRequest(url: url)
            request.timeoutInterval = 5

            URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                        self.neitherReachableAttempts = 0
                        self.connectTo(url: self.defaultCloudUrl, mode: "CLOUD", manual: false)
                    } else {
                        self.handleConnectionFailure()
                    }
                }
            }.resume()
        }
    }

    private func handleConnectionFailure() {
        neitherReachableAttempts += 1

        if neitherReachableAttempts >= maxRetryBeforeSetup {
            print("[AppState] Max retries reached (\(maxRetryBeforeSetup)), showing setup screen")
            showSetupScreen()
            return
        }

        let delay = min(
            reconnectBaseSeconds * pow(2.0, Double(neitherReachableAttempts - 1)),
            reconnectMaxSeconds
        )

        print("[AppState] Neither server reachable, retrying in \(Int(delay))s (attempt \(neitherReachableAttempts)/\(maxRetryBeforeSetup))")

        neitherReachableTimer?.invalidate()
        neitherReachableTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            guard let self = self else { return }

            if let server = self.bonjourBrowser?.discoveredServers.first {
                self.neitherReachableAttempts = 0
                self.connectTo(url: server.url, mode: "LOCAL", manual: false)
                return
            }

            self.checkCloudAndConnect()
        }
    }

    private func showSetupScreen() {
        isSearching = false
        showSetup = true
        startMdnsBrowsingBackground()
    }

    func connectTo(url: String, mode: String, manual: Bool) {
        isSearching = false
        serverUrl = url
        connectionMode = mode
        manualOverride = manual
        showSetup = false
        isConnected = true

        bonjourBrowser?.stopBrowsing()

        startDualMode()
    }

    func resetToAutoDiscover() {
        stopDualMode()
        serverUrl = ""
        manualOverride = false
        isConnected = false
        connectionMode = "CLOUD"
        startAutoDiscovery()
    }

    func switchServer(to url: String) {
        let wasLocal = connectionMode == "LOCAL"
        serverUrl = url
        connectionMode = isLocalUrl(url) ? "LOCAL" : "CLOUD"

        CloudChannel.shared.sendMessage([
            "type": "playerStateUpdate",
            "payload": [
                "deviceId": deviceId,
                "platform": "macos",
                "primaryMode": connectionMode,
                "primaryUrl": serverUrl
            ]
        ])

        if connectionMode == "LOCAL" && !wasLocal {
            stopLocalRediscovery()
            startLocalHealthCheck()
        } else if connectionMode == "CLOUD" && wasLocal {
            stopLocalHealthCheck()
            startLocalRediscovery()
        }
    }

    private func startDualMode() {
        startCloudChannel()
        setupCloudChannelCallbacks()

        if connectionMode == "LOCAL" {
            startLocalHealthCheck()
            startMdnsBrowsingBackground()
        } else {
            startLocalRediscovery()
        }
    }

    private func stopDualMode() {
        healthCheckTimer?.invalidate()
        healthCheckTimer = nil
        localRediscoveryTimer?.invalidate()
        localRediscoveryTimer = nil
        discoveryTimer?.invalidate()
        discoveryTimer = nil
        neitherReachableTimer?.invalidate()
        neitherReachableTimer = nil
        CloudChannel.shared.stop()
        bonjourBrowser?.stopBrowsing()
        cancellables.removeAll()
    }

    private func startCloudChannel() {
        CloudChannel.shared.start(
            cloudUrl: defaultCloudUrl,
            deviceId: deviceId,
            primaryMode: connectionMode,
            primaryUrl: serverUrl
        )
    }

    private func setupCloudChannelCallbacks() {
        CloudChannel.shared.onSwitchServer = { [weak self] url in
            self?.switchServer(to: url)
        }

        CloudChannel.shared.onForceReload = { [weak self] in
            guard let self = self else { return }
            let currentUrl = self.serverUrl
            self.serverUrl = ""
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                self.serverUrl = currentUrl
            }
        }

        CloudChannel.shared.onRemoteRestart = {
            let task = Process()
            task.executableURL = URL(fileURLWithPath: "/usr/bin/open")
            task.arguments = ["-n", Bundle.main.bundlePath]
            try? task.run()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                NSApplication.shared.terminate(nil)
            }
        }
    }

    private func startLocalHealthCheck() {
        healthCheckTimer?.invalidate()
        localHealthFailures = 0
        healthCheckTimer = Timer.scheduledTimer(withTimeInterval: healthCheckIntervalSeconds, repeats: true) { [weak self] _ in
            self?.checkLocalServerHealth()
        }
    }

    private func stopLocalHealthCheck() {
        healthCheckTimer?.invalidate()
        healthCheckTimer = nil
        localHealthFailures = 0
    }

    private func checkLocalServerHealth() {
        guard connectionMode == "LOCAL",
              let url = URL(string: "\(serverUrl)/api/health") else { return }

        var request = URLRequest(url: url)
        request.timeoutInterval = 5

        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }

                if error != nil {
                    self.localHealthFailures += 1
                    print("[AppState] Local server health check failed (attempt \(self.localHealthFailures))")
                    if self.localHealthFailures >= 2 {
                        self.fallbackToCloud()
                    }
                    return
                }

                if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
                    self.localHealthFailures += 1
                    print("[AppState] Local server health check returned \(httpResponse.statusCode) (attempt \(self.localHealthFailures))")
                    if self.localHealthFailures >= 2 {
                        self.fallbackToCloud()
                    }
                } else {
                    self.localHealthFailures = 0
                }
            }
        }.resume()
    }

    private func fallbackToCloud() {
        print("[AppState] Falling back to cloud")
        stopLocalHealthCheck()
        serverUrl = defaultCloudUrl
        connectionMode = "CLOUD"

        startLocalRediscovery()
    }

    private func startLocalRediscovery() {
        startMdnsBrowsingBackground()
        localRediscoveryAttempts = 0
        scheduleLocalRediscovery()
    }

    private func scheduleLocalRediscovery() {
        localRediscoveryAttempts += 1
        let delay = min(
            reconnectBaseSeconds * pow(2.0, Double(min(localRediscoveryAttempts - 1, 4))),
            reconnectMaxSeconds
        )

        localRediscoveryTimer?.invalidate()
        localRediscoveryTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            guard let self = self, self.connectionMode == "CLOUD" else { return }

            if let server = self.bonjourBrowser?.discoveredServers.first {
                print("[AppState] Local server rediscovered: \(server.url)")
                self.localRediscoveryAttempts = 0
                self.switchServer(to: server.url)
            } else {
                self.scheduleLocalRediscovery()
            }
        }
    }

    private func stopLocalRediscovery() {
        localRediscoveryTimer?.invalidate()
        localRediscoveryTimer = nil
        localRediscoveryAttempts = 0
    }

    private func startMdnsBrowsingBackground() {
        if bonjourBrowser == nil {
            bonjourBrowser = BonjourBrowser()
        }
        bonjourBrowser?.startBrowsing()
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
}
