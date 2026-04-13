import Foundation

struct ScannedHub: Identifiable {
    let id = UUID()
    let ip: String
    let port: Int
    let url: String
}

class NetworkScanner: ObservableObject {
    @Published var isScanning = false
    @Published var foundHubs: [ScannedHub] = []
    
    private var scanTask: Task<Void, Never>?
    private let scanPorts = [8787, 8788, 8789, 8790, 8791, 8792, 8793, 8794, 8795, 8796]
    private let probeTimeout: TimeInterval = 2.0
    
    func getLocalSubnets() -> [String] {
        var subnets: Set<String> = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return [] }
        
        defer { freeifaddrs(ifaddr) }
        
        var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let addr = ptr {
            let flags = Int32(addr.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) != 0
            let isLoopback = (flags & IFF_LOOPBACK) != 0
            
            if isUp && !isLoopback {
                if addr.pointee.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(addr.pointee.ifa_addr, socklen_t(addr.pointee.ifa_addr.pointee.sa_len),
                                   &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                        let ip = String(cString: hostname)
                        let parts = ip.split(separator: ".")
                        if parts.count == 4 {
                            subnets.insert("\(parts[0]).\(parts[1]).\(parts[2])")
                        }
                    }
                }
            }
            ptr = addr.pointee.ifa_next
        }
        return Array(subnets)
    }
    
    func scan() async -> [ScannedHub] {
        await MainActor.run {
            isScanning = true
            foundHubs = []
        }
        
        let subnets = getLocalSubnets()
        NSLog("[NetworkScanner] Scanning subnets: %@", subnets.joined(separator: ", "))
        
        if subnets.isEmpty {
            NSLog("[NetworkScanner] No local subnets found")
            await MainActor.run { isScanning = false }
            return []
        }
        
        var found: [ScannedHub] = []
        
        for subnet in subnets {
            let priorityIPs = [1, 100, 200, 2, 3, 10, 50, 150, 254]
            var allIPs: [Int] = priorityIPs
            for i in 1...254 {
                if !allIPs.contains(i) { allIPs.append(i) }
            }
            
            for batchStart in stride(from: 0, to: allIPs.count, by: 20) {
                let batchEnd = min(batchStart + 20, allIPs.count)
                let batch = Array(allIPs[batchStart..<batchEnd])
                
                let results = await withTaskGroup(of: ScannedHub?.self) { group in
                    for lastOctet in batch {
                        let ip = "\(subnet).\(lastOctet)"
                        for port in scanPorts {
                            group.addTask {
                                return await self.probeHost(ip: ip, port: port)
                            }
                        }
                    }
                    
                    var batchResults: [ScannedHub] = []
                    for await result in group {
                        if let hub = result {
                            batchResults.append(hub)
                        }
                    }
                    return batchResults
                }
                
                if !results.isEmpty {
                    found.append(contentsOf: results)
                    NSLog("[NetworkScanner] Found hub(s): %@", results.map { $0.url }.joined(separator: ", "))
                    await MainActor.run {
                        foundHubs = found
                        isScanning = false
                    }
                    return found
                }
            }
        }
        
        NSLog("[NetworkScanner] No hubs found on local network")
        await MainActor.run { isScanning = false }
        return found
    }
    
    private func probeHost(ip: String, port: Int) async -> ScannedHub? {
        let urlString = "http://\(ip):\(port)/api/health"
        guard let url = URL(string: urlString) else { return nil }
        
        var request = URLRequest(url: url)
        request.timeoutInterval = probeTimeout
        request.httpMethod = "GET"
        
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                return ScannedHub(ip: ip, port: port, url: "http://\(ip):\(port)")
            }
        } catch {
        }
        return nil
    }
    
    func stop() {
        scanTask?.cancel()
        scanTask = nil
        isScanning = false
    }
}
