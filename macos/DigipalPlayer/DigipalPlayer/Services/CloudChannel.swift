import Foundation
import AppKit
import Combine

class CloudChannel: NSObject, ObservableObject, URLSessionWebSocketDelegate {
    static let shared = CloudChannel()

    @Published var isConnected: Bool = false

    private var webSocketTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var cloudUrl: String = ""
    private var deviceId: String = ""
    private var platform: String = "macos"
    private var reconnectAttempts: Int = 0
    private var reconnectTimer: Timer?
    private var isRunning: Bool = false

    private let reconnectBaseSeconds: Double = 5.0
    private let reconnectMaxSeconds: Double = 60.0

    var onSwitchServer: ((String) -> Void)?
    var onForceReload: (() -> Void)?
    var onRemoteRestart: (() -> Void)?

    override init() {
        super.init()
        let config = URLSessionConfiguration.default
        self.urlSession = URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }

    func start(cloudUrl: String, deviceId: String, primaryMode: String, primaryUrl: String) {
        self.cloudUrl = cloudUrl
        self.deviceId = deviceId
        self.isRunning = true
        connect(primaryMode: primaryMode, primaryUrl: primaryUrl)
    }

    func stop() {
        isRunning = false
        reconnectTimer?.invalidate()
        reconnectTimer = nil
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        DispatchQueue.main.async {
            self.isConnected = false
        }
    }

    private func connect(primaryMode: String = "local", primaryUrl: String = "") {
        guard isRunning else { return }

        let wsUrlString = cloudUrl
            .replacingOccurrences(of: "https://", with: "wss://")
            .replacingOccurrences(of: "http://", with: "ws://")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            + "/ws/player"

        guard let wsUrl = URL(string: wsUrlString) else {
            print("[CloudChannel] Invalid WebSocket URL: \(wsUrlString)")
            scheduleReconnect(primaryMode: primaryMode, primaryUrl: primaryUrl)
            return
        }

        webSocketTask = urlSession?.webSocketTask(with: wsUrl)
        webSocketTask?.resume()

        let identifyPayload: [String: Any] = [
            "type": "playerIdentify",
            "payload": [
                "deviceId": deviceId,
                "platform": platform,
                "primaryMode": primaryMode,
                "primaryUrl": primaryUrl
            ]
        ]

        if let data = try? JSONSerialization.data(withJSONObject: identifyPayload),
           let str = String(data: data, encoding: .utf8) {
            webSocketTask?.send(.string(str)) { [weak self] error in
                if let error = error {
                    print("[CloudChannel] Failed to send identify: \(error)")
                }
            }
        }

        reconnectAttempts = 0
        receiveMessage(primaryMode: primaryMode, primaryUrl: primaryUrl)
    }

    private func receiveMessage(primaryMode: String, primaryUrl: String) {
        webSocketTask?.receive { [weak self] result in
            guard let self = self, self.isRunning else { return }

            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleMessage(text)
                    }
                @unknown default:
                    break
                }
                self.receiveMessage(primaryMode: primaryMode, primaryUrl: primaryUrl)

