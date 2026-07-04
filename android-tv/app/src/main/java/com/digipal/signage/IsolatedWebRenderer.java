package com.digipal.signage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IsolatedWebRenderer — per-slide isolated WebView for design/kiosk/url content
 * (task #1875). Unlike the old shared long-lived WebView (which used to drive
 * the whole playlist via __digipalGotoSlide, retired task #1886 — WebDesign/
 * WebKiosk/WebSlideRenderer were deleted as dead code), this loads exactly one
 * slide per WebView instance from the standalone /tv/render/:pairingCode/
 * :contentId route (or a raw URL for WEBVIEW_URL slides), so a crash/hang on
 * one design cannot take down subsequent slides. Hidden until
 * Digipal.ready(slideId) fires from JS. This is now the only WebView-delegated
 * render path (task P7 legacy cleanup: the FEATURE_ISOLATED_WEB_RENDERER flag
 * this comment used to reference has been removed — PlaylistScheduler always
 * dispatches WEBVIEW_DESIGN/KIOSK/URL here unconditionally); any timeout/error
 * still routes to a retry via PlaylistScheduler.onIsolatedRendererFailed().
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
      /** Optional -- used only for P7 diagnostic telemetry (renderer_process_gone,
       *  stale_*_ignored, subresource/stale page errors). Null-safe: telemetry is
       *  skipped entirely if not supplied. */
      private final TelemetryManager telemetry;
      private final Handler handler = new Handler(Looper.getMainLooper());
      private WebView webView;
        private String currentSlideId = "";
        /** Monotonically-increasing token minted fresh on every {@code prepare()} call
         *  (task P2: isolated WebView stale events + security token). Guards against
         *  a race where a stale WebView instance's async JS callback (ready/error/
         *  heartbeat/event/etc.) arrives after this renderer has already been
         *  re-prepared for a *different* slide that happens to share the same
         *  slideId (e.g. the same content repeated later in a loop, or a slideId
         *  reused across playlist revisions) -- slideId-equality alone cannot tell
         *  those two generations apart, but the token is unique per generation. */
        private long currentRenderToken = 0L;
        private static final java.util.concurrent.atomic.AtomicLong tokenSeq = new java.util.concurrent.atomic.AtomicLong(0);
        private final AtomicBoolean ready = new AtomicBoolean(false);

      /** True only if {@code slideId} matches the slide this renderer is currently
       *  showing/loading. RenderBridge callbacks (ready/error/heartbeat/event/
       *  requestNavigation/requestExit) fire asynchronously from JS and can arrive
       *  after the scheduler has already moved on to (or reused this WebView for) a
       *  different slide; acting on a stale callback would show/hide the wrong
       *  content or reset timers for a slide that is no longer active. */
      private boolean isCurrentSlide(String slideId) {
            return slideId != null && slideId.equals(currentSlideId);
        }

        /** True only if BOTH the slideId and the render token match the generation
         *  this renderer is currently showing/loading. See {@link #currentRenderToken}. */
        private boolean isCurrentGeneration(String slideId, long renderToken) {
            return isCurrentSlide(slideId) && renderToken == currentRenderToken;
        }
    private Runnable timeoutRunnable;
      private Runnable heartbeatRunnable;
      /** Unified Design Studio Renderer stabilization (Step 3): optional, null-safe.
       *  Backs window.DigipalMedia (see DigipalMediaBridge) and shouldInterceptRequest()'s
       *  virtual media-URL resolution for locally-cached design/kiosk media. */
      private final MediaDownloadManager mediaDownloadManager;

      public IsolatedWebRenderer(Context ctx, ViewGroup container, Listener listener) {
            this(ctx, container, listener, null, null);
        }

        public IsolatedWebRenderer(Context ctx, ViewGroup container, Listener listener, TelemetryManager telemetry) {
            this(ctx, container, listener, telemetry, null);
        }

        // Unified Design Studio Renderer stabilization (Step 3): optional MediaDownloadManager
        // backs the window.DigipalMedia JS bridge (see DigipalMediaBridge below) and
        // shouldInterceptRequest()'s virtual-media-URL resolution. Null-safe everywhere it's
        // used, so callers that don't pass one (or run on older call sites) just fall back to
        // remote-only media loading with no behavior change.
        public IsolatedWebRenderer(Context ctx, ViewGroup container, Listener listener, TelemetryManager telemetry, MediaDownloadManager mediaDownloadManager) {
            this.ctx = ctx;
            this.container = container;
            this.listener = listener;
            this.telemetry = telemetry;
            this.mediaDownloadManager = mediaDownloadManager;
        }

    private WebViewPolicy currentPolicy;
    /** Host of the URL most recently passed to prepare(). The top-level
 *  frame of an isolated-renderer WebView must never leave this host (task
 *  P5: harden WebView policies) -- everything we load, including
 *  third-party Canva/Website/URL content, is served through our own
 *  /tv/render/:pairingCode/:contentId route, which embeds any external
 *  content in an iframe rather than navigating the top frame there. If a
 *  page inside that iframe (or a JS bug) triggers a top-level navigation
 *  away from our origin, the "Digipal" JavascriptInterface added in
 *  ensureWebView() would otherwise remain attached and reachable
 *  by the new, untrusted origin -- a known WebView JS-interface exposure
 *  class of vulnerability. shouldOverrideUrlLoading below blocks any such
 *  navigation instead of letting the WebView leave trustedHost. */
    private String trustedHost;

      /** URL most recently loaded via prepare() (task P7: renderer safety/telemetry) --
       *  used to tell a genuine load failure of the current slide's own page apart from
       *  a stray error bubbling up from an already-superseded WebView/request. */
      private String currentRenderUrl;

      /** Counts diagnostic ("ignored") events logged for the current generation
       *  (task P7). Kept small and reset on every prepare() so a chatty/broken
       *  page cannot spam the telemetry server with unbounded stale/ignored events. */
      private int diagnosticLogCount = 0;
      private static final int MAX_DIAGNOSTIC_LOGS_PER_GENERATION = 5;

      private void logDiagnostic(String eventType, String slideId, long renderToken, String detail) {
          if (diagnosticLogCount >= MAX_DIAGNOSTIC_LOGS_PER_GENERATION) return;
          diagnosticLogCount++;
          Log.w(TAG, "[" + eventType + "] " + slideId + "/" + renderToken + " current=" + currentSlideId + "/" + currentRenderToken + (detail != null ? " " + detail : ""));
          if (telemetry != null) {
              try {
                  org.json.JSONObject details = new org.json.JSONObject();
                  details.put("renderToken", renderToken);
                  details.put("currentSlideId", currentSlideId);
                  details.put("currentRenderToken", currentRenderToken);
                  if (detail != null) details.put("detail", detail);
                  telemetry.logEvent(eventType, slideId, details.toString());
              } catch (Throwable ignored) {}
          }
      }

    public void prepare(String slideId, String url) {
        prepare(slideId, url, null);
    }

    /**
     * Prepares this renderer for a slide with an explicit {@link WebViewPolicy}. Pass
     * {@code null} to fall back to the safe default (equivalent to the old two-bucket
     * applyWebViewProfile behavior) — task: Per-Asset WebView Policy.
     */
    /** True once a {@code freshWebView}-policy slide has been prepared (task #1892:
       *  destroy after use). Consumed the *next* time prepare() runs so the fresh
       *  instance is torn down before the following slide loads, regardless of
       *  whether that next slide also demands freshWebView. Prevents a "fresh" kiosk/
       *  interactive WebView (with JS state, timers, cookies) from silently being
       *  reused by a subsequent non-fresh slide. */
      private boolean pendingFreshWebViewTeardown = false;

      public void prepare(String slideId, String url, WebViewPolicy policy) {
          this.currentSlideId = slideId;
          this.currentPolicy = policy;
          this.ready.set(false);
            this.currentRenderToken = tokenSeq.incrementAndGet();
            // Append the render token so the loaded page can echo it back on every
            // Digipal.ready/error/heartbeat/event/requestNavigation/requestExit call
            // (see RenderBridge below) -- lets us tell apart two generations of the
            // same slideId, which slideId matching alone cannot do.
            url = url + (url.contains("?") ? "&" : "?") + "renderToken=" + this.currentRenderToken;
              // javac requires effectively-final captured locals inside the timeoutRunnable
              // lambda below; url is reassigned above (renderToken append) so it no longer
              // qualifies -- capture a separate final copy for the lambda to reference.
              final String urlForTimeoutLog = url;
          if (pendingFreshWebViewTeardown) {
              // Previous slide required a fresh WebView; it must not be handed to this
              // (or any) subsequent slide -- destroy it now, before deciding whether this
              // slide also needs a fresh instance.
              destroy();
              pendingFreshWebViewTeardown = false;
          }
          boolean isReuse = webView != null;
          if (policy != null && policy.freshWebView) {
              // Per-asset policy demands a brand-new WebView instance (e.g. interactive
              // kiosk content) rather than reusing whatever instance is already alive.
              destroy();
              isReuse = false;
          }
          if (isReuse) {
              // Reusing an existing WebView across slides: clear the outgoing page first so
              // its JS timers/intervals, in-flight media, and DOM state don't keep running
              // (or bleed) into the next slide's load. loadUrl("about:blank") synchronously
              // tears down the document; stopLoading() aborts any in-flight navigation.
              try { webView.stopLoading(); webView.loadUrl("about:blank"); } catch (Throwable ignored) {}
          }
          ensureWebView();
          if (policy != null) policy.applyTo(webView);
          hide();
          cancelTimers();
          pendingFreshWebViewTeardown = (policy != null && policy.freshWebView);

        long timeoutMs = (policy != null && policy.readyTimeoutMs > 0) ? policy.readyTimeoutMs : LOAD_TIMEOUT_MS;
        timeoutRunnable = () -> {
            if (!ready.get()) {
                Log.w(TAG, "[timeout] " + slideId + " url=" + urlForTimeoutLog);
                listener.onRendererFailed(slideId, "load_timeout");
            }
        };
        handler.postDelayed(timeoutRunnable, timeoutMs);

        try { trustedHost = android.net.Uri.parse(url).getHost(); } catch (Throwable ignored) { trustedHost = null; }
        currentRenderUrl = url;
        diagnosticLogCount = 0;
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

    /** Returns true (cancel navigation) if uri's host differs from trustedHost.
 *  A null/unparsable uri or a null trustedHost (nothing loaded yet) is allowed
 *  through rather than blocked, since it can't be a cross-origin escape. */
    private boolean blockUntrustedNavigation(android.net.Uri uri) {
        if (uri == null || trustedHost == null) return false;
        String host = uri.getHost();
        if (host != null && host.equalsIgnoreCase(trustedHost)) return false;
        Log.w(TAG, "[blocked_cross_origin_navigation] slide=" + currentSlideId + " from=" + trustedHost + " to=" + uri);
        return true;
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
          webView.addJavascriptInterface(new DigipalMediaBridge(), "DigipalMedia");
        webView.setWebViewClient(new WebViewClient() {
              // Blocks any top-level navigation away from the host we originally
              // loaded (task P5: harden WebView policies). Every slide type is
              // loaded via our own /tv/render/... route, which iframes third-party
              // content rather than top-navigating to it, so a request here for a
              // different host means either a JS-triggered top-navigation escape
              // attempt from embedded content or an unexpected redirect -- neither
              // of which should be allowed to carry the attached "Digipal" JS
              // interface to an untrusted origin.
              @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                  return blockUntrustedNavigation(req == null ? null : req.getUrl());
              }
              @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    return blockUntrustedNavigation(url == null ? null : android.net.Uri.parse(url));
                }
                // Unified Design Studio Renderer stabilization (Step 3): resolves the
                // virtual https://appassets.androidplatform.net/media/<objectPath>
                // URLs handed out by MediaDownloadManager.getLocalMediaWebUrl() (see the
                // window.DigipalMedia JS bridge below) back to locally-cached media
                // bytes, so a design/kiosk slide can render offline-cached media inside
                // this WebView without ever touching a raw file:// URL. Any request that
                // isn't a recognized/cached virtual media URL falls through to normal
                // network loading (returns null).
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                    try {
                        android.net.Uri uri = req != null ? req.getUrl() : null;
                        if (uri != null && "appassets.androidplatform.net".equals(uri.getHost())
                                && mediaDownloadManager != null) {
                            File cached = mediaDownloadManager.resolveLocalMediaFile(uri.getPath());
                            if (cached != null) {
                                String mime = MediaDownloadManager.guessMimeType(cached);
                                return new android.webkit.WebResourceResponse(mime, null,
                                        new java.io.FileInputStream(cached));
                            }
                        }
                    } catch (Throwable ignored) {}
                    return null;
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                    // P7: only ever fail the active slide for a main-frame error on the
                    // page we are actually showing -- ignore subresource errors (fonts,
                    // images, XHRs inside the page), about:blank (loaded synchronously
                    // by prepare()/destroy() when swapping slides), and any error whose
                    // URL/host no longer matches this generation's currentRenderUrl or
                    // trustedHost (a stray callback from an already-superseded request).
                    if (req == null) return;
                    android.net.Uri failingUri = req.getUrl();
                    String failingUrl = failingUri != null ? failingUri.toString() : null;
                    if (!req.isForMainFrame()) {
                        logDiagnostic("subresource_error_ignored", currentSlideId, currentRenderToken, failingUrl);
                        return;
                    }
                    if (failingUrl == null || "about:blank".equals(failingUrl)) {
                        return;
                    }
                    boolean matchesCurrentUrl = failingUrl.equals(currentRenderUrl);
                    boolean matchesTrustedHost = false;
                    if (!matchesCurrentUrl && trustedHost != null) {
                        String failingHost = failingUri != null ? failingUri.getHost() : null;
                        matchesTrustedHost = failingHost != null && trustedHost.equalsIgnoreCase(failingHost);
                    }
                    if (!matchesCurrentUrl && !matchesTrustedHost) {
                        logDiagnostic("stale_page_error_ignored", currentSlideId, currentRenderToken, failingUrl);
                        return;
                    }
                    listener.onRendererFailed(currentSlideId, "page_error");
                }
              // Without this override, Android's default behavior when a WebView's
              // renderer process dies (OOM kill, GPU crash, etc.) is to call
              // finish() on the whole Activity — killing the app instead of just
              // this slide. That produced a crash/reboot loop on low-RAM Fire TV
              // devices with no caught exception (so no crash report was ever
              // recorded). Handling it here destroys only the dead WebView and
              // routes back to the legacy renderer via onRendererFailed(),
              // matching how MainActivity's own long-lived WebView already
              // recovers from the same condition.
              @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
              @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                    boolean didCrash = detail != null && detail.didCrash();
                    Log.w(TAG, "[render_process_gone] slide=" + currentSlideId
                        + " didCrash=" + didCrash);
                    if (telemetry != null) {
                        try {
                            org.json.JSONObject details = new org.json.JSONObject();
                            details.put("renderToken", currentRenderToken);
                            details.put("didCrash", didCrash);
                            telemetry.logEvent("renderer_process_gone", currentSlideId, details.toString());
                        } catch (Throwable ignored) {}
                    }
                    if (v != webView) {
                        try { v.destroy(); } catch (Throwable ignored) {}
                        return true;
                    }
                    final String failedSlideId = currentSlideId;
                    try { destroy(); } catch (Throwable ignored) {}
                    handler.post(() -> listener.onRendererFailed(failedSlideId, "render_process_gone"));
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
              @JavascriptInterface public void ready(String slideId, long renderToken) {
                  handler.post(() -> {
                      if (!isCurrentGeneration(slideId, renderToken)) {
                          logDiagnostic("stale_ready_ignored", slideId, renderToken, null);
                          return;
                      }
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
              @JavascriptInterface public void error(String slideId, long renderToken, String code, String message) {
                  handler.post(() -> {
                      if (!isCurrentGeneration(slideId, renderToken)) {
                          logDiagnostic("stale_error_ignored", slideId, renderToken, code + ": " + message);
                          return;
                      }
                      listener.onRendererFailed(slideId, code + ": " + message);
                  });
              }
              @JavascriptInterface public void heartbeat(String slideId, long renderToken) {
                  handler.post(() -> {
                      if (!isCurrentGeneration(slideId, renderToken)) {
                          logDiagnostic("stale_heartbeat_ignored", slideId, renderToken, null);
                          return;
                      }
                      resetHeartbeatTimer();
                  });
              }
              @JavascriptInterface public void event(String slideId, long renderToken, String eventName, String payload) {
                  if (!isCurrentGeneration(slideId, renderToken)) {
                      logDiagnostic("stale_event_ignored", slideId, renderToken, eventName);
                      return;
                  }
                  Log.d(TAG, "[event] " + slideId + " " + eventName);
              }
              @JavascriptInterface public void requestNavigation(String slideId, long renderToken, String target) {
                  if (!isCurrentGeneration(slideId, renderToken)) {
                      logDiagnostic("stale_requestNavigation_ignored", slideId, renderToken, target);
                      return;
                  }
                  Log.d(TAG, "[requestNavigation] " + slideId + " -> " + target);
              }
              @JavascriptInterface public void requestExit(String slideId, long renderToken) {
                  handler.post(() -> {
                      if (!isCurrentGeneration(slideId, renderToken)) {
                          logDiagnostic("stale_requestExit_ignored", slideId, renderToken, null);
                          return;
                      }
                      listener.onRendererFailed(slideId, "exit_requested");
                  });
              }
          }
        /** Unified Design Studio Renderer stabilization (Step 3): minimal JS bridge for
         *  locally-cached media, mirroring the pattern used by RenderBridge/"Digipal"
         *  above. Exposed to the isolated-renderer page as window.DigipalMedia so
         *  Design Studio/kiosk content can ask "do you already have this media file
         *  cached locally?" and get back a same-origin-safe web URL to use directly in
         *  an <img>/<video> src, instead of always hitting the (possibly offline)
         *  remote signed URL. Both methods are null-safe no-ops when this renderer was
         *  constructed without a MediaDownloadManager. */
        private class DigipalMediaBridge {
            @JavascriptInterface
            public String getLocalMediaWebUrl(String objectPath) {
                if (mediaDownloadManager == null || objectPath == null) return "";
                try {
                    return mediaDownloadManager.getLocalMediaWebUrl(objectPath);
                } catch (Throwable ignored) {
                    return "";
                }
            }

            @JavascriptInterface
            public void downloadMedia(String objectPath, String signedUrl) {
                if (mediaDownloadManager == null || objectPath == null || signedUrl == null) return;
                try {
                    mediaDownloadManager.downloadMedia(objectPath, signedUrl);
                } catch (Throwable ignored) {}
            }
        }
  }
  