import Foundation

class MediaManager {
    static let shared = MediaManager()

    private let mediaDir: URL
    private let manifestPath: URL
    private var manifest: [String: MediaEntry] = [:]
    private var activeDownloads: Set<String> = []
    private let maxConcurrent = 3
    private let lowStorageThreshold: UInt64 = 100 * 1024 * 1024
    private let queue = DispatchQueue(label: "com.digipal.player.media", qos: .utility)

    struct MediaEntry: Codable {
        var localPath: String
        var size: Int64
        var downloadedAt: Double
        var lastAccessed: Double
    }

    private init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let playerDir = appSupport.appendingPathComponent("DigipalPlayer")
        self.mediaDir = playerDir.appendingPathComponent("media")
        self.manifestPath = playerDir.appendingPathComponent("media-manifest.json")

        try? FileManager.default.createDirectory(at: mediaDir, withIntermediateDirectories: true)
        loadManifest()
        cleanupOrphans()
    }

    private func loadManifest() {
        guard FileManager.default.fileExists(atPath: manifestPath.path) else { return }
        do {
            let data = try Data(contentsOf: manifestPath)
            manifest = try JSONDecoder().decode([String: MediaEntry].self, from: data)
        } catch {
            print("[MediaManager] Failed to load manifest: \(error.localizedDescription)")
            manifest = [:]
        }
    }

    private func saveManifest() {
        do {
            let data = try JSONEncoder().encode(manifest)
            try data.write(to: manifestPath)
        } catch {
            print("[MediaManager] Failed to save manifest: \(error.localizedDescription)")
        }
    }

    private func objectPathToFilename(_ objectPath: String) -> String {
        return objectPath
            .replacingOccurrences(of: "/objects/", with: "")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: ":", with: "_")
            .replacingOccurrences(of: "*", with: "_")
            .replacingOccurrences(of: "?", with: "_")
            .replacingOccurrences(of: "\"", with: "_")
            .replacingOccurrences(of: "<", with: "_")
            .replacingOccurrences(of: ">", with: "_")
            .replacingOccurrences(of: "|", with: "_")
    }

    func getLocalMediaPath(objectPath: String) -> String {
        guard var entry = manifest[objectPath] else { return "" }
        let filePath = entry.localPath
        guard FileManager.default.fileExists(atPath: filePath) else {
            manifest.removeValue(forKey: objectPath)
            saveManifest()
            return ""
        }
        entry.lastAccessed = Date().timeIntervalSince1970 * 1000
        manifest[objectPath] = entry
        saveManifest()
        return "local-media://" + filePath.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed)!
    }

    func getAllLocalPaths() -> [(String, String)] {
        return manifest.compactMap { (objectPath, entry) in
            guard FileManager.default.fileExists(atPath: entry.localPath) else { return nil }
            let protocolUrl = "local-media://" + (entry.localPath.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? entry.localPath)
            return (objectPath, protocolUrl)
        }
    }

    func downloadMedia(objectPath: String, signedUrl: String, completion: @escaping (String) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }

            if self.activeDownloads.contains(objectPath) { return }
            if let entry = self.manifest[objectPath],
               FileManager.default.fileExists(atPath: entry.localPath) { return }
            if self.activeDownloads.count >= self.maxConcurrent { return }

            if !self.hasEnoughStorage() {
                self.evictOldest(count: 1)
                if !self.hasEnoughStorage() {
                    print("[MediaManager] Low storage, skipping: \(objectPath)")
                    return
                }
            }

            self.activeDownloads.insert(objectPath)

            let filename = self.objectPathToFilename(objectPath)
            let filePath = self.mediaDir.appendingPathComponent(filename)
            let tempPath = self.mediaDir.appendingPathComponent(filename + ".tmp")

            guard let url = URL(string: signedUrl) else {
                self.activeDownloads.remove(objectPath)
                return
            }

            let task = URLSession.shared.downloadTask(with: url) { [weak self] tempUrl, response, error in
                guard let self = self else { return }

                defer { self.activeDownloads.remove(objectPath) }

                if let error = error {
                    print("[MediaManager] Download failed for \(objectPath): \(error.localizedDescription)")
                    return
                }

                guard let tempUrl = tempUrl,
                      let httpResponse = response as? HTTPURLResponse,
                      httpResponse.statusCode == 200 else {
                    return
                }

                do {
                    if FileManager.default.fileExists(atPath: filePath.path) {
                        try FileManager.default.removeItem(at: filePath)
                    }
                    try FileManager.default.moveItem(at: tempUrl, to: filePath)

                    let attrs = try FileManager.default.attributesOfItem(atPath: filePath.path)
                    let fileSize = (attrs[.size] as? Int64) ?? 0

                    self.manifest[objectPath] = MediaEntry(
                        localPath: filePath.path,
                        size: fileSize,
                        downloadedAt: Date().timeIntervalSince1970 * 1000,
                        lastAccessed: Date().timeIntervalSince1970 * 1000
                    )
                    self.saveManifest()

                    let protocolUrl = "local-media://" + (filePath.path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? filePath.path)
                    completion(protocolUrl)
                } catch {
                    print("[MediaManager] Failed to save downloaded file: \(error.localizedDescription)")
                    try? FileManager.default.removeItem(at: tempPath)
                }
            }
            task.resume()
        }
    }

    func deleteMedia(objectPath: String) {
        guard let entry = manifest[objectPath] else { return }
        try? FileManager.default.removeItem(atPath: entry.localPath)
        manifest.removeValue(forKey: objectPath)
        saveManifest()
    }

    func deleteAllMedia() {
        for (_, entry) in manifest {
            try? FileManager.default.removeItem(atPath: entry.localPath)
        }
        manifest = [:]
        saveManifest()
    }

    func getStorageInfo() -> String {
        var usedBytes: Int64 = 0
        var totalFiles = 0
        for entry in manifest.values {
            usedBytes += entry.size
            totalFiles += 1
        }

        var freeBytes: UInt64 = 0
        var totalSpace: UInt64 = 0
        do {
            let attrs = try FileManager.default.attributesOfFileSystem(forPath: mediaDir.path)
            freeBytes = (attrs[.systemFreeSize] as? UInt64) ?? 0
            totalSpace = (attrs[.systemSize] as? UInt64) ?? 0
        } catch {}

        return "{\"usedBytes\":\(usedBytes),\"freeBytes\":\(freeBytes),\"totalSpace\":\(totalSpace),\"totalFiles\":\(totalFiles)}"
    }

    private func hasEnoughStorage() -> Bool {
        do {
            let attrs = try FileManager.default.attributesOfFileSystem(forPath: mediaDir.path)
            let freeBytes = (attrs[.systemFreeSize] as? UInt64) ?? 0
            return freeBytes > lowStorageThreshold
        } catch {
            return true
        }
    }

    private func evictOldest(count: Int) {
        let sorted = manifest.sorted { ($0.value.lastAccessed) < ($1.value.lastAccessed) }
        let toEvict = sorted.prefix(count)
        for (key, entry) in toEvict {
            try? FileManager.default.removeItem(atPath: entry.localPath)
            manifest.removeValue(forKey: key)
        }
        saveManifest()
    }

    func cleanupOrphans() {
        do {
            let files = try FileManager.default.contentsOfDirectory(at: mediaDir, includingPropertiesForKeys: nil)
            let knownPaths = Set(manifest.values.map { $0.localPath })

            for file in files {
                if !knownPaths.contains(file.path) && !file.lastPathComponent.hasSuffix(".tmp") {
                    try? FileManager.default.removeItem(at: file)
                }
            }

            var changed = false
            for (key, entry) in manifest {
                if !FileManager.default.fileExists(atPath: entry.localPath) {
                    manifest.removeValue(forKey: key)
                    changed = true
                }
            }
            if changed { saveManifest() }
        } catch {
            print("[MediaManager] cleanupOrphans failed: \(error.localizedDescription)")
        }
    }

    func cleanup() {
        saveManifest()
    }
}
