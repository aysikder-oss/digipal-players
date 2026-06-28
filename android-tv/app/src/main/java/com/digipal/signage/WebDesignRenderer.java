package com.digipal.signage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebDesignRenderer — isolated WebView for Digipal canvas design files.
 * Hidden until Digipal.ready(slideId) fires from JavaScript.
 * Minimal JS bridge: ready / error / heartbeat / event / requestNavigation / requestExit.
 * Native scheduler enforces load timeout (default 8 s).
 */
public class WebDesignRenderer {

    private static final String TAG = "WebDesignRenderer";
    private static final long LOAD_TIMEOUT_MS = 8_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 12_000;

    public interface Listener {
        void onDesignReady(String slideId);
        void onDesignError(String slideId, String error);
        void onDesignEvent(String slideId, String eventName, String payload);
    }

    private final Context ctx;
    private final ViewGroup container;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private String currentSlideId = "";
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private Runnable timeoutRunnable;
    private Runnable heartbeatRunnable;

    public WebDesignRenderer(Context ctx, ViewGroup container, Listener listener) {
        this.ctx = ctx;
        this.container = container;
        this.listener = listener;
    }

    public void prepare(String slideId, String url) {
        this.currentSlideId = slideId;
        this.ready.set(false);
        ensureWebView();
        hide();
        cancelTimers();

        // Load timeout
        timeoutRunnable = () -> {
            if (!ready.get()) {
                Log.w(TAG, "[timeout] " + slideId);
                listener.onDesignError(slideId, "load_timeout");
            }
        };
        handler.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MS);

        webView.loadUrl(url);
    }

    public void show() {
        if (webView != null) webView.setVisibility(View.VISIBLE);
    }

    public void hide() {
        if (webView != null) webView.setVisibility(View.INVISIBLE);
    }

    public void destroy() {
        cancelTimers();
        if (webView != null) {
            container.removeView(webView);
            webView.destroy();
            webView = null;
        }
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
        webView.addJavascriptInterface(new DesignBridge(), "Digipal");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) listener.onDesignError(currentSlideId, "page_error");
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
        heartbeatRunnable = () -> listener.onDesignError(currentSlideId, "heartbeat_timeout");
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_TIMEOUT_MS);
    }

    private class DesignBridge {
        @JavascriptInterface public void ready(String slideId) {
            handler.post(() -> {
                cancelTimers();
                ready.set(true);
                show();
                resetHeartbeatTimer();
                listener.onDesignReady(slideId);
            });
        }
        @JavascriptInterface public void error(String slideId, String code, String message) {
            handler.post(() -> listener.onDesignError(slideId, code + ": " + message));
        }
        @JavascriptInterface public void heartbeat(String slideId) {
            handler.post(() -> resetHeartbeatTimer());
        }
        @JavascriptInterface public void event(String slideId, String eventName, String payload) {
            handler.post(() -> listener.onDesignEvent(slideId, eventName, payload));
        }
        @JavascriptInterface public void requestNavigation(String slideId, String target) {
            Log.d(TAG, "[requestNavigation] " + slideId + " -> " + target);
        }
        @JavascriptInterface public void requestExit(String slideId) {
            handler.post(() -> listener.onDesignError(slideId, "exit_requested"));
        }
    }
}
