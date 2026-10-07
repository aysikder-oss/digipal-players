package com.nexuscast.player;

import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ST-only compatibility boundary for the shared player's MediaPipe 0.10.32 SDK.
 * Never use the CDN latest alias as a fallback for a missing packaged resource.
 */
final class GestureRuntimeAssets {
    static final String VERSION = "0.10.32";
    static final String ASSET_ROOT = "mediapipe/" + VERSION + "/";
    private static final String CDN_ROOT = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@";
    private static final String[] FILES = {
        "vision_wasm_internal.js", "vision_wasm_internal.wasm",
        "vision_wasm_nosimd_internal.js", "vision_wasm_nosimd_internal.wasm"
    };

    interface OriginPolicy { boolean trusted(String url); }
    private final AssetManager assets;

    GestureRuntimeAssets(AssetManager assets) { this.assets = assets; }

    // Compare the complete URL, not decoded paths, suffixes or host substrings.
    static String resourceName(String url) {
        for (String name : FILES) {
            if ((CDN_ROOT + "latest/wasm/" + name).equals(url)
                    || (CDN_ROOT + VERSION + "/wasm/" + name).equals(url)) return name;
        }
        return null;
    }

    WebResourceResponse intercept(WebResourceRequest request, boolean trustedDocument, OriginPolicy policy) {
        if (request == null || request.getUrl() == null) return null;
        String name = resourceName(request.getUrl().toString());
        if (name == null) return null; // Preserve all existing media/shell/network routing.
        if (!"GET".equals(request.getMethod())) return error(405, "Method Not Allowed", name);
        // shouldInterceptRequest runs off the UI thread: never call WebView.getUrl here.
        // The activity records document trust in onPageStarted. Also validate the
        // requesting origin/referrer so an untrusted iframe cannot use the pin.
        boolean hasInitiator = false;
        Map<String, String> requestHeaders = request.getRequestHeaders();
        if (requestHeaders != null) {
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                if ("Origin".equalsIgnoreCase(header.getKey()) || "Referer".equalsIgnoreCase(header.getKey())) {
                    if (!policy.trusted(header.getValue())) return error(403, "Forbidden", name);
                    hasInitiator = true;
                }
            }
        }
        if (!trustedDocument || request.isForMainFrame() || !hasInitiator) {
            return error(403, "Forbidden", name);
        }
        String mime = name.endsWith(".wasm") ? "application/wasm" : "application/javascript";
        try {
            return new WebResourceResponse(mime, name.endsWith(".js") ? "UTF-8" : null,
                    200, "OK", headers(), assets.open(ASSET_ROOT + name));
        } catch (IOException failure) {
            Log.e("STGestureRuntime", "Packaged runtime unavailable: " + ASSET_ROOT + name, failure);
            return error(503, "Service Unavailable", name);
        }
    }

    private static Map<String, String> headers() {
        Map<String, String> result = new HashMap<>();
        // Same CORS behavior as the CDN; initiator authorization is enforced above.
        result.put("Access-Control-Allow-Origin", "*");
        result.put("Access-Control-Expose-Headers", "X-Digipal-Gesture-Runtime");
        result.put("Cache-Control", "no-store");
        result.put("X-Content-Type-Options", "nosniff");
        result.put("X-Digipal-Gesture-Runtime", VERSION);
        return result;
    }

    private static WebResourceResponse error(int status, String reason, String name) {
        String message = "ST gesture runtime " + VERSION + ": " + reason + " (" + name + ")";
        Log.e("STGestureRuntime", message);
        return new WebResourceResponse("text/plain", "UTF-8", status, reason, headers(),
                new ByteArrayInputStream(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
