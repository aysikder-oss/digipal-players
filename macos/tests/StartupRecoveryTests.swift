import Foundation
import Combine

// Test external network/discovery boundaries; compile the real AppState unchanged.
final class BonjourBrowser {
    static weak var latest: BonjourBrowser?
    @Published var discoveredServers: [DiscoveredServer] = []
    init() { Self.latest = self }
    func startBrowsing() {}
    func stopBrowsing() { discoveredServers = [] }
}
struct DiscoveredServer {
    let url: String
}
final class CloudChannel {
    static let shared = CloudChannel()
    var onSwitchServer: ((String) -> Void)?
    var onForceReload: (() -> Void)?
    var onRemoteRestart: (() -> Void)?
    func start(cloudUrl: String, deviceId: String, primaryMode: String, primaryUrl: String) {}
    func stop() {}
    func sendMessage(_ message: [String: Any]) {}
}

final class HealthProtocol: URLProtocol {
    static var status = 200
    static var delay: TimeInterval = 0
    static var fail = false
    static var requests = 0
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests += 1
        let status = Self.status
        let fail = Self.fail
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.delay) { [self] in
            if fail {
                client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            } else {
                let response = HTTPURLResponse(url: request.url!, statusCode: status,
                                               httpVersion: nil, headerFields: nil)!
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocolDidFinishLoading(self)
            }
        }
    }
    // Deliberately allow a late response: AppState must reject retired attempts.
    override func stopLoading() {}
}

@main
struct StartupRecoveryTests {
    static func wait(_ seconds: TimeInterval = 0.15) {
        RunLoop.main.run(until: Date().addingTimeInterval(seconds))
    }
    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        precondition(condition(), message)
        print("PASS: \(message)")
    }
    static func main() {
        let defaults = UserDefaults.standard
        let keys = ["serverUrl", "manualOverride", "autoStartOnLogin", "deviceId"]
        let original = keys.map { defaults.object(forKey: $0) }
        defer {
            for (key, value) in zip(keys, original) {
                if let value = value { defaults.set(value, forKey: key) }
                else { defaults.removeObject(forKey: key) }
            }
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [HealthProtocol.self]
        let session = URLSession(configuration: config)
        func fresh() -> AppState {
            keys.forEach { defaults.removeObject(forKey: $0) }
            HealthProtocol.status = 200
            HealthProtocol.delay = 0
            HealthProtocol.fail = false
            HealthProtocol.requests = 0
            let state = AppState(startAutomatically: false, cloudSession: session)
            state.discoveryTimeoutSeconds = 0.02
            state.cloudTimeoutSeconds = 0.02
            return state
        }

        var state = fresh()
        state.startAutoDiscovery()
        wait()
        expect(state.serverUrl == "https://digipalsignage.com" && !state.isSearching,
               "No Hub plus healthy cloud reaches cloud playback")
        expect(state.connectionError == nil && !state.showSetup, "Successful startup has no error")
        state.openServerSettings()

        state = fresh()
        state.startAutoDiscovery()
        BonjourBrowser.latest?.discoveredServers = [DiscoveredServer(url: "http://192.168.1.20:5000")]
        wait()
        expect(state.connectionMode == "LOCAL" && !state.isSearching, "Local discovery remains supported")
        expect(HealthProtocol.requests == 0, "Working Hub does not require cloud health")
        state.openServerSettings()

        state = fresh()
        HealthProtocol.status = 503
        state.startAutoDiscovery()
        wait()
        expect(!state.isSearching && state.connectionError != nil, "Cloud failure reveals recovery, not a spinner")
        state.openServerSettings()
        expect(state.showSetup && state.connectionError == nil, "Server settings leave startup recovery cleanly")

        state = fresh()
        HealthProtocol.fail = true
        state.startAutoDiscovery()
        wait()
        expect(state.connectionError != nil, "Offline connection produces a recoverable state")
        HealthProtocol.fail = false
        state.startAutoDiscovery()
        wait()
        expect(state.connectionError == nil && state.connectionMode == "CLOUD", "Retry recovers when internet returns")
        state.openServerSettings()

        state = fresh()
        HealthProtocol.delay = 0.25
        state.startAutoDiscovery()
        wait(0.06)
        state.connectTo(url: "http://192.168.1.30:5000", mode: "LOCAL", manual: true)
        wait(0.35)
        expect(state.serverUrl == "http://192.168.1.30:5000" && state.manualOverride,
               "Late cloud response cannot override a manual server choice")
        state.openServerSettings()

        state = fresh()
        HealthProtocol.delay = 0.25
        state.startAutoDiscovery()
        wait(0.06)
        state.openServerSettings()
        wait(0.35)
        expect(state.showSetup && !state.isSearching, "Late cloud response cannot close server settings")

        state = fresh()
        HealthProtocol.delay = 4
        state.startAutoDiscovery()
        wait(2.2)
        expect(!state.isSearching && state.connectionError != nil, "Independent deadline bounds a hanging network request")
        state.connectToCloud()
        expect(state.serverUrl == "https://digipalsignage.com" && state.connectionError == nil,
               "Explicit cloud action bypasses health-check gating")
        state.openServerSettings()

        _ = fresh()
        defaults.set("https://digipalsignage.com", forKey: "serverUrl")
        defaults.set(false, forKey: "manualOverride")
        state = AppState(cloudSession: session)
        expect(!state.isSearching && state.serverUrl == "https://digipalsignage.com",
               "Previously saved automatic cloud server opens without discovery")
        state.openServerSettings()

        _ = fresh()
        defaults.set("http://192.168.1.40:5000", forKey: "serverUrl")
        defaults.set(true, forKey: "manualOverride")
        defaults.set("existing-device", forKey: "deviceId")
        state = AppState(cloudSession: session)
        expect(!state.isSearching && state.connectionMode == "LOCAL" && state.manualOverride,
               "Saved manual Hub remains configured")
        expect(state.deviceId == "existing-device", "Device identity is preserved")
        state.openServerSettings()
        session.invalidateAndCancel()
        print("All Mac startup recovery checks passed.")
    }
}