            case .failure(let error):
                print("[CloudChannel] Receive error: \(error)")
                DispatchQueue.main.async {
                    self.isConnected = false
                }
                self.scheduleReconnect(primaryMode: primaryMode, primaryUrl: primaryUrl)
            }
        }
    }

    private func handleMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }

        switch type {
        case "switchServer":
            if let payload = json["payload"] as? [String: Any],
               let url = payload["url"] as? String {
                print("[CloudChannel] Command: switch server to \(url)")
                DispatchQueue.main.async {
                    self.onSwitchServer?(url)
                }
            }

        case "forceReload":
            print("[CloudChannel] Command: force reload")
            DispatchQueue.main.async {
                self.onForceReload?()
            }

        case "remoteRestart":
            print("[CloudChannel] Command: remote restart")
            DispatchQueue.main.async {
                self.onRemoteRestart?()
            }

        case "pushContent":
            print("[CloudChannel] Command: push content")
            DispatchQueue.main.async {
                self.onForceReload?()
            }

        case "requestScreenshot":
            print("[CloudChannel] Request: screenshot")
            let requestId = (json["requestId"] as? String)
                ?? ((json["payload"] as? [String: Any])?["requestId"] as? String)
                ?? ""
            captureAndSendScreenshot(requestId: requestId)

        case "requestDeviceStatus":
            print("[CloudChannel] Request: device status")
            let requestId = (json["requestId"] as? String)
                ?? ((json["payload"] as? [String: Any])?["requestId"] as? String)
                ?? ""
            sendDeviceStatus(requestId: requestId)

        default:
            print("[CloudChannel] Unknown message type: \(type)")
        }
    }

    private func captureAndSendScreenshot(requestId: String) {
        DispatchQueue.main.async { [weak self] in
            guard let screen = NSScreen.main else {
                self?.sendResponse(type: "screenshotResponse", requestId: requestId, payload: ["error": "No screen available"])
                return
            }

            let rect = screen.frame
            if let cgImage = CGWindowListCreateImage(rect, .optionOnScreenOnly, kCGNullWindowID, .bestResolution) {
                let bitmapRep = NSBitmapImageRep(cgImage: cgImage)
                if let pngData = bitmapRep.representation(using: .png, properties: [:]) {
                    let base64 = "data:image/png;base64," + pngData.base64EncodedString()
                    self?.sendResponse(type: "screenshotResponse", requestId: requestId, payload: ["imageData": base64])
                    return
                }
            }
            self?.sendResponse(type: "screenshotResponse", requestId: requestId, payload: ["error": "Screenshot capture failed"])
        }
    }

    private func sendDeviceStatus(requestId: String) {
        let processInfo = ProcessInfo.processInfo
        let appState = AppState.shared

        let payload: [String: Any] = [
            "platform": "macos",
            "version": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0",
            "osVersion": processInfo.operatingSystemVersionString,
            "hostname": Host.current().localizedName ?? "Unknown",
            "totalMemory": processInfo.physicalMemory,
            "cpus": processInfo.processorCount,
            "uptime": processInfo.systemUptime,
            "primaryMode": appState.connectionMode,
            "primaryUrl": appState.serverUrl,
            "cloudConnected": isConnected,
        ]

        sendResponse(type: "deviceStatusResponse", requestId: requestId, payload: payload)
    }

    private func sendResponse(type: String, requestId: String, payload: [String: Any]) {
        let message: [String: Any] = [
            "type": type,
            "requestId": requestId,
            "payload": payload
        ]

        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let str = String(data: data, encoding: .utf8) else { return }

        webSocketTask?.send(.string(str)) { error in
            if let error = error {
                print("[CloudChannel] Failed to send response: \(error)")
            }
        }
    }

    func sendMessage(_ message: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let str = String(data: data, encoding: .utf8) else { return }

        webSocketTask?.send(.string(str)) { error in
            if let error = error {
                print("[CloudChannel] Failed to send message: \(error)")
            }
        }
    }

    private func scheduleReconnect(primaryMode: String, primaryUrl: String) {
        guard isRunning else { return }

        reconnectAttempts += 1
        let delay = min(
            reconnectBaseSeconds * pow(2.0, Double(reconnectAttempts - 1)),
            reconnectMaxSeconds
        )

        print("[CloudChannel] Reconnecting in \(Int(delay))s (attempt \(reconnectAttempts))")

        reconnectTimer?.invalidate()
        reconnectTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            self?.connect(primaryMode: primaryMode, primaryUrl: primaryUrl)
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("[CloudChannel] Connected")
        DispatchQueue.main.async {
            self.isConnected = true
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        print("[CloudChannel] Disconnected")
        DispatchQueue.main.async {
            self.isConnected = false
        }
    }
}
