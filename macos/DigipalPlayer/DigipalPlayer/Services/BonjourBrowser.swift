import Foundation
import Combine

struct DiscoveredServer: Identifiable {
    let id = UUID()
    let name: String
    let url: String
    let port: Int
}

class BonjourBrowser: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {
    @Published var discoveredServers: [DiscoveredServer] = []

    private var browser: NetServiceBrowser?
    private var resolvingServices: [NetService] = []

    func startBrowsing() {
        NSLog("[Bonjour] Starting browser for _digipal._tcp in domain local.")
        browser = NetServiceBrowser()
        browser?.delegate = self
        browser?.searchForServices(ofType: "_digipal._tcp.", inDomain: "local.")
    }

    func stopBrowsing() {
        NSLog("[Bonjour] Stopping browser")
        browser?.stop()
        browser = nil
        resolvingServices.removeAll()
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        NSLog("[Bonjour] Found service: %@ (moreComing: %@)", service.name, moreComing ? "yes" : "no")
        service.delegate = self
        resolvingServices.append(service)
        service.resolve(withTimeout: 5.0)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        NSLog("[Bonjour] Lost service: %@", service.name)
        DispatchQueue.main.async {
            self.discoveredServers.removeAll { $0.name == service.name }
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        NSLog("[Bonjour] Browser failed to search: %@", errorDict.description)
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
        NSLog("[Bonjour] Browser stopped searching")
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let addresses = sender.addresses else {
            NSLog("[Bonjour] Service %@ resolved but has no addresses", sender.name)
            return
        }

        NSLog("[Bonjour] Resolving service: %@ (%d addresses)", sender.name, addresses.count)

        for addressData in addresses {
            let address = addressData.withUnsafeBytes { ptr -> String? in
                let sockAddr = ptr.load(as: sockaddr.self)
                if sockAddr.sa_family == UInt8(AF_INET) {
                    let addr4 = ptr.load(as: sockaddr_in.self)
                    var hostname = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                    var addr = addr4.sin_addr
                    inet_ntop(AF_INET, &addr, &hostname, socklen_t(INET_ADDRSTRLEN))
                    return String(cString: hostname)
                }
                return nil
            }

            if let ip = address {
                let port = sender.port
                let url = "http://\(ip):\(port)"
                let server = DiscoveredServer(
                    name: sender.name,
                    url: url,
                    port: port
                )

                NSLog("[Bonjour] Resolved service: %@ at %@", sender.name, url)

                DispatchQueue.main.async {
                    if !self.discoveredServers.contains(where: { $0.url == url }) {
                        self.discoveredServers.append(server)
                    }
                }
                break
            }
        }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        NSLog("[Bonjour] Failed to resolve service: %@ error: %@", sender.name, errorDict.description)
    }
}
