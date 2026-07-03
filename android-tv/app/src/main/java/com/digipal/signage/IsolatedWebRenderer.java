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
 * IsolatedWebRenderer — per-slide isolated WebView for design/kiosk/url content
 * (task #1875). Unlike the legacy long-lived WebView (which drives the whole
 * playlist via __digipalGotoSlide), this loads exactly one slide per WebView
 * instance from the standalone /tv/render/:pairingCode/:contentId route (or a
 * raw URL for WEBVIEW_URL slides), so a crash/hang on one design cannot take
 * down subsequent slides. Hidden until Digipal.ready(slideId) fires from JS.
 * Behind FEATURE_ISOLATED_WEB_RENDERER (default OFF in PlaylistScheduler) —
 * any timeout/error routes back to the legacy WebView flow via
 * PlaylistScheduler.onIsolatedRendererFailed().
 */
public class IsolatedWebRenderer {

    private static final String TAG = "IsolatedWebRenderer";
    private static final long LOAD_TIMEOUT_MS = 8_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;

    public interface Listener {
        void onRendererReady(String slideId);
        void onRendererFailed(String slideId, String reason);
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

    public IsolatedWebRenderer(Context ctx, ViewGroup container, Listener listener) {
        this.ctx = ctx;
        this.container = container;
        this.listener = listener;
    }

    private WebViewPolicy currentPolicy;

    public void prepare(String slideId, String url) {
        prepare(slideId, url, null);
    }

    /**
     * Prepares this renderer for a slide with an explicit {@link WebViewPolicy}. Pass
     * {@code null} to fall back to the safe default (equivalent to the old two-bucket
     * applyWebViewProfile behavior) — task: Per-Asset WebView Policy.
     */
    public void prepare(String slideId, String url, WebViewPolicy policy) {
        this.currentSlideId = slideId;
        this.currentPolicy = policy;
        this.ready.set(false);
        if (policy != null && policy.freshWebView) {
            // Per-asset policy demands a brand-new WebView instance (e.g. interactive
            // kiosk content) rather than reusing whatever instance is already alive.
            destroy();
        }
        ensureWebView();
        if (policy != null) policy.applyTo(webView);
        hide();
        cancelTimers();

        long timeoutMs = (policy != null && policy.readyTimeoutMs > 0) ? policy.readyTimeoutMs : LOAD_TIMEOUT_MS;
        timeoutRunnable = () -> {
            if (!ready.get()) {
                Log.w(TAG, "[timeout] " + slideId + " url=" + url);
                listener.onRendererFailed(slideId, "load_timeout");
            }
        };
        handler.postDelayed(timeoutRunnable, timeoutMs);

        webView.loadUrl(url);
    }

    public void show() { if (webView != null) webView.setVisibility(View.VISIBLE); }
    public void hide() { if (webView != null) webView.setVisibility(View.INVISIBLE); }

    /** True while a WebView instance is allocated (visible or hidden-but-alive). Used by
     *  the low-memory WebView policy (task) to decide whether an aggressive destroy is
     *  needed on CRITICAL tier vs. reuse on NORMAL/LOW tier. */
    public boolean isAlive() { return webView != null; }

    /** True only while the renderer is the one currently on screen. Used to avoid
     *  destroying a WebView that is actively showing content. */
    public boolean isShowing() { return webView != null && webView.getVisibility() == View.VISIBLE; }

    public void destroy() {
        cancelTimers();
        if (currentPolicy != null && currentPolicy.clearCookiesOnExit) {
            try { android.webkit.CookieManager.getInstance().removeAllCookies(null); } catch (Throwable ignored) {}
        }
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
        webView.addJavascriptInterface(new RenderBridge(), "Digipal");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) listener.onRendererFailed(currentSlideId, "page_error");
            }
            // CRITICAL: without this override, a Chromium renderer crash in this
            // per-slide WebView (e.g. a heavy Design Studio canvas OOMing the
            // renderer process on a low-RAM Fire TV) takes down the ENTIRE app
            // process instead of just this slide — the default Android behavior
            // for onRenderProcessGone when unhandled (targetSdkVersion O+) is to
            // crash the whole app, and because the crash originates in the
            // WebView renderer process rather than a Java exception, the
            // UncaughtExceptionHandler-based crash-report pipeline never fires
            // either (no report reaches the server). Handling it here keeps the
            // isolation promise this renderer is named for: destroy the dead
            // WebView, fail over to the legacy shared WebView via
            // onRendererFailed(), and return true so Android does NOT kill the
            // app process.
            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "[onRenderProcessGone] slide=" + currentSlideId
                        + " didCrash=" + (detail != null && detail.didCrash()));
                final String failedSlideId = currentSlideId;
                try {
                    container.removeView(v);
                    v.destroy();
                } catch (Throwable ignored) {}
                if (v == webView) webView = null;
                cancelTimers();
                ready.set(false);
                handler.post(() -> listener.onRendererFailed(failedSlideId,
                        (detail != null && detail.didCrash()) ? "render_process_crashed" : "render_process_killed"));
                return true;
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
        heartbeatRunnable = () -> listener.onRendererFailed(currentSlideId, "heartbeat_timeout");
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_TIMEOUT_MS);
    }

    private void injectCustomJsWithRetries(int attemptsLeft) {
        if (webView == null || currentPolicy == null || currentPolicy.customJs == null) return;
        try {
            webView.evaluateJavascript(currentPolicy.customJs, result -> {
                if ((result == null || result.equals("null")) && attemptsLeft > 0) {
                    handler.postDelayed(() -> injectCustomJsWithRetries(attemptsLeft - 1), 500);
                }
            });
        } catch (Throwable ignored) {}
    }

    private class RenderBridge {
        @JavascriptInterface public void ready(String slideId) {
            handler.post(() -> {
                cancelTimers();
                ready.set(true);
                show();
                resetHeartbeatTimer();
                if (currentPolicy != null && currentPolicy.customJs != null) {
                    injectCustomJsWithRetries(currentPolicy.customJsMaxRetries);
                }
                listener.onRendererReady(slideId);
            });
        }
        @JavascriptInterface public void error(String slideId, String code, String message) {
            handler.post(() -> listener.onRendererFailed(slideId, code + ": " + message));
        }
        @JavascriptInterface public void heartbeat(String slideId) {
            handler.post(() -> resetHeartbeatTimer());
        }
        @JavascriptInterface public void event(String slideId, String eventName, String payload) {
            Log.d(TAG, "[event] " + slideId + " " + eventName);
        }
        @JavascriptInterface public void requestNavigation(String slideId, String target) {
            Log.d(TAG, "[requestNavigation] " + slideId + " -> " + target);
        }
        @JavascriptInterface public void requestExit(String slideId) {
            handler.post(() -> listener.onRendererFailed(slideId, "exit_requested"));
        }
    }
}
