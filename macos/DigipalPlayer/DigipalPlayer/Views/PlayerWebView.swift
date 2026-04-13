import SwiftUI
import WebKit

struct PlayerWebView: NSViewRepresentable {
    @EnvironmentObject var appState: AppState

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.preferences.setValue(true, forKey: "developerExtrasEnabled")
        config.mediaTypesRequiringUserActionForPlayback = []
        config.allowsAirPlayForMediaPlayback = true

        let contentController = config.userContentController
        let handler = context.coordinator
        contentController.add(handler, name: "digipal")

        let bridgeScript = WKUserScript(
            source: Self.bridgeJavaScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        )
        contentController.addUserScript(bridgeScript)

        config.setURLSchemeHandler(LocalMediaSchemeHandler(), forURLScheme: "local-media")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = false
        webView.setValue(false, forKey: "drawsBackground")

        context.coordinator.webView = webView

        NotificationCenter.default.addObserver(
            forName: .toggleWebInspector,
            object: nil,
            queue: .main
        ) { _ in
            webView.configuration.preferences.setValue(true, forKey: "developerExtrasEnabled")
            if let inspector = webView.value(forKey: "_inspector") as? NSObject {
                inspector.perform(NSSelectorFromString("show:"), with: nil)
            }
        }

        if let url = URL(string: appState.playerUrl) {
            webView.load(URLRequest(url: url))
        }

        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {
        let currentUrl = webView.url?.absoluteString ?? ""
        let expectedBase = appState.serverUrl

        if !currentUrl.hasPrefix(expectedBase) && !appState.showSetup {
            if let url = URL(string: appState.playerUrl) {
                webView.load(URLRequest(url: url))
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(appState: appState)
    }

    static let bridgeJavaScript = """
    window.Android = {
        downloadMedia: function(objectPath, signedUrl) {
            window.webkit.messageHandlers.digipal.postMessage({
                action: 'downloadMedia',
                objectPath: objectPath,
                signedUrl: signedUrl
            });
        },
        getLocalMediaPath: function(objectPath) {
            return window.__digipal_localPaths?.[objectPath] || '';
        },
        deleteMedia: function(objectPath) {
            window.webkit.messageHandlers.digipal.postMessage({
                action: 'deleteMedia',
                objectPath: objectPath
            });
            return true;
        },
        deleteAllMedia: function() {
            window.webkit.messageHandlers.digipal.postMessage({
                action: 'deleteAllMedia'
            });
            return 0;
        },
        getStorageInfo: function() {
            return window.__digipal_storageInfo || '{"usedBytes":0,"freeBytes":0,"totalSpace":0,"totalFiles":0}';
        },
        setAutoRelaunch: function(enabled) {
            window.webkit.messageHandlers.digipal.postMessage({
                action: 'setAutoRelaunch',
                enabled: enabled
            });
        },
        scheduleRelaunch: function() {
            window.webkit.messageHandlers.digipal.postMessage({
                action: 'scheduleRelaunch'
            });
        },
        getDeviceInfo: function() {
            return window.__digipal_deviceInfo || '{"platform":"macos","version":"1.0.0","osVersion":"unknown"}';
        },
        clearCache: function() {
            window.webkit.messageHandlers.digipal.postMessage({
                action: 'clearCache'
            });
        },
        restartApp: function() {
            window.webkit.messageHandlers.digipal.postMessage({
                action: 'restartApp'
            });
        }
    };
    window.__digipal_localPaths = {};
    """

    static func deviceInfoJSON() -> String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        let osVersion = "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)"
        let info: [String: String] = [
            "platform": "macos",
            "version": "1.0.0",
            "osVersion": osVersion
        ]
        if let data = try? JSONSerialization.data(withJSONObject: info),
           let str = String(data: data, encoding: .utf8) {
            return str
        }
        return "{\"platform\":\"macos\",\"version\":\"1.0.0\",\"osVersion\":\"unknown\"}"
    }

    class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var appState: AppState
        weak var webView: WKWebView?
        private var retryCount = 0
        private let maxRetries = 100
        private var retryTimer: Timer?

        init(appState: AppState) {
            self.appState = appState
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any],
                  let action = body["action"] as? String else { return }

            switch action {
            case "downloadMedia":
                guard let objectPath = body["objectPath"] as? String,
                      let signedUrl = body["signedUrl"] as? String else { return }
                MediaManager.shared.downloadMedia(objectPath: objectPath, signedUrl: signedUrl) { [weak self] localUrl in
                    DispatchQueue.main.async {
                        self?.notifyMediaDownloaded(objectPath: objectPath, localUrl: localUrl)
                    }
                }

            case "deleteMedia":
                if let objectPath = body["objectPath"] as? String {
                    MediaManager.shared.deleteMedia(objectPath: objectPath)
                }

            case "deleteAllMedia":
                MediaManager.shared.deleteAllMedia()

            case "setAutoRelaunch":
                if let enabled = body["enabled"] as? Bool {
                    appState.autoStartOnLogin = enabled
                    if let delegate = NSApp.delegate as? AppDelegate {
                        if enabled {
                            delegate.enableAutoStart()
                        } else {
                            delegate.disableAutoStart()
                        }
                    }
                }

            case "scheduleRelaunch", "restartApp":
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                    let url = Bundle.main.bundleURL
                    let config = NSWorkspace.OpenConfiguration()
                    NSWorkspace.shared.openApplication(at: url, configuration: config)
                    NSApp.terminate(nil)
                }

            case "clearCache":
                MediaManager.shared.deleteAllMedia()
                updateStorageInfo()

            default:
                break
            }
        }

        private func notifyMediaDownloaded(objectPath: String, localUrl: String) {
            let js = """
            window.__digipal_localPaths[\(Self.jsString(objectPath))] = \(Self.jsString(localUrl));
            if(window.__onMediaDownloaded) window.__onMediaDownloaded(\(Self.jsString(objectPath)), \(Self.jsString(localUrl)));
            """
            webView?.evaluateJavaScript(js)
        }

        private static func jsString(_ str: String) -> String {
            let escaped = str
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "'", with: "\\'")
                .replacingOccurrences(of: "\n", with: "\\n")
                .replacingOccurrences(of: "\r", with: "\\r")
            return "'\(escaped)'"
        }

        func updateStorageInfo() {
            let info = MediaManager.shared.getStorageInfo()
            let js = "window.__digipal_storageInfo = \(Self.jsString(info));"
            webView?.evaluateJavaScript(js)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            retryCount = 0
            retryTimer?.invalidate()
            DispatchQueue.main.async {
                self.appState.isConnected = true
            }
            updateStorageInfo()

            let deviceInfoJson = PlayerWebView.deviceInfoJSON()
            let escapedJson = deviceInfoJson.replacingOccurrences(of: "'", with: "\\'")
            webView.evaluateJavaScript("window.__digipal_deviceInfo = '\(escapedJson)';")

            NotificationCenter.default.post(name: .playerDidConnect, object: nil)

            let localPaths = MediaManager.shared.getAllLocalPaths()
            for (objectPath, localUrl) in localPaths {
                let js = "window.__digipal_localPaths[\(Self.jsString(objectPath))] = \(Self.jsString(localUrl));"
                webView.evaluateJavaScript(js)
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            handleLoadError(webView: webView)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            handleLoadError(webView: webView)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if navigationAction.navigationType == .linkActivated,
               let url = navigationAction.request.url,
               !url.absoluteString.hasPrefix(appState.serverUrl) {
                NSWorkspace.shared.open(url)
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        private func handleLoadError(webView: WKWebView) {
            DispatchQueue.main.async {
                self.appState.isConnected = false
                NotificationCenter.default.post(name: .playerDidDisconnect, object: nil)
            }

            guard retryCount < maxRetries else { return }
            retryCount += 1

            let delay = min(pow(2.0, Double(retryCount)), 30.0)
            retryTimer?.invalidate()
            retryTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
                guard let self = self, let url = URL(string: self.appState.playerUrl) else { return }
                webView.load(URLRequest(url: url))
            }
        }
    }
}

