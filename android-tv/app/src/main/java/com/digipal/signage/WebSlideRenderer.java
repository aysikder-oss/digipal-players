package com.digipal.signage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;

/**
 * WebSlideRenderer — WebView for general web URL slides.
 * Hidden until page loads. Enforces load timeout (10s) and heartbeat timeout (12s).
 */
public class WebSlideRenderer {

    private static final String TAG = "WebSlideRenderer";
    private static final long LOAD_TIMEOUT_MS = 10_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 12_000;

    public interface Listener {
        void onSlideReady(String slideId);
        void onSlideError(String slideId, String error);
    }

    private final Context ctx;
    private final ViewGroup container;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private String currentSlideId = "";
    private Runnable timeoutRunnable, heartbeatRunnable;

    public WebSlideRenderer(Context ctx, ViewGroup container, Listener listener) {
        this.ctx = ctx; this.container = container; this.listener = listener;
    }

    public void prepare(String slideId, String url) {
        this.currentSlideId = slideId;
        ensureWebView();
        hide();
        cancelTimers();
        timeoutRunnable = () -> listener.onSlideError(slideId, "load_timeout");
        handler.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS);
        webView.loadUrl(url);
    }

    public void show() { if (webView != null) webView.setVisibility(View.VISIBLE); }
    public void hide() { if (webView != null) webView.setVisibility(View.INVISIBLE); }

    public void destroy() {
        cancelTimers();
        if (webView != null) { container.removeView(webView); webView.destroy(); webView = null; }
    }

    private void ensureWebView() {
        if (webView != null) return;
        webView = new WebView(ctx);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.setBackgroundColor(0xFF0a0e1a);
        webView.setVisibility(View.INVISIBLE);
        // Minimal bridge for web slides
        webView.addJavascriptInterface(new SlideBridge(), "Digipal");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                cancelTimers();
                show();
                resetHeartbeatTimer();
                listener.onSlideReady(currentSlideId);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) listener.onSlideError(currentSlideId, "page_error");
            }
        });
        container.addView(webView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void cancelTimers() {
        if (timeoutRunnable != null) { handler.removeCallbacks(timeoutRunnable); timeoutRunnable = null; }
        if (heartbeatRunnable != null) { handler.removeCallbacks(heartbeatRunnable); heartbeatRunnable = null; }
    }

    private void resetHeartbeatTimer() {
        if (heartbeatRunnable != null) handler.removeCallbacks(heartbeatRunnable);
        heartbeatRunnable = () -> listener.onSlideError(currentSlideId, "heartbeat_timeout");
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_TIMEOUT_MS);
    }

    private class SlideBridge {
        @JavascriptInterface public void ready(String slideId) {
            handler.post(() -> { cancelTimers(); show(); resetHeartbeatTimer(); listener.onSlideReady(slideId); });
        }
        @JavascriptInterface public void error(String slideId, String code, String message) {
            handler.post(() -> listener.onSlideError(slideId, code + ": " + message));
        }
        @JavascriptInterface public void heartbeat(String slideId) {
            handler.post(() -> resetHeartbeatTimer());
        }
        @JavascriptInterface public void event(String slideId, String eventName, String payload) {}
        @JavascriptInterface public void requestNavigation(String slideId, String target) {}
        @JavascriptInterface public void requestExit(String slideId) {}
    }
}
