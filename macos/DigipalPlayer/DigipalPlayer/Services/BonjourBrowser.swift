import Foundation
import Network
import Combine

struct DiscoveredServer: Identifiable, Equatable {
    let id = UUID()
    let name: String
    let url: String
    let port: Int
}

class BonjourBrowser: ObservableObject {
    @Published var discoveredServers: [DiscoveredServer] = []

    private var browser: NWBrowser?
    private var pendingConnections: [String: NWConnection] = [:]

    func startBrowsing() {
        stopBrowsing()

        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true

        let descriptor = NWBrowser.Descriptor.bonjourWithTXTRecord(
            type: "_digipal._tcp",
            domain: "local."
        )

        let b = NWBrowser(for: descriptor, using: parameters)
        self.browser = b

        b.browseResultsChangedHandler = { [weak self] _, changes in
            guard let self = self else { return }
            for change in changes {
                switch change {
                case .added(let result):
                    self.resolveService(result.endpoint)
                case .removed(let result):
                    self.handleRemoval(result.endpoint)
                default:
                    break
                }
            }
        }

        b.start(queue: .main)
    }

    func stopBrowsing() {
        browser?.cancel()
        browser = nil
        pendingConnections.values.forEach { $0.cancel() }
        pendingConnections.removeAll()
        DispatchQueue.main.async { self.discoveredServers.removeAll() }
    }

    private func endpointKey(_ endpoint: NWEndpoint) -> String {
        "\(endpoint)"
    }

    private func resolveService(_ endpoint: NWEndpoint) {
        let key = endpointKey(endpoint)
        guard pendingConnections[key] == nil else { return }

        let conn = NWConnection(to: endpoint, using: .tcp)
        pendingConnections[key] = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .ready:
                if let remoteEP = conn.currentPath?.remoteEndpoint,
                   case .hostPort(let host, let port) = remoteEP {
                    let hostStr = self.cleanHost("\(host)")
                    let portInt = Int(port.rawValue)
                    let name = self.serviceName(from: endpoint)
                    let url = self.formatUrl(host: hostStr, port: portInt)
                    DispatchQueue.main.async {
                        if !self.discoveredServers.contains(where: { $0.url == url }) {
                            self.discoveredServers.append(
                                DiscoveredServer(name: name, url: url, port: portInt)
                            )
                        }
                    }
                }
                conn.cancel()
                DispatchQueue.main.async { self.pendingConnections.removeValue(forKey: key) }
            case .failed, .cancelled:
                DispatchQueue.main.async { self.pendingConnections.removeValue(forKey: key) }
            default:
                break
            }
        }

        conn.start(queue: .global(qos: .utility))
    }

    private func handleRemoval(_ endpoint: NWEndpoint) {
        let key = endpointKey(endpoint)
        pendingConnections[key]?.cancel()
        pendingConnections.removeValue(forKey: key)
        let name = serviceName(from: endpoint)
        DispatchQueue.main.async {
            self.discoveredServers.removeAll { $0.name == name }
        }
    }

    private func serviceName(from endpoint: NWEndpoint) -> String {
        if case .service(let name, _, _, _) = endpoint { return name }
        return "Digipal Hub"
    }

    private func cleanHost(_ raw: String) -> String {
        if let idx = raw.firstIndex(of: "%") {
            return String(raw[..<idx])
        }
        return raw
    }

    private func formatUrl(host: String, port: Int) -> String {
        if host.contains(":") {
            return "http://[\(host)]:\(port)"
        }
        return "http://\(host):\(port)"
    }
}
