package com.nexuscast.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebKioskRenderer — WebView for interactive kiosk files.
 * Touch input enabled. Scheduler enters INTERACTIVE_KIOSK_ACTIVE state.
 * Exits on: requestExit, idle timeout (60s), max session timeout (300s), or error.
 */
public class WebKioskRenderer {

    private static final String TAG = "WebKioskRenderer";
    private static final long LOAD_TIMEOUT_MS = 10_000;
    private static final long IDLE_TIMEOUT_MS = 60_000;
    private static final long MAX_SESSION_MS = 300_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;

    public interface Listener {
        void onKioskReady(String slideId);
        void onKioskExit(String slideId, String reason);
        void onKioskEvent(String slideId, String eventName, String payload);
    }

    private final Context ctx;
    private final ViewGroup container;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private String currentSlideId = "";
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private Runnable timeoutRunnable, idleRunnable, sessionRunnable, heartbeatRunnable;

    public WebKioskRenderer(Context ctx, ViewGroup container, Listener listener) {
        this.ctx = ctx; this.container = container; this.listener = listener;
    }

    public void prepare(String slideId, String url) {
        this.currentSlideId = slideId;
        ready.set(false);
        ensureWebView();
        hide();
        cancelTimers();
        timeoutRunnable = () -> { if (!ready.get()) listener.onKioskExit(slideId, "load_timeout"); };
        handler.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS);
        sessionRunnable = () -> listener.onKioskExit(slideId, "max_session");
        handler.postDelayed(sessionRunnable, MAX_SESSION_MS);
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
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setBackgroundColor(0xFF0a0e1a);
        webView.setVisibility(View.INVISIBLE);
        webView.addJavascriptInterface(new KioskBridge(), "Digipal");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) listener.onKioskExit(currentSlideId, "page_error");
            }
        });
        container.addView(webView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void cancelTimers() {
        if (timeoutRunnable != null) { handler.removeCallbacks(timeoutRunnable); timeoutRunnable = null; }
        if (idleRunnable != null) { handler.removeCallbacks(idleRunnable); idleRunnable = null; }
        if (sessionRunnable != null) { handler.removeCallbacks(sessionRunnable); sessionRunnable = null; }
        if (heartbeatRunnable != null) { handler.removeCallbacks(heartbeatRunnable); heartbeatRunnable = null; }
    }

    private void resetIdleTimer() {
        if (idleRunnable != null) handler.removeCallbacks(idleRunnable);
        idleRunnable = () -> listener.onKioskExit(currentSlideId, "idle_timeout");
        handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS);
    }

    private void resetHeartbeatTimer() {
        if (heartbeatRunnable != null) handler.removeCallbacks(heartbeatRunnable);
        heartbeatRunnable = () -> listener.onKioskExit(currentSlideId, "heartbeat_timeout");
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_TIMEOUT_MS);
    }

    private class KioskBridge {
        @JavascriptInterface public void ready(String slideId) {
            handler.post(() -> {
                if (timeoutRunnable != null) { handler.removeCallbacks(timeoutRunnable); timeoutRunnable = null; }
                ready.set(true);
                show();
                resetIdleTimer();
                resetHeartbeatTimer();
                listener.onKioskReady(slideId);
            });
        }
        @JavascriptInterface public void error(String slideId, String code, String message) {
            handler.post(() -> listener.onKioskExit(slideId, code + ": " + message));
        }
        @JavascriptInterface public void heartbeat(String slideId) {
            handler.post(() -> { resetIdleTimer(); resetHeartbeatTimer(); });
        }
        @JavascriptInterface public void event(String slideId, String eventName, String payload) {
            handler.post(() -> { resetIdleTimer(); listener.onKioskEvent(slideId, eventName, payload); });
        }
        @JavascriptInterface public void requestNavigation(String slideId, String target) {
            Log.d(TAG, "[navigate] " + slideId + " -> " + target);
        }
        @JavascriptInterface public void requestExit(String slideId) {
            handler.post(() -> listener.onKioskExit(slideId, "js_exit_requested"));
        }
    }
}