class LocalMediaSchemeHandler: NSObject, WKURLSchemeHandler {
    private static let mediaCacheRoot: String = {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        return appSupport.appendingPathComponent("DigipalPlayer/media").path
    }()

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else {
            urlSchemeTask.didFailWithError(URLError(.badURL))
            return
        }

        let filePath = url.absoluteString
            .replacingOccurrences(of: "local-media://", with: "")
            .removingPercentEncoding ?? ""

        let resolvedPath = (filePath as NSString).standardizingPath
        let cacheRoot = Self.mediaCacheRoot.hasSuffix("/") ? Self.mediaCacheRoot : Self.mediaCacheRoot + "/"

        guard resolvedPath == Self.mediaCacheRoot || resolvedPath.hasPrefix(cacheRoot) else {
            urlSchemeTask.didFailWithError(URLError(.noPermissionsToReadFile))
            return
        }

        let fileUrl = URL(fileURLWithPath: resolvedPath)

        guard FileManager.default.fileExists(atPath: resolvedPath) else {
            urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist))
            return
        }

        do {
            let data = try Data(contentsOf: fileUrl)
            let mimeType = Self.mimeType(for: filePath)
            let response = URLResponse(
                url: url,
                mimeType: mimeType,
                expectedContentLength: data.count,
                textEncodingName: nil
            )
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(data)
            urlSchemeTask.didFinish()
        } catch {
            urlSchemeTask.didFailWithError(error)
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}

    private static func mimeType(for path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        switch ext {
        case "mp4": return "video/mp4"
        case "webm": return "video/webm"
        case "mov": return "video/quicktime"
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "svg": return "image/svg+xml"
        case "pdf": return "application/pdf"
        case "html", "htm": return "text/html"
        case "css": return "text/css"
        case "js": return "application/javascript"
        default: return "application/octet-stream"
        }
    }
}
