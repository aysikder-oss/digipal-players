package com.digipal.signage;

import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * WebViewPolicy — per-asset WebView configuration model (task: Per-Asset WebView
 * Policy). Extends the previous coarse two-bucket switch in
 * {@code MainActivity#applyWebViewProfile} (design/kiosk vs. plain URL) into a
 * tunable-per-slide policy. {@link #forSlideType} reproduces exactly what
 * {@code applyWebViewProfile} already did, so any slide with no explicit policy
 * (the overwhelming majority today) behaves identically to before this task.
 *
 * Fields not yet wired to a config source (cacheMode, cookies, desktop UA,
 * custom JS, offline flag, ready timeout) default to safe no-op values and are
 * consumed by {@link IsolatedWebRenderer} — server/UI exposure to set them
 * per-content is a follow-up once this plumbing lands.
 */
public class WebViewPolicy {

    /** Human-readable policy identifier reported in telemetry (renderer observability task). */
    public String name = "default";

    /** True = brand-new WebView instance per slide (never reused); false = reuse allowed. */
    public boolean freshWebView = false;

    /** One of WebSettings.LOAD_DEFAULT / LOAD_NO_CACHE / LOAD_CACHE_ELSE_NETWORK / LOAD_CACHE_ONLY. */
    public int cacheMode = WebSettings.LOAD_DEFAULT;

    /** Clear cookies for this WebView's origin when the slide finishes. */
    public boolean clearCookiesOnExit = false;

    /** Allow third-party cookies (e.g. embedded widgets/analytics inside a design). */
    public boolean allowThirdPartyCookies = true;

    /** Force a desktop User-Agent string (useful for external URL slides built for desktop). */
    public boolean desktopUserAgent = false;

    /** Optional periodic reload interval in seconds; 0 = never auto-refresh. */
    public int refreshIntervalSec = 0;

    /** Optional JS to inject via evaluateJavascript once the page has loaded. */
    public String customJs = null;

    /** Max retries for injecting customJs if the initial injection fails/no-ops. */
    public int customJsMaxRetries = 0;

    /** Whether this slide's content is expected to work fully offline (affects cache preference). */
    public boolean canOffline = false;

    /** Whether the rendered content expects touch input (kiosk interactive designs). */
    public boolean requiresTouch = false;

    /** Ready-gate timeout in ms before falling back — 0 means "use IsolatedWebRenderer's default". */
    public long readyTimeoutMs = 0;

    // ---- WebSettings fields carried over 1:1 from applyWebViewProfile ----
    public boolean allowFileAccess;
    public boolean allowFileAccessFromFileUrls;
    public boolean allowUniversalFileAccess;
    public boolean databaseEnabled;
    public int mixedContentMode;
    public boolean rendererPriorityImportant;

    /**
     * Safe default policy matching today's applyWebViewProfile(slideType) exactly.
     * Design/Kiosk = permissive (file access on, mixed content always allow).
     * Everything else (plain URL) = restrictive (file access off, compatibility mode).
     */
    public static WebViewPolicy forSlideType(PlaylistScheduler.SlideType slideType) {
          WebViewPolicy p = new WebViewPolicy();
          boolean isDesignKiosk = slideType == PlaylistScheduler.SlideType.WEBVIEW_DESIGN
                  || slideType == PlaylistScheduler.SlideType.WEBVIEW_KIOSK
                  // Directory/wayfinding kiosks are just as interactive and stateful
                  // (touch routing, multi-floor navigation, held DOM/JS state) as the
                  // Kiosk Designer output -- they need the same permissive+fresh policy.
                  || slideType == PlaylistScheduler.SlideType.WEBVIEW_DIRECTORY;
          // Canva embeds are third-party iframes: they need cookies/mixed-content allowed
          // like design/kiosk, but they are stateless single-purpose embeds (no local kiosk
          // session to protect), so a fresh WebView per slide is not required.
          boolean isCanva = slideType == PlaylistScheduler.SlideType.WEBVIEW_CANVA;
          // Plain websites are arbitrary third-party pages -- same network permissiveness
          // as Canva (mixed content, third-party cookies for embedded widgets/analytics)
          // but reused across slides like WEBVIEW_URL/WIDGET/PDF/TEXT/AUDIO.
          boolean isWebsite = slideType == PlaylistScheduler.SlideType.WEBVIEW_WEBSITE;
          if (isDesignKiosk) {
              p.allowFileAccess = true;
              p.allowFileAccessFromFileUrls = true;
              p.allowUniversalFileAccess = true;
              p.databaseEnabled = true;
              p.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW;
              p.rendererPriorityImportant = true;
              p.requiresTouch = slideType == PlaylistScheduler.SlideType.WEBVIEW_KIOSK
                      || slideType == PlaylistScheduler.SlideType.WEBVIEW_DIRECTORY;
              // Fresh WebView per slide for design/kiosk/directory: reusing a hidden
              // WebView across different Design Studio projects/kiosk instances left
              // stale JS globals, timers, and DOM state from the previous design alive
              // in the background, which could fire late Digipal.ready()/error() calls
              // for a slideId that is no longer current (see stale-callback guards in
              // PlaylistScheduler). Only content types below are safe to reuse.
              p.freshWebView = true;
              p.name = slideType == PlaylistScheduler.SlideType.WEBVIEW_KIOSK ? "kiosk_permissive_fresh"
                      : slideType == PlaylistScheduler.SlideType.WEBVIEW_DIRECTORY ? "directory_permissive_fresh"
                      : "design_permissive_fresh";
              // Dynamic design/kiosk/directory content can involve heavy DOM/JS work
              // (widgets, animations, multi-floor routing) that legitimately needs more
              // than the 3s native-media ready gate on slow/low-RAM Android devices.
              // Unified Design Studio Renderer stabilization (Step 7): weak Fire TV /
              // Android TV devices can take longer than 8s on first load for a
              // design/kiosk/directory slide (heavy DOM/JS, widgets, animations,
              // multi-floor routing) -- bumped to 12s so a legitimately-slow-but-working
              // first paint isn't misclassified as a load_timeout failure.
              p.readyTimeoutMs = 12_000L;
          } else if (isCanva || isWebsite) {
              p.allowFileAccess = false;
              p.allowFileAccessFromFileUrls = false;
              p.allowUniversalFileAccess = false;
              p.databaseEnabled = true;
              p.allowThirdPartyCookies = true;
              p.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW;
              p.rendererPriorityImportant = false;
              p.name = isCanva ? "canva_embed" : "website_embed";
              // Canva/website embeds are arbitrary third-party pages/iframes that can be
              // slow to first-paint (ad/analytics scripts, large third-party bundles).
              p.readyTimeoutMs = 10_000L;
          } else {
              // WEBVIEW_WIDGET / WEBVIEW_PDF / WEBVIEW_TEXT / WEBVIEW_AUDIO / WEBVIEW_URL:
              // internally-rendered content served from our own origin -- keep the
              // original restrictive, reusable policy unchanged.
              p.allowFileAccess = false;
              p.allowFileAccessFromFileUrls = false;
              p.allowUniversalFileAccess = false;
              p.databaseEnabled = false;
              p.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE;
              p.rendererPriorityImportant = false;
              p.name = "url_restrictive";
              // TEXT/AUDIO are near-instant internally-rendered pages with no external
              // network dependency; everything else in this bucket (WIDGET/PDF/URL) is
              // internally-rendered but may still fetch external widget data/PDF pages.
              p.readyTimeoutMs = (slideType == PlaylistScheduler.SlideType.WEBVIEW_TEXT
                      || slideType == PlaylistScheduler.SlideType.WEBVIEW_AUDIO)
                      ? 1_000L : 8_000L;
          }
          return p;
      }
  
    /** Applies this policy's WebSettings-backed fields to the given WebView. */
    public void applyTo(WebView webView) {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setAllowFileAccess(allowFileAccess);
        settings.setAllowFileAccessFromFileURLs(allowFileAccessFromFileUrls);
        settings.setAllowUniversalAccessFromFileURLs(allowUniversalFileAccess);
        settings.setDatabaseEnabled(databaseEnabled);
        settings.setMixedContentMode(mixedContentMode);
        settings.setCacheMode(cacheMode);
        if (desktopUserAgent) {
            settings.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                rendererPriorityImportant ? WebView.RENDERER_PRIORITY_IMPORTANT : WebView.RENDERER_PRIORITY_BOUND,
                true);
        }
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, allowThirdPartyCookies);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Returns "package@version" for the Android System WebView implementation currently
     * in use, or "unknown" if it cannot be determined. Exposed for diagnostics/telemetry.
     */
    public static String currentWebViewPackageInfo() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
                if (info != null) return info.packageName + "@" + info.versionName;
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }
}
