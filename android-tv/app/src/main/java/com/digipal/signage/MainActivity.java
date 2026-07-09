package com.digipal.signage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.net.wifi.WifiManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import android.view.PixelCopy;
import java.io.ByteArrayOutputStream;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
  import android.text.format.Formatter;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    /** Set true in onResume, false in onStop â read by WatchdogService.inForeground(). */
    public static volatile boolean activityVisible = false;
    /** Set true in onCreate, false in onDestroy â used by WatchdogService to detect real crashes vs Home-press. */
    public static volatile boolean activityAlive = false;

    private WebView webView;
    private long lastGcMs = 0;
    private FrameLayout rootLayout;
    private FrameLayout errorContainer;
    private MediaDownloadManager mediaDownloadManager;
    private static final String PREFS_NAME = "DigipalPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SERVER_MODE = "server_mode";
    private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";
    private static final String KEY_CHECK_SEC = "relaunch_check_sec";
    private static final long RELAUNCH_DEBOUNCE_MS = 10_000L;
    private long lastRelaunchScheduleElapsedMs = 0L;
    /** Screen pairing code, reported once by the JS player via Android.reportPairingCode()
     *  (task #1875) — used to build the isolated-renderer /tv/render/:pairingCode/:contentId URL. */
    private static final String KEY_PAIRING_CODE = "pairing_code";
    private volatile String cachedPairingCode = null;
    private IsolatedWebRenderer isolatedWebRenderer;
    /** Local versioned player shell cache (local player shell hardening task) — lets the app
     *  boot from a last-known-good shell snapshot when the configured server is unreachable. */
    private PlayerShellManager playerShellManager;
    private boolean bootedFromLocalShell = false;
    /** Mount-confirmation watchdog (fixes silent blank-screen boots from a broken local shell).
     *  onPageFinished only proves the top-level HTML document loaded without an HTTP error — it
     *  says nothing about whether the JS app actually mounted anything (e.g. the pairing screen).
     *  A local shell missing/corrupting a referenced asset used to still pass that check and get
     *  promoted to "last_good" forever. The JS side now calls Android.reportAppMounted() once
     *  real content renders; if that signal doesn't arrive within MOUNT_WATCHDOG_MS of
     *  onPageFinished, the boot is treated as failed. */
    private volatile boolean appMountConfirmed = false;
    private final android.os.Handler mountWatchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mountWatchdogRunnable;
    private static final long MOUNT_WATCHDOG_MS = 8000;
    /** "texture" or "surface" — defaults to "texture" on Fire TV, "surface" elsewhere */
    private static final String PREF_VIDEO_RENDERER = "pref_video_renderer";
    private boolean isUserClosing = false;
    private View customView;
    private FrameLayout customViewContainer;
    private boolean hasHttpError = false;
      private int dpadPressCount = 0;
      private long dpadFirstPressMs = 0L;
      private android.view.View diagnosticsOverlay = null;
      private final android.os.Handler diagDismissHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // Native media overlay views (ExoPlayer + Glide) â Android TV only
    private androidx.media3.ui.PlayerView nativeVideoView;
    // TextureView pair for Fire TV — used when pref_video_renderer="texture"
    private android.view.TextureView nativeTexViewA;
    private android.view.TextureView nativeTexViewB;
    private android.widget.ImageView nativeImageView;
    private androidx.media3.exoplayer.ExoPlayer exoPlayer;
      // ExoPlayer on-disk video cache (OptiSigns-style LRU, 2 GB max)
      private static androidx.media3.datasource.cache.SimpleCache videoCache;
      private static final long VIDEO_CACHE_SIZE = 50L * 1024 * 1024;  // 50 MB streaming buffer (full copies live in AssetCacheManager/Room — local playback bypasses this cache)
    // Handler/Runnable for first-frame video ready callback (or 8s safety timeout)
    private android.os.Handler videoReadyHandler;
    private Runnable videoReadyRunnable;
    // Stable playback timer: resets crash counter only after 3 min of uninterrupted playback
    private final android.os.Handler stablePlaybackHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable stablePlaybackRunnable;
    private static final long STABLE_PLAYBACK_MS = 3 * 60_000L;
    private androidx.media3.common.Player.Listener nativeVideoListener;
    // Preload â background ExoPlayer/Glide to buffer the next playlist item before it plays
    private androidx.media3.exoplayer.ExoPlayer preloadPlayer;
    private String preloadedVideoUrl;
    private boolean preloadVideoReady = false;
    private String preloadedImageUrl;
    private boolean preloadImageReady = false;
    // Dual-buffer B views â preloaded content renders here silently while A is visible
    private androidx.media3.ui.PlayerView nativeVideoViewB;
    private android.widget.ImageView nativeImageViewB;
    // P0 Fix 5: on-screen native debug overlay (rendererKind/webViewPolicy/etc.),
    // toggled from the JS DebugOverlay via setNativeDebugOverlayEnabled so field
    // techs can see ground-truth renderer state even while the WebView is dormant.
    private TextView nativeDebugOverlay;
    private volatile boolean nativeDebugOverlayEnabled = false;
    private final android.os.Handler debugOverlayHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable debugOverlayTickRunnable;
    private boolean activeVideoViewIsA = true;
    private boolean activeImageViewIsA = true;
    // Old player held alive during swap-wait so activeView stays visible; released after new frame confirmed
    private androidx.media3.exoplayer.ExoPlayer pendingOldPlayer;
    // Native-first rendering mode (OptiSigns-style OOM elimination on low-mem Fire TV)
    private boolean nativeFirstRendering = false;
    // Fix 3: generation token to cancel stale WebView recreations.
    private volatile int dormantGeneration = 0;
    /** Duration (ms) of the currently active native slide. Used by setWebViewDormant
     *  to skip WebView recreation for short slides (Fix 8). */
    private volatile long currentNativeSlideDurationMs = 0L;
    // Fix 4: ExoPlayer stall watchdog fields.
    private long stallLastPositionMs = -1L;
    private long stallLastCheckMs = 0L;
    private static final long STALL_THRESHOLD_MS = 30_000L;
    private static final long STALL_CHECK_MS = 5_000L;
    private final android.os.Handler stallHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable stallRunnable;
    // Fix 2: buffering watchdog — detects STATE_BUFFERING stall where bufferedPosition does not advance
    private final android.os.Handler bufWatchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable bufWatchdogRunnable;
    private String  bufWatchdogSlideId;
    private long    bufWatchdogLastBufferedMs = -1L;
    private int     bufWatchdogStallChecks   = 0;
    private static final long   BUF_WATCHDOG_INTERVAL_MS = 5_000L;   // check every 5s
    private static final int    BUF_WATCHDOG_STALL_TICKS = 3;        // 3 × 5s = 15s threshold
    // Native content loop — drives video/image slides via NativePlaylistManager without WebView
    private volatile String pendingNativePlaylistJson; // Codex fix 7: buffered playlist JSON until PlaylistScheduler is constructed
      // Native heartbeat for Fire TV: when WebView is paused (timers frozen), a Java
      // Handler fires evaluateJavascript every 25s to keep the WebSocket alive.
      // Without this, Amazon WebView's pauseTimers() suspends setInterval-based heartbeats
      // and the server disconnects the screen after its 300s WebSocket timeout.
      private final android.os.Handler heartbeatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
      private Runnable heartbeatRunnable;
    // Native-first playlist engine (v3.11.0) — PlaylistScheduler replaces JS timer
    private PlaylistRepository      playlistRepository;
    private TelemetryManager        telemetryManager;
    private PlaylistScheduler       playlistScheduler;
    private AssetCacheManager       assetCacheManager;
    /** url → absolute local file path for READY assets; populated from Room at boot + on every successful download. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> localPathCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private PdfPrerenderer          pdfPrerenderer;
    private ReliabilitySupervisor   reliabilitySupervisor;
    private HealthMonitor           healthMonitor;
      private MemoryBudgetManager     memoryBudgetManager;
      private RecoveryCoordinator     recoveryCoordinator;
    // Player URL tracked so WebView can be recreated with the same page between slides
    private String currentPlayerUrl = null;

    // Room-based offline playlist cache â survives boot, no blank screen on restart
    private CacheDatabase cacheDb;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityAlive = true;
          // WorkManager crash recovery: start the periodic backup worker.
          // Crash-counter reset (onCleanStart) is deferred to armStablePlaybackTimer() —
          // it fires only after 3 minutes of consecutive clean playback, preventing the
          // crash-loop counter from resetting on every cold start.
          AppRecoverManager.scheduleBackupWorker(this);
          // Install uncaught exception handler to record crash + schedule WorkManager recovery
          final Thread.UncaughtExceptionHandler _prevCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
          Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
              try { AppRecoverManager.scheduleRecovery(getApplicationContext()); } catch (Throwable ignored) {}
              if (_prevCrashHandler != null) _prevCrashHandler.uncaughtException(thread, throwable);
              else android.os.Process.killProcess(android.os.Process.myPid());
          });
        // Initialise Room offline cache (build() is non-blocking; first query opens file on bg thread)
        try {
            cacheDb = CacheDatabase.getInstance(this);
        } catch (Exception e) {
            android.util.Log.e("DigipalCache", "Room DB init failed — offline cache disabled: " + e.getMessage());
            cacheDb = null;
        }
          if (videoCache == null) {
              videoCache = new androidx.media3.datasource.cache.SimpleCache(
                  new java.io.File(getCacheDir(), "exo_video_cache"),
                  new androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(VIDEO_CACHE_SIZE)
              );
          }
          try {

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { getWindow().setSustainedPerformanceMode(true); } catch (Throwable ignored) {}
        }

        hideSystemUI();

        FrameLayout root = new FrameLayout(this);
        rootLayout = root;
        root.setBackgroundColor(Color.parseColor("#0a0e1a"));

        // Restore cached pairing code (task #1875) so the isolated renderer can build
        // /tv/render/:pairingCode/:contentId URLs before the JS player re-reports it.
        cachedPairingCode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_PAIRING_CODE, null);

        if (playerShellManager == null) playerShellManager = new PlayerShellManager(this);

        try {
              webView = new WebView(this);
          } catch (Throwable e) {
              android.widget.TextView errTv = new android.widget.TextView(this);
              errTv.setText("WebView unavailable.\n\nPlease update Android System WebView from the Play Store, then relaunch Digipal.");
              errTv.setTextColor(android.graphics.Color.WHITE);
              errTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
              errTv.setPadding(80, 80, 80, 80);
              errTv.setGravity(android.view.Gravity.CENTER);
              root.addView(errTv);
              customViewContainer = new FrameLayout(this);
        customViewContainer.setBackgroundColor(Color.BLACK);
        customViewContainer.setVisibility(View.GONE);
        root.addView(customViewContainer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
              return;
          }
          setupWebView();
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        errorContainer = new FrameLayout(this);
        errorContainer.setBackgroundColor(Color.parseColor("#0a0e1a"));
        errorContainer.setVisibility(View.GONE);
        root.addView(errorContainer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Native video overlay (ExoPlayer PlayerView) â sits above WebView and errorContainer
        nativeVideoView = new androidx.media3.ui.PlayerView(this);
        nativeVideoView.setUseController(false);
        nativeVideoView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        nativeVideoView.setKeepContentOnPlayerReset(true);
        try { nativeVideoView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT); } catch (Throwable ignored) {}
        nativeVideoView.setVisibility(View.INVISIBLE);
        root.addView(nativeVideoView, new FrameLayout.LayoutParams(1, 1));
        // Dual-buffer B video view â preloaded content renders here while A is visible
        nativeVideoViewB = new androidx.media3.ui.PlayerView(this);
        nativeVideoViewB.setUseController(false);
        nativeVideoViewB.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        nativeVideoViewB.setKeepContentOnPlayerReset(true);
        try { nativeVideoViewB.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT); } catch (Throwable ignored) {}
        nativeVideoViewB.setVisibility(View.INVISIBLE);
        root.addView(nativeVideoViewB, new FrameLayout.LayoutParams(1, 1));
        // TextureView overlays for Fire TV — used when pref_video_renderer="texture"
        nativeTexViewA = new android.view.TextureView(this);
        nativeTexViewA.setVisibility(View.INVISIBLE); nativeTexViewA.setAlpha(0f);
        root.addView(nativeTexViewA, new FrameLayout.LayoutParams(1, 1));
        nativeTexViewB = new android.view.TextureView(this);
        nativeTexViewB.setVisibility(View.INVISIBLE); nativeTexViewB.setAlpha(0f);
        root.addView(nativeTexViewB, new FrameLayout.LayoutParams(1, 1));
        // Native image overlay (Glide ImageView) â sits above nativeVideoView
        nativeImageView = new RecyclingSafeImageView(this);
        nativeImageView.setVisibility(View.INVISIBLE);
        root.addView(nativeImageView, new FrameLayout.LayoutParams(1, 1));
        // Dual-buffer B image view â preloaded image loads here while A is visible
        nativeImageViewB = new RecyclingSafeImageView(this);
        nativeImageViewB.setVisibility(View.INVISIBLE);
        root.addView(nativeImageViewB, new FrameLayout.LayoutParams(1, 1));

        // P0 Fix 5: native debug overlay TextView, added above every other renderer
        // (including any isolated per-slide WebView created later, task #1875) so the
        // debug panel stays visible even when an isolated WebView briefly sits above
        // the main WebView in z-order. Never focusable/clickable so it cannot steal
        // touch/remote input from the actual renderers underneath it.
        nativeDebugOverlay = new TextView(this);
        nativeDebugOverlay.setTextColor(Color.parseColor("#00FF88"));
        nativeDebugOverlay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        nativeDebugOverlay.setBackgroundColor(Color.parseColor("#B0000000"));
        nativeDebugOverlay.setPadding(16, 12, 16, 12);
        nativeDebugOverlay.setTypeface(android.graphics.Typeface.MONOSPACE);
        nativeDebugOverlay.setFocusable(false);
        nativeDebugOverlay.setFocusableInTouchMode(false);
        nativeDebugOverlay.setClickable(false);
        nativeDebugOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams debugOverlayParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        debugOverlayParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        debugOverlayParams.topMargin = 24;
        debugOverlayParams.leftMargin = 24;
        root.addView(nativeDebugOverlay, debugOverlayParams);

        setContentView(root);


        mediaDownloadManager = new MediaDownloadManager(this);
        mediaDownloadManager.setWebView(webView);
        mediaDownloadManager.cleanupOrphans();

        
          // Start crash watchdog â relaunches app within 10s if it crashes or is killed.
          if (isAutoRelaunchEnabled()) {
              Intent watchdogIntent = new Intent(this, WatchdogService.class);
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  startForegroundService(watchdogIntent);
              } else {
                  startService(watchdogIntent);
              }
          }
  
        String serverUrl = getServerUrl();
        String localBootUrl = null;
        try {
            if (playerShellManager != null) localBootUrl = playerShellManager.getBootUrl(serverUrl);
        } catch (Throwable ignored) {}
        // Captured immediately after getBootUrl() so the opportunistic refresh below can tell
        // whether the shell it just booted from is stale relative to what's on the server right
        // now, instead of only refreshing for whichever restart happens to come next (shell
        // staleness self-heal task — a 24/7 kiosk device may never restart on its own).
        final String bootedShellHash = playerShellManager != null ? playerShellManager.getLastBootContentHash() : "";
        final boolean bootedLocally = localBootUrl != null;
        if (localBootUrl != null) {
            bootedFromLocalShell = true;
            currentPlayerUrl = localBootUrl;
            webView.loadUrl(localBootUrl);
        } else {
            bootedFromLocalShell = false;
            loadPlayerUrl(serverUrl);
        }
        // Renderer observability: report which shell (local cached vs. remote network
        // load) actually served this boot, so field telemetry can distinguish the two.
        try {
            String source = playerShellManager != null ? playerShellManager.getLastBootSource() : "remote";
            if (telemetryManager != null) {
                telemetryManager.setShellSource(source);
                telemetryManager.setShellBuild(
                        playerShellManager != null ? playerShellManager.getLastBootVersion() : "",
                        bootedShellHash);
            }
            if (playlistScheduler != null) playlistScheduler.setShellSource(source);
        } catch (Throwable ignored) {}
        // Opportunistically refresh the local shell cache in the background so the next boot
        // (or a rollback) has an up-to-date, health-checked snapshot (local player shell hardening
        // task). Shell staleness self-heal task: if this session booted from a local snapshot and
        // the freshly downloaded shell's content hash differs from it, the device is running
        // stale JS *right now* — force an immediate reload of the just-cached fresh shell instead
        // of waiting for a restart that a 24/7 kiosk device may never get.
        try {
            if (playerShellManager != null) {
                playerShellManager.downloadShellAsync(serverUrl, new PlayerShellManager.DownloadCallback() {
                    @Override public void onSuccess(String version, String contentHash) {
                        try {
                            if (telemetryManager != null) telemetryManager.setShellBuild(version, contentHash);
                            boolean stale = bootedLocally
                                    && !bootedShellHash.isEmpty()
                                    && contentHash != null && !contentHash.isEmpty()
                                    && !contentHash.equals(bootedShellHash);
                            if (!stale) return;
                            Log.w("Digipal", "[shell] detected stale local shell (booted=" + bootedShellHash
                                    + " current=" + contentHash + ") — self-healing with a fresh reload");
                            if (telemetryManager != null) {
                                telemetryManager.logEvent("shell_self_heal", null,
                                        "{\"reason\":\"stale_shell\",\"bootedHash\":\"" + bootedShellHash
                                                + "\",\"freshHash\":\"" + contentHash + "\"}");
                            }
                            runOnUiThread(() -> {
                                try {
                                    String freshUrl = playerShellManager.getBootUrl(serverUrl);
                                    if (freshUrl == null) freshUrl = serverUrl; // shouldn't happen; fall back to network
                                    bootedFromLocalShell = playerShellManager.getLastBootSource().equals("local");
                                    currentPlayerUrl = freshUrl;
                                    appMountConfirmed = false;
                                    if (bootedFromLocalShell) {
                                        webView.loadUrl(freshUrl);
                                    } else {
                                        loadPlayerUrl(freshUrl);
                                    }
                                } catch (Throwable ignored2) {}
                            });
                        } catch (Throwable ignored3) {}
                    }
                    @Override public void onFailure(String reason) { /* keep running on whatever booted this session */ }
                });
            }
        } catch (Throwable ignored) {}

        startAnrWatchdog();
        initNativeComponents();
          } catch (Throwable _onCreate_err) {
              try {
                  android.widget.LinearLayout errRoot = new android.widget.LinearLayout(this);
                  errRoot.setOrientation(android.widget.LinearLayout.VERTICAL);
                  errRoot.setGravity(android.view.Gravity.CENTER);
                  errRoot.setBackgroundColor(android.graphics.Color.parseColor("#0a0e1a"));
                  android.widget.TextView errMsg = new android.widget.TextView(this);
                  errMsg.setText("Startup error (" + "TV" + "):\n"
                      + _onCreate_err.getClass().getSimpleName() + ": " + _onCreate_err.getMessage()
                      + "\n\nPlease contact support or reinstall.");
                  errMsg.setTextColor(android.graphics.Color.WHITE);
                  errMsg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
                  errMsg.setPadding(80, 80, 80, 80);
                  errMsg.setGravity(android.view.Gravity.CENTER);
                  errRoot.addView(errMsg);
                  setContentView(errRoot);
              } catch (Throwable ignored) {}
          }
      }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        // P6 hardening: the main shell WebView only ever loads the trusted
          // cloud (https://digipalsignage.com) or a private-network local-hub
          // origin validated by ServerSetupActivity.isPrivateNetworkUrl() -- it
          // never loads file:// content, so it should run least-privileged.
          // Isolated per-slide content (designs/kiosks/urls) is rendered by
          // IsolatedWebRenderer under its own WebViewPolicy and is unaffected
          // by these settings.
          settings.setAllowFileAccess(false);
          settings.setAllowFileAccessFromFileURLs(false);
          settings.setAllowUniversalAccessFromFileURLs(false);
          settings.setDatabaseEnabled(true);
          settings.setTextZoom(100);
          settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
          // COMPATIBILITY_MODE still allows the local-hub HTTP origin (already
          // gated to private IPs) to load its own same-origin mixed resources,
          // while blocking active mixed content from being injected into the
          // trusted cloud/local shell page itself.
          settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              settings.setSafeBrowsingEnabled(true);
          }

        webView.setBackgroundColor(Color.parseColor("#0a0e1a"));
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

          // Keep WebView renderer alive and auto-restart it under memory pressure (API 26+, OptiSigns technique)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
          }

          webView.addJavascriptInterface(new WebAppInterface(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Serve virtual https://appassets.androidplatform.net/media/<objectPath>
                // URLs (handed out by getLocalMediaWebUrl below) from the local media
                // cache, so the main WebView can play cached media while offline
                // without ever using a raw file:// URL.
                try {
                    android.net.Uri mediaUri = request != null ? request.getUrl() : null;
                    if (mediaUri != null && "appassets.androidplatform.net".equals(mediaUri.getHost())
                            && mediaDownloadManager != null) {
                        java.io.File cached = mediaDownloadManager.resolveLocalMediaFile(mediaUri.getPath());
                        if (cached != null) {
                            String mime = MediaDownloadManager.guessMimeType(cached);
                            return new android.webkit.WebResourceResponse(mime, null,
                                    new java.io.FileInputStream(cached));
                        }
                    }
                } catch (Throwable ignored) {}
                if (playerShellManager != null
                        && "appassets.androidplatform.net".equals(request.getUrl().getHost())) {
                    android.webkit.WebResourceResponse r =
                            playerShellManager.buildAssetLoader().shouldInterceptRequest(request.getUrl());
                    if (r != null) return r;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                hasHttpError = false;
                appMountConfirmed = false;
                if (mountWatchdogRunnable != null) mountWatchdogHandler.removeCallbacks(mountWatchdogRunnable);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!hasHttpError) {
                    // The HTML document loaded without an HTTP/network error, but that alone does
                    // NOT prove the JS app actually mounted anything — a local shell missing/
                    // corrupting a referenced asset renders a blank page with no HTTP error at
                    // all (WebViewAssetLoader 404s a sub-resource silently; onReceivedError only
                    // fires for the main frame). Wait for the JS side to positively confirm real
                    // content rendered (Android.reportAppMounted()) before promoting this shell to
                    // last_good or clearing the error overlay. If confirmation never arrives,
                    // onMountWatchdogTimeout() below treats it as a failed boot.
                    if (mountWatchdogRunnable != null) mountWatchdogHandler.removeCallbacks(mountWatchdogRunnable);
                    mountWatchdogRunnable = () -> onMountWatchdogTimeout();
                    mountWatchdogHandler.postDelayed(mountWatchdogRunnable, MOUNT_WATCHDOG_MS);
                    errorContainer.setVisibility(View.GONE);
                    webView.setVisibility(View.VISIBLE);
                    // Suppress webkit media controls and the native video
                    // loading overlay shown during playlist transitions.
                    webView.evaluateJavascript(
                        "(function(){" +
                        "var s=document.createElement('style');" +
                        "s.textContent=" +
                        "'video::-webkit-media-controls{display:none!important}" +
                        "video::-webkit-media-controls-enclosure{display:none!important}" +
                        "video::-webkit-media-controls-panel{display:none!important}';" +
                        "document.head&&document.head.appendChild(s);" +
                        "})();",
                        null);
                // Inject offline playlist cache for immediate use by React app (window.__digipalOfflineCache)
                    if (cacheDb != null) {
                        final android.webkit.WebView _wv = webView;
                        new Thread(() -> {
                            try {
                                java.util.List<CacheDatabase.CacheObject> all = cacheDb.cacheDao().findAll();
                                if (all == null || all.isEmpty()) return;
                                StringBuilder js = new StringBuilder(
                                    "if(!window.__digipalOfflineCache)window.__digipalOfflineCache={};");
                                for (CacheDatabase.CacheObject item : all) {
                                    if (item == null || item.key == null || item.json == null) continue;
                                    try {
                                        new org.json.JSONTokener(item.json).nextValue();
                                    } catch (Throwable invalid) {
                                        continue;
                                    }
                                    js.append("window.__digipalOfflineCache[")
                                      .append(org.json.JSONObject.quote(item.key))
                                      .append("]=")
                                      .append(item.json)
                                      .append(";");
                                }
                                final String script = js.toString();
                                runOnUiThread(() -> { if (_wv != null) _wv.evaluateJavascript(script, null); });
                            } catch (Throwable e) {
                                android.util.Log.w("Digipal", "Offline cache inject: " + e.getMessage());
                            }
                        }, "DigipalOfflineCacheInject").start();
                    }
                    // Inject APK crash stats and any pending crash report for web-player pickup
                  try {
                      android.content.SharedPreferences _cp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                      int _cc = _cp.getInt(CrashCounter.KEY_CRASH_COUNT, 0);
                      boolean _mx = _cp.getBoolean(CrashCounter.KEY_MAX_EXCEEDED, false);
                      boolean _hasCrash = _cp.getBoolean("pending_crash_ready", false);
                      StringBuilder _js = new StringBuilder();
                      _js.append("window.__digipalCrashStats={crashCount:").append(_cc)
                         .append(",isMaxExceeded:").append(_mx).append("};");
                      if (_hasCrash) {
                          try {
                              org.json.JSONObject _crash = new org.json.JSONObject();
                              _crash.put("occurredAt", _cp.getLong("pending_crash_at", 0L));
                              _crash.put("errorType", _cp.getString("pending_crash_type", "UnknownError"));
                              _crash.put("freeMemoryMb", _cp.getInt("pending_crash_free_mb", 0));
                              _crash.put("totalMemoryMb", _cp.getInt("pending_crash_total_mb", 0));
                              _crash.put("stackTrace", _cp.getString("pending_crash_stack", ""));
                              _js.append("window.__digipalNativeCrash=").append(_crash.toString()).append(";");
                          } catch (org.json.JSONException _je) {
                              _js.append("window.__digipalNativeCrash=null;");
                          }
                      } else {
                          _js.append("window.__digipalNativeCrash=null;");
                      }
                      final String _finalJs = _js.toString();
                      final android.webkit.WebView _wv2 = webView;
                      runOnUiThread(() -> { if (_wv2 != null) _wv2.evaluateJavascript(_finalJs, null); });
                  } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    hasHttpError = true;
                    if (maybeRollbackLocalShell()) return;
                    showError("Connection Lost", "Unable to reach the server. Retrying...");
                    retryConnection();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    int statusCode = errorResponse.getStatusCode();
                    if (statusCode >= 500) {
                        hasHttpError = true;
                        if (maybeRollbackLocalShell()) return;
                        showError("Connecting...", "Server is starting up. Retrying...");
                        retryConnection();
                    }
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
                if (isAllowedMainWebViewUrl(url)) return false;
                Log.w("DigipalSecurity", "Blocked main WebView navigation to " + url);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isAllowedMainWebViewUrl(url)) return false;
                Log.w("DigipalSecurity", "Blocked main WebView navigation to " + url);
                return true;
            }

            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                android.util.Log.w("Digipal", "WebView render process gone; didCrash="
                    + (detail != null && detail.didCrash()));
                if (view != webView) {
                    try { view.destroy(); } catch (Throwable ignored) {}
                    return true;
                }
                recoverFromRenderProcessGone(view);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public View getVideoLoadingProgressView() {
                // Suppress the default grey overlay + play-button that Android
                // WebView renders while a <video> element is buffering or
                // switching sources between playlist items.
                FrameLayout empty = new FrameLayout(MainActivity.this);
                empty.setVisibility(View.GONE);
                return empty;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // Defensive null-guards (Sentry: NPE on addView) — a fullscreen video
                // request can theoretically reach here via a stale WebChromeClient
                // callback before customViewContainer/webView are (re)initialized,
                // e.g. across an Activity recreation race. Bail out safely instead
                // of crashing the whole app.
                if (view == null || customViewContainer == null || webView == null) {
                    try { callback.onCustomViewHidden(); } catch (Throwable ignored) {}
                    return;
                }
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewContainer.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ));
                customViewContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                if (customViewContainer != null) {
                    customViewContainer.removeView(customView);
                    customViewContainer.setVisibility(View.GONE);
                }
                customView = null;
                if (webView != null) webView.setVisibility(View.VISIBLE);
                hideSystemUI();
            }
        });
    }

    private class WebAppInterface {

        @JavascriptInterface
        public void setAutoRelaunch(boolean enabled) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_AUTO_RELAUNCH, enabled).apply();
            // Restart WatchdogService so the new setting takes effect immediately
            Intent ws = new Intent(MainActivity.this, WatchdogService.class);
            stopService(ws);
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(ws);
                else startService(ws);
            }
        }


        @JavascriptInterface
        public void setRelaunchCheckSec(int seconds) {
            int clamped = Math.max(5, Math.min(120, seconds));
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putInt(KEY_CHECK_SEC, clamped).apply();
            // Restart WatchdogService to pick up the new interval immediately.
            if (prefs.getBoolean(KEY_AUTO_RELAUNCH, false)) {
                Intent ws = new Intent(MainActivity.this, WatchdogService.class);
                stopService(ws);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(ws);
                else startService(ws);
            }
        }
        @JavascriptInterface
        public void scheduleRelaunch() {
            scheduleAppRelaunch(2000);
        }

        /** Called by the web app once it has actually rendered real content (pairing screen,
         *  player, error screen — anything past a blank shell). Cancels the mount watchdog and,
         *  the first time it fires after a page load, promotes the current shell to last_good —
         *  replacing the old blind "onPageFinished with no HTTP error = good" promotion, which
         *  could not tell a real render apart from a silently blank one (fixes stuck blank
         *  pairing/player screens on a broken locally cached shell). */
        @JavascriptInterface
        public void reportAppMounted() {
            appMountConfirmed = true;
            if (mountWatchdogRunnable != null) mountWatchdogHandler.removeCallbacks(mountWatchdogRunnable);
            if (playerShellManager != null) {
                try { playerShellManager.markCurrentAsGood(); } catch (Throwable ignored) {}
            }
        }

        @JavascriptInterface
        public void heartbeat() {
            lastHeartbeatMs = System.currentTimeMillis();
            heartbeatReceived = true;
            if (healthMonitor != null) healthMonitor.onHeartbeatReceived();
        }

        /**
         * Reports the real native APK version (versionName) so the JS player's
         * detectAppVersion() no longer silently falls back to "web" — the WS
         * identify payload's appVersion field then shows the true build number
         * (e.g. "3.16.27") on the admin/customer dashboard instead of "web".
         */
        @JavascriptInterface
        public String getAppVersion() {
            return BuildConfig.VERSION_NAME;
        }

        /**
         * Reported once by the JS player when its pairing code is known (task #1875).
         * Cached in-memory + SharedPreferences so the isolated per-slide renderer can
         * build /tv/render/:pairingCode/:contentId URLs without depending on the
         * (potentially unhealthy) main WebView at slide-dispatch time.
         */
        @JavascriptInterface
        public void reportPairingCode(String code) {
            if (code == null || code.isEmpty()) return;
            if (code.equals(cachedPairingCode)) return;
            cachedPairingCode = code;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_PAIRING_CODE, code).apply();
            android.util.Log.d("DigipalNative", "[pairingCode] cached " + code);
        
            if (telemetryManager != null) telemetryManager.setPairingCode(code);
        }

        @JavascriptInterface
        public void downloadMedia(String objectPath, String signedUrl) {
            if (mediaDownloadManager != null) {
                mediaDownloadManager.downloadMedia(objectPath, signedUrl);
            }
        }

        @JavascriptInterface
        public String getLocalMediaPath(String objectPath) {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.getLocalMediaPath(objectPath);
            }
            return "";
        }

        /** Returns a WebView-only virtual https://appassets.androidplatform.net/media/
         *  URL for a locally cached objectPath (empty string when not cached). Unlike
         *  getLocalMediaPath()'s file:// URLs, these work from an https:// origin page.
         *  WebView-only: ExoPlayer/Glide native overlays cannot fetch these URLs. */
        @JavascriptInterface
        public String getLocalMediaWebUrl(String objectPath) {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.getLocalMediaWebUrl(objectPath);
            }
            return "";
        }

        /**
         * Debug-panel status: reports which renderer is actually active (native video/
         * image, isolated WebView, or legacy shared WebView), current memory tier,
         * WebView policy, shell source, and whether the isolated-renderer feature flag
         * is even turned on for this build. Consumed by the JS DebugOverlay so the
         * on-screen debug panel can show ground truth instead of guessing from appVersion.
         */
        // P0 Fix 5: toggled by the JS DebugOverlay when the user opens/closes the
        // on-screen diagnostics panel; drives visibility of the native TextView
        // overlay that mirrors getRendererStatus() so debug info survives WebView
        // dormancy / isolated-WebView renderer swaps.
        @JavascriptInterface
        public void setNativeDebugOverlayEnabled(boolean enabled) {
            nativeDebugOverlayEnabled = enabled;
            runOnUiThread(() -> {
                if (debugOverlayTickRunnable != null) {
                    debugOverlayHandler.removeCallbacks(debugOverlayTickRunnable);
                }
                if (nativeDebugOverlay == null) return;
                if (!enabled) {
                    nativeDebugOverlay.setVisibility(View.GONE);
                    return;
                }
                nativeDebugOverlay.bringToFront();
                nativeDebugOverlay.setVisibility(View.VISIBLE);
                debugOverlayTickRunnable = () -> {
                    updateNativeDebugOverlayText();
                    if (nativeDebugOverlayEnabled) {
                        debugOverlayHandler.postDelayed(debugOverlayTickRunnable, 2000L);
                    }
                };
                debugOverlayHandler.post(debugOverlayTickRunnable);
            });
        }

        @JavascriptInterface
        public String getRendererStatus() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("appVersion", BuildConfig.VERSION_NAME);
                o.put("versionCode", BuildConfig.VERSION_CODE);
                if (playlistScheduler != null) {
                      o.put("rendererKind", playlistScheduler.getCurrentRendererKind());
                      o.put("memoryTier", playlistScheduler.getMemoryTierName());
                      o.put("webViewPolicy", playlistScheduler.getLastWebViewPolicyName());
                      o.put("shellSource", playlistScheduler.getShellSourceName());
                      o.put("fallbackUsed", playlistScheduler.isLastFallbackUsed());
                      o.put("slideId", playlistScheduler.getCurrentSlideId());
                      o.put("contentId", playlistScheduler.getCurrentContentId());
                      o.put("renderMode", playlistScheduler.getCurrentRenderMode());
                      o.put("lastReadyAtMs", playlistScheduler.getLastReadyAtMs());
                      o.put("lastErrorAtMs", playlistScheduler.getLastErrorAtMs());
                      o.put("lastErrorMessage", playlistScheduler.getLastErrorMessage());
                      o.put("retryCount", playlistScheduler.getRetryCountForSlide());
                      o.put("consecutiveFailures", playlistScheduler.getConsecutiveFailures());
                  } else {
                      o.put("rendererKind", "unknown");
                      o.put("memoryTier", "unknown");
                      o.put("webViewPolicy", "");
                      o.put("shellSource", "unknown");
                      o.put("fallbackUsed", false);
                      o.put("slideId", "");
                      o.put("contentId", 0);
                      o.put("renderMode", "");
                      o.put("lastReadyAtMs", 0);
                      o.put("lastErrorAtMs", 0);
                      o.put("lastErrorMessage", "");
                      o.put("retryCount", 0);
                      o.put("consecutiveFailures", 0);
                  }
                  // lastHeartbeatMs is a MainActivity-level field (set from the JS heartbeat()
                  // bridge call), not scheduler-scoped -- always available once the WebView has
                  // called heartbeat() at least once (debug/telemetry task).
                  o.put("lastHeartbeatAtMs", lastHeartbeatMs);
                  o.put("isolatedRendererFeatureEnabled", PlaylistScheduler.isIsolatedRendererFeatureEnabled());
                  // Shell staleness telemetry task: the actual shell build this device is
                  // currently executing, so field reports can be diagnosed remotely.
                  o.put("shellVersion", playerShellManager != null ? playerShellManager.getLastBootVersion() : "");
                  o.put("shellContentHash", playerShellManager != null ? playerShellManager.getLastBootContentHash() : "");
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public boolean deleteMedia(String objectPath) {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.deleteMedia(objectPath);
            }
            return false;
        }

        @JavascriptInterface
        public int deleteAllMedia() {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.deleteAllMedia();
            }
            return 0;
        }

        @JavascriptInterface
        public String getStorageInfo() {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.getStorageInfo();
            }
            return "{\"usedBytes\":0,\"freeBytes\":0,\"totalSpace\":0,\"totalFiles\":0}";
        }


          @JavascriptInterface
          public String getDeviceInfo() {
              try {
                  org.json.JSONObject o = new org.json.JSONObject();
                  o.put("model", android.os.Build.MODEL);
                  o.put("manufacturer", android.os.Build.MANUFACTURER);
                  o.put("androidVersion", android.os.Build.VERSION.RELEASE);
                  o.put("appVersion", BuildConfig.VERSION_NAME);
                  o.put("deviceId", getOrCreateInstallId());
                  return o.toString();
              } catch (Throwable e) {
                  return "{}";
              }
          }
  
        /**
         * Device resource snapshot for the web player to throttle itself before a
         * crash. Method name must stay exactly "getResourceStats" â the player's
         * native bridge depends on it.
         */
        @JavascriptInterface
        public String getResourceStats() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                if (am != null) {
                    am.getMemoryInfo(mi);
                    o.put("availMemBytes", mi.availMem);
                    o.put("totalMemBytes", mi.totalMem);
                    o.put("lowMemory", mi.lowMemory);
                    o.put("memThresholdBytes", mi.threshold);
                    // These two fields are consumed by parseResourceStats() in playerBridge.ts
                    // to compute nativeMemRatio. Previously missing — nativeMemRatio was always 0.
                    o.put("memUsedBytes", mi.totalMem - mi.availMem);
                    o.put("memTotalBytes", mi.totalMem);
                }
                Runtime rt = Runtime.getRuntime();
                o.put("appHeapUsedBytes", rt.totalMemory() - rt.freeMemory());
                o.put("appHeapMaxBytes", rt.maxMemory());
                java.io.File dir = getFilesDir();
                o.put("freeStorageBytes", dir.getUsableSpace());
                o.put("totalStorageBytes", dir.getTotalSpace());
                o.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
                o.put("sdkInt", Build.VERSION.SDK_INT);
                return o.toString();
            } catch (Throwable e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void openServerSettings() {
            runOnUiThread(() -> openSetupScreen());
        }

        @JavascriptInterface
        public String getServerMode() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            return prefs.getString(KEY_SERVER_MODE, "cloud");
        }

        @JavascriptInterface
        public String getConnectedServerUrl() {
            return getServerUrl();
        }


          @JavascriptInterface
          public String scanLocalServers() {
              android.net.nsd.NsdManager nsdManager =
                  (android.net.nsd.NsdManager) getSystemService(android.content.Context.NSD_SERVICE);
              if (nsdManager == null) return "[]";

              final java.util.List<String> found =
                  java.util.Collections.synchronizedList(new java.util.ArrayList<>());
              final java.util.concurrent.atomic.AtomicInteger pending =
                  new java.util.concurrent.atomic.AtomicInteger(0);

              final java.util.concurrent.CountDownLatch stopLatch =
                  new java.util.concurrent.CountDownLatch(1);
              final java.util.concurrent.CountDownLatch resolveLatch =
                  new java.util.concurrent.CountDownLatch(1);

              android.net.nsd.NsdManager.DiscoveryListener discoveryListener =
                  new android.net.nsd.NsdManager.DiscoveryListener() {
                      @Override public void onStartDiscoveryFailed(String t, int e) { stopLatch.countDown(); }
                      @Override public void onStopDiscoveryFailed(String t, int e) {}
                      @Override public void onDiscoveryStarted(String t) {}
                      @Override public void onDiscoveryStopped(String t) { stopLatch.countDown(); }
                      @Override public void onServiceLost(android.net.nsd.NsdServiceInfo si) {}
                      @Override
                      public void onServiceFound(android.net.nsd.NsdServiceInfo serviceInfo) {
                          pending.incrementAndGet();
                          nsdManager.resolveService(serviceInfo,
                              new android.net.nsd.NsdManager.ResolveListener() {
                                  @Override public void onResolveFailed(android.net.nsd.NsdServiceInfo si, int err) {
                                      if (pending.decrementAndGet() == 0) resolveLatch.countDown();
                                  }
                                  @Override
                                  public void onServiceResolved(android.net.nsd.NsdServiceInfo si) {
                                      try {
                                          java.net.InetAddress host = si.getHost();
                                          if (host != null) {
                                              found.add("http://" + host.getHostAddress() + ":" + si.getPort());
                                          }
                                      } catch (Throwable ignored) {
                                      } finally {
                                          if (pending.decrementAndGet() == 0) resolveLatch.countDown();
                                      }
                                  }
                              });
                      }
                  };

              try {
                  nsdManager.discoverServices("_digipal._tcp.",
                      android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener);
                  new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                      try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Throwable ignored) {}
                  }, 3000);
                  stopLatch.await(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
                  if (pending.get() > 0) {
                      resolveLatch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                  }
              } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
              } catch (Throwable ignored) {}

              org.json.JSONArray arr = new org.json.JSONArray();
              for (String url : found) arr.put(url);
              return arr.toString();
          }

          @JavascriptInterface
        public void notifyPaired(String url) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (!prefs.getBoolean("cloud_pairing_pending", false)) return;

            String serverUrl = (url != null && !url.trim().isEmpty()) ? url.trim() : BuildConfig.SERVER_URL;
            if (!UrlPolicy.isAllowedServerUrl(serverUrl)) {
                Log.w("DigipalSecurity", "Rejected paired server URL: " + serverUrl);
                return;
            }

            prefs.edit()
                    .putString(KEY_SERVER_MODE, "cloud")
                    .putString(KEY_SERVER_URL, serverUrl)
                    .putBoolean("cloud_pairing_pending", false)
                    .apply();
        }

          @JavascriptInterface
        public void setSentryPlayerId(String playerId) {
            try {
                if (playerId != null && !playerId.isEmpty()) {
                    io.sentry.Sentry.configureScope(scope -> scope.setTag("player_id", playerId));
                }
            } catch (Exception ignored) {}
        }

        /**
         * Remote unstick mechanism (shell staleness task): drop the cached local shell
         * pointer and force an immediate absolute network reload of the current player URL.
         * Unlike the existing "reload"/"force_update" WS commands (which do a relative
         * window.location reload/replace and would just re-serve the SAME stale local shell
         * if the device is stuck on one), this goes through Android's boot decision fresh —
         * invalidateCurrent() means the next getBootUrl() call returns null, so the reload
         * below falls straight through to the network. Lets ops unstick an already-affected
         * device without a physical visit; exposed to the web layer as the refresh_shell WS
         * command (useTvCommands.ts).
         */
        @JavascriptInterface
        public void invalidateLocalShellAndReload() {
            try {
                if (playerShellManager != null) {
                    try { playerShellManager.invalidateCurrent(); } catch (Throwable ignored) {}
                }
                bootedFromLocalShell = false;
                final String serverUrl = getServerUrl();
                runOnUiThread(() -> {
                    try {
                        appMountConfirmed = false;
                        loadPlayerUrl(serverUrl);
                    } catch (Throwable ignored) {}
                });
                if (telemetryManager != null) {
                    telemetryManager.logEvent("shell_self_heal", null, "{\"reason\":\"remote_unstick\"}");
                }
            } catch (Throwable ignored) {}
        }

          @JavascriptInterface
          public void captureScreenshot(String requestId) {
              final String quotedId = org.json.JSONObject.quote(requestId == null ? "" : requestId);
              if (webView == null) return;
              if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                  // PixelCopy requires API 26+; signal null so the web side falls back to html2canvas
                  runOnUiThread(() -> {
                      if (webView != null) {
                          webView.evaluateJavascript(
                              "if(window.__digipalNativeScreenshot)window.__digipalNativeScreenshot("
                                  + quotedId + ",null)",
                              null);
                      }
                  });
                  return;
              }
              int width = Math.max(1, webView.getWidth());
              int height = Math.max(1, webView.getHeight());
              Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
              PixelCopy.request(
                      getWindow(),
                      new Rect(0, 0, width, height),
                      bmp,
                      copyResult -> {
                          String b64 = null;
                          try {
                              if (copyResult == PixelCopy.SUCCESS) {
                                  ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                  bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                                  b64 = "data:image/jpeg;base64,"
                                          + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                              }
                              final String payload = b64;
                              final String quotedPayload = payload == null ? "null" : org.json.JSONObject.quote(payload);
                              runOnUiThread(() -> {
                                  if (webView != null) {
                                      webView.evaluateJavascript(
                                              "if(window.__digipalNativeScreenshot)window.__digipalNativeScreenshot("
                                                      + quotedId + "," + quotedPayload + ")",
                                              null);
                                  }
                              });
                          } finally {
                              try { bmp.recycle(); } catch (Throwable ignored) {}
                          }
                      },
                      new Handler(Looper.getMainLooper()));
          }


          /**
           * Called by the React player after each successful server fetch.
           * Upserts the JSON into Room so it's available immediately on the next boot.
           */
          @android.webkit.JavascriptInterface
          public void putCache(String key, String json) {
              if (cacheDb == null || key == null || json == null) return;
              try {
                  CacheDatabase.CacheObject obj = new CacheDatabase.CacheObject();
                  obj.key = key;
                  obj.json = json;
                  obj.updatedAt = System.currentTimeMillis();
                  cacheDb.cacheDao().upsert(obj);
              } catch (Throwable e) {
                  android.util.Log.w("Digipal", "Cache write failed: " + e.getMessage());
              }
          }

          /**
           * Synchronously returns cached JSON by key (runs on JS bridge thread â not main thread, safe).
           * Returns null if no entry exists.
           */
          @android.webkit.JavascriptInterface
          public String getCache(String key) {
              if (cacheDb == null || key == null) return null;
              try {
                  CacheDatabase.CacheObject obj = cacheDb.cacheDao().findByKey(key);
                  return obj != null ? obj.json : null;
              } catch (Throwable e) {
                  return null;
              }
          }

          @android.webkit.JavascriptInterface
              public void playNativeVideo(String url, float x, float y, float w, float h, String objectFit, boolean loop, float volume) {
                  playNativeVideo(url, x, y, w, h, objectFit, loop, volume, "");
              }

              @android.webkit.JavascriptInterface
              public void playNativeVideo(String url, float x, float y, float w, float h, String objectFit, boolean loop, float volume, String contentId) {
                runOnUiThread(() -> {
                    try {
                        boolean fromPreload = url.equals(preloadedVideoUrl) && preloadPlayer != null;
                        // Cancel any pending ready timers/listeners from the previous item
                        if (videoReadyHandler != null && videoReadyRunnable != null) {
                            videoReadyHandler.removeCallbacks(videoReadyRunnable);
                            videoReadyHandler = null; videoReadyRunnable = null;
                        }
                        if (nativeVideoListener != null) {
                            if (exoPlayer != null) exoPlayer.removeListener(nativeVideoListener);
                            nativeVideoListener = null;
                        }
                        float d = getResources().getDisplayMetrics().density;
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int)(w * d), (int)(h * d));
                        lp.leftMargin = (int)(x * d);
                        lp.topMargin  = (int)(y * d);
                        int resizeMode = "cover".equals(objectFit) ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM :
                                         "fill".equals(objectFit)  ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL :
                                         androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
                        // Active view = currently visible; preloadView = invisible, holds the buffered content
                        final androidx.media3.ui.PlayerView activeView  = activeVideoViewIsA ? nativeVideoView : nativeVideoViewB;
                        final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
                        // TextureView refs (Fire TV path)
                        final boolean useTexture = useTextureViewRenderer();
                        final android.view.TextureView activeTexView   = activeVideoViewIsA ? nativeTexViewA : nativeTexViewB;
                        final android.view.TextureView incomingTexView = activeVideoViewIsA ? nativeTexViewB : nativeTexViewA;
                        android.util.Log.d("DigipalVideo", "[schedPlay] url=" + (url.length() > 60 ? url.substring(0, 60) : url) + " useTexture=" + useTexture + " fromPreload=" + fromPreload + " pvState=" + (exoPlayer != null ? exoPlayer.getPlaybackState() : -1));
                        // Discard any pending-old player that was never cleaned up (e.g. back-to-back plays)
                          if (pendingOldPlayer != null) { try { pendingOldPlayer.stop(); pendingOldPlayer.release(); } catch (Throwable ignored) {} pendingOldPlayer = null; }
                          // DIAG: timing + device state for every video transition
                          final long diagT0 = android.os.SystemClock.elapsedRealtime();
                          final String diagPath = fromPreload ? (preloadVideoReady ? "preload-ready" : "preload-waiting") : "cold-load";
                          { android.app.ActivityManager.MemoryInfo diagMi = new android.app.ActivityManager.MemoryInfo(); ((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(diagMi); android.util.Log.i("DigipalMetrics", "[playNativeVideo] path=" + diagPath + " memAvailMB=" + (diagMi.availMem/1048576L) + " lowMem=" + diagMi.lowMemory + " url=…" + (url.length()>50 ? url.substring(url.length()-50) : url)); }
                          if (fromPreload) {
                              // ââ Dual-buffer instant swap ââââââââââââââââââââââââââââââââââââââââââââââââââ
                              // Capture old player â keep it running on activeView until new frame confirmed visible
                              final androidx.media3.exoplayer.ExoPlayer oldPlayer = exoPlayer;
                              pendingOldPlayer = oldPlayer;
                              // Promote preloadPlayer â exoPlayer
                              exoPlayer = preloadPlayer;
                              preloadPlayer = null; preloadedVideoUrl = null;
                              // Apply playback settings and ensure it's playing
                              exoPlayer.setRepeatMode(loop ? androidx.media3.common.Player.REPEAT_MODE_ONE : androidx.media3.common.Player.REPEAT_MODE_OFF);
                              exoPlayer.setVolume(volume);
                              exoPlayer.play();
                              // Move preloadView to the actual display position/size
                              preloadView.setResizeMode(resizeMode);
                              preloadView.setLayoutParams(lp);
                // Make VISIBLE with alpha=0 so SurfaceView/TextureView renders immediately (Fire TV fix)
                if (useTexture) {
                    incomingTexView.setLayoutParams(lp);
                    incomingTexView.setVisibility(View.VISIBLE); incomingTexView.setAlpha(0f);
                } else {
                    preloadView.setVisibility(View.VISIBLE); preloadView.setAlpha(0f);
                }
                              if (preloadVideoReady) {
                                  // First frame already rendered on preloadView surface â instant, zero-black swap
                                  // Alpha swap — incoming becomes visible, outgoing fades out (fixes Fire TV blank SurfaceView)
                                  if (useTexture) {
                                      incomingTexView.setAlpha(1f); incomingTexView.setVisibility(View.VISIBLE);
                                      activeTexView.setAlpha(0f);   activeTexView.setVisibility(View.INVISIBLE);
                                  } else {
                                      preloadView.setAlpha(1f); preloadView.setVisibility(View.VISIBLE);
                                      activeView.setAlpha(0f);  activeView.setVisibility(View.INVISIBLE);
                                  }
                                  hideNativeImagesForVideo();
                                  android.util.Log.d("RendererOwner", "owner=video hideImages=true useTexture=" + useTextureViewRenderer());
                                  activeVideoViewIsA = !activeVideoViewIsA;
                                  preloadVideoReady = false;
                                  // Release old player now that new content is confirmed visible
                                  pendingOldPlayer = null;
                                  if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                  notifyNativeVideoReady(contentId);
                              } else {
                                  // Preload still buffering â old content stays visible on activeView until first frame
                                  final boolean[] done = {false};
                                  nativeVideoListener = new androidx.media3.common.Player.Listener() {
                                      @Override public void onRenderedFirstFrame() {
                                          if (exoPlayer != null) exoPlayer.removeListener(this);
                                          nativeVideoListener = null;
                                          if (done[0]) return; done[0] = true;
                                          runOnUiThread(() -> {
                                              if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                              // Alpha swap — incoming becomes visible, outgoing fades out (fixes Fire TV blank SurfaceView)
                                              if (useTexture) {
                                                  incomingTexView.setAlpha(1f); incomingTexView.setVisibility(View.VISIBLE);
                                                  activeTexView.setAlpha(0f);   activeTexView.setVisibility(View.INVISIBLE);
                                              } else {
                                                  preloadView.setAlpha(1f); preloadView.setVisibility(View.VISIBLE);
                                                  activeView.setAlpha(0f);  activeView.setVisibility(View.INVISIBLE);
                                              }
                                              hideNativeImagesForVideo();
                                              android.util.Log.d("RendererOwner", "owner=video hideImages=true useTexture=" + useTextureViewRenderer());
                                              activeVideoViewIsA = !activeVideoViewIsA;
                                              preloadVideoReady = false;
                                              pendingOldPlayer = null;
                                              if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                              notifyNativeVideoReady(contentId);
                                          });
                                      }
                                      @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                                            // DIAG: log full error before any early-return
                                            android.util.Log.w("DigipalMetrics", "[preload onPlayerError] " + error.getClass().getSimpleName() + ": " + error.getMessage() + (error.getCause() != null ? " cause=" + error.getCause().getClass().getSimpleName() : ""));
                                            // FIX: silence check FIRST — keep listener + 2.5s fallback alive so ExoPlayer's
                                            // internal retry can reach onRenderedFirstFrame and swap the view.
                                            if (error.getCause() instanceof androidx.media3.exoplayer.ExoTimeoutException) {
                                                android.util.Log.w("DigipalNative", "ExoPlayer startup timeout silenced — keeping listener for retry");
                                                return;
                                            }
                                          if (exoPlayer != null) exoPlayer.removeListener(this);
                                          nativeVideoListener = null;
                                          if (done[0]) return; done[0] = true;
                                          if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                          pendingOldPlayer = null;
                                          if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                          android.util.Log.w("DigipalMetrics", "[preload onPlayerError fatal] advancing playlist");
                                          notifyNativeVideoReady(contentId);
                                      }
                                  };
                                  exoPlayer.addListener(nativeVideoListener);
                                  final android.os.Handler readyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                  final Runnable readyCb = new Runnable() {
                                      @Override public void run() {
                                          videoReadyHandler = null; videoReadyRunnable = null; nativeVideoListener = null;
                                          if (done[0]) return; done[0] = true;
                                          // Alpha swap — incoming becomes visible, outgoing fades out (fixes Fire TV blank SurfaceView)
                                          if (useTexture) {
                                              incomingTexView.setAlpha(1f); incomingTexView.setVisibility(View.VISIBLE);
                                              activeTexView.setAlpha(0f);   activeTexView.setVisibility(View.INVISIBLE);
                                          } else {
                                              preloadView.setAlpha(1f); preloadView.setVisibility(View.VISIBLE);
                                              activeView.setAlpha(0f);  activeView.setVisibility(View.INVISIBLE);
                                          }
                                          hideNativeImagesForVideo();
                                          android.util.Log.d("RendererOwner", "owner=video hideImages=true useTexture=" + useTextureViewRenderer());
                                          activeVideoViewIsA = !activeVideoViewIsA;
                                          preloadVideoReady = false;
                                          pendingOldPlayer = null;
                                          if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                          notifyNativeVideoReady(contentId);
                                      }
                                  };
                                  videoReadyHandler = readyHandler; videoReadyRunnable = readyCb;
                                  readyHandler.postDelayed(readyCb, 2500);
                              }
                          } else {
                              // ââ Cold load: use inactive (preload) view so old content stays visible âââââââââ
                              if (preloadPlayer != null) {
                                  try { preloadPlayer.release(); } catch (Throwable ignored) {}
                                  preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false;
                                  preloadView.setPlayer(null);
                              }
                              // Build new player on preloadView â old exoPlayer keeps playing on activeView
                              final androidx.media3.exoplayer.ExoPlayer coldPlayer = buildCachedExoPlayer();
                              if (useTexture) {
                                  // Fire TV TextureView path: skip SurfaceView, render into TextureView directly
                                  preloadView.setPlayer(null);
                                  coldPlayer.setVideoTextureView(incomingTexView);
                                  incomingTexView.setLayoutParams(lp);
                                  incomingTexView.setVisibility(View.VISIBLE); incomingTexView.setAlpha(0f);
                                  android.util.Log.d("DigipalVideo", "[cold-load] TextureView path started, alpha=0");
                              } else {
                                  preloadView.setPlayer(coldPlayer);
                                  preloadView.setResizeMode(resizeMode); preloadView.setLayoutParams(lp);
                                  // VISIBLE+alpha=0 so SurfaceView renders immediately (fixes Fire TV blank video)
                                  preloadView.setVisibility(View.VISIBLE); preloadView.setAlpha(0f);
                                  android.util.Log.d("DigipalVideo", "[cold-load] SurfaceView path started, alpha=0");
                              }
                              preloadView.setLayoutParams(lp);
                              coldPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(url)));
                              // Only the PlaylistScheduler-driven path (playNativeVideoForScheduler) owns
                              // loop restart via its own natural-end listener. This generic bridge method
                              // is also used directly for standalone single-content screens (no playlist,
                              // no scheduler running) via NativeTvVideoOverlay — for those, REPEAT_MODE_OFF
                              // left the video frozen/black after one play since nothing else restarts it.
                              // Respect the caller's `loop` flag here, matching the fromPreload branch above.
                              coldPlayer.setRepeatMode(loop ? androidx.media3.common.Player.REPEAT_MODE_ONE : androidx.media3.common.Player.REPEAT_MODE_OFF);
                              coldPlayer.setVolume(volume);
                              coldPlayer.prepare();
                              coldPlayer.play();
                              final androidx.media3.exoplayer.ExoPlayer oldPlayer = exoPlayer;
                              exoPlayer = coldPlayer;
                              pendingOldPlayer = oldPlayer;
                              final android.os.Handler readyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                              final Runnable readyCb = new Runnable() {
                                  @Override public void run() {
                                      videoReadyHandler = null; videoReadyRunnable = null; nativeVideoListener = null;
                                      long diagFbMs = android.os.SystemClock.elapsedRealtime() - diagT0;
                                      android.util.Log.w("DigipalMetrics", "[8s fallback fired] cold-load — no first frame; keeping last visible, latencyMs=" + diagFbMs);
                                      // FIX: do NOT swap views — keep old content visible, avoid brown GPU surface
                                      final androidx.media3.exoplayer.ExoPlayer failedCold = exoPlayer;
                                      exoPlayer = oldPlayer;
                                      pendingOldPlayer = null;
                                      if (failedCold != null) { try { failedCold.stop(); failedCold.release(); } catch (Throwable ignored) {} }
                                      preloadView.setPlayer(null);
                                      notifyNativeVideoReady(contentId);
                                      webView.evaluateJavascript("if(window.__digipalNativeMetrics)window.__digipalNativeMetrics({type:'videoFallback',path:'cold-load',latencyMs:" + diagFbMs + "})", null);
                                  }
                              };
                              videoReadyHandler = readyHandler; videoReadyRunnable = readyCb;
                              readyHandler.postDelayed(readyCb, 8000);
                              nativeVideoListener = new androidx.media3.common.Player.Listener() {
                                  @Override public void onRenderedFirstFrame() {
                                      if (exoPlayer != null) exoPlayer.removeListener(this);
                                      nativeVideoListener = null;
                                      if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                      final long diagLatencyMs = android.os.SystemClock.elapsedRealtime() - diagT0;
                                      android.util.Log.i("DigipalMetrics", "[onRenderedFirstFrame] path=" + diagPath + " latencyMs=" + diagLatencyMs);
                                      runOnUiThread(() -> {
                                          // Alpha swap — incoming becomes visible, outgoing fades out (fixes Fire TV blank SurfaceView)
                                          if (useTexture) {
                                              incomingTexView.setAlpha(1f); incomingTexView.setVisibility(View.VISIBLE);
                                              activeTexView.setAlpha(0f);   activeTexView.setVisibility(View.INVISIBLE);
                                          } else {
                                              preloadView.setAlpha(1f); preloadView.setVisibility(View.VISIBLE);
                                              activeView.setAlpha(0f);  activeView.setVisibility(View.INVISIBLE);
                                          }
                                          hideNativeImagesForVideo();
                                          android.util.Log.d("RendererOwner", "owner=video hideImages=true useTexture=" + useTextureViewRenderer());
                                          activeVideoViewIsA = !activeVideoViewIsA;
                                          pendingOldPlayer = null;
                                          if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} }
                                          notifyNativeVideoReady(contentId);
                                          String safeDiagPath = org.json.JSONObject.quote(diagPath == null ? "" : diagPath);
                                          webView.evaluateJavascript(
                                                  "if(window.__digipalNativeMetrics)window.__digipalNativeMetrics({type:'videoReady',path:"
                                                          + safeDiagPath + ",latencyMs:" + diagLatencyMs + "})",
                                                  null);
                                      });
                                  }
                                  @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                                        // DIAG: log full error before any early-return
                                        android.util.Log.w("DigipalMetrics", "[cold onPlayerError] " + error.getClass().getSimpleName() + ": " + error.getMessage() + (error.getCause() != null ? " cause=" + error.getCause().getClass().getSimpleName() : ""));
                                        // FIX: silence check FIRST — keep listener + 8s fallback alive so ExoPlayer's
                                        // internal retry can reach onRenderedFirstFrame and swap the view.
                                        if (error.getCause() instanceof androidx.media3.exoplayer.ExoTimeoutException) {
                                            android.util.Log.w("DigipalNative", "ExoPlayer startup timeout silenced — keeping listener + 8s fallback for retry");
                                            return;
                                        }
                                      if (exoPlayer != null) exoPlayer.removeListener(this);
                                      nativeVideoListener = null;
                                      if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                      // FIX: do NOT swap views — keep old content visible, avoid brown GPU surface
                                      final androidx.media3.exoplayer.ExoPlayer failedErr = exoPlayer;
                                      exoPlayer = oldPlayer;
                                      pendingOldPlayer = null;
                                      if (failedErr != null) { try { failedErr.stop(); failedErr.release(); } catch (Throwable ignored) {} }
                                      preloadView.setPlayer(null);
                                      android.util.Log.w("DigipalMetrics", "[cold onPlayerError fatal] advancing playlist");
                                      notifyNativeVideoReady(contentId);
                                  }
                              };
                              exoPlayer.addListener(nativeVideoListener);
                          }
                      } catch (Exception e) { android.util.Log.e("DigipalNative", "playNativeVideo error", e); }
                  });
              }

          @android.webkit.JavascriptInterface
            public void stopNativeVideo() {
                runOnUiThread(() -> {
                    try {
                        if (videoReadyHandler != null && videoReadyRunnable != null) {
                            videoReadyHandler.removeCallbacks(videoReadyRunnable);
                            videoReadyHandler = null; videoReadyRunnable = null;
                        }
                        if (nativeVideoListener != null) {
                            if (exoPlayer != null) exoPlayer.removeListener(nativeVideoListener);
                            nativeVideoListener = null;
                        }
                        releaseVideoPlayer(exoPlayer); exoPlayer = null;
                        MainActivity.this.stopStallWatchdog(); // Fix 4
                        if (pendingOldPlayer != null) { releaseVideoPlayer(pendingOldPlayer); pendingOldPlayer = null; }
                        hideNativeVideoSurfaces();
                    } catch (Exception e) {}
                });
            }

          @android.webkit.JavascriptInterface
          public void pauseNativeVideo() {
              runOnUiThread(() -> { try { if (exoPlayer != null) exoPlayer.pause(); } catch (Exception e) {} });
          }

          @android.webkit.JavascriptInterface
          public void resumeNativeVideo() {
              runOnUiThread(() -> { try { if (exoPlayer != null) exoPlayer.play(); } catch (Exception e) {} });
          }

          @android.webkit.JavascriptInterface
          public void seekNativeVideo(long positionMs) {
              runOnUiThread(() -> { try { if (exoPlayer != null) exoPlayer.seekTo(positionMs); } catch (Exception e) {} });
          }

          @android.webkit.JavascriptInterface
            public void setNativeVideoRect(float x, float y, float w, float h) {
                runOnUiThread(() -> {
                    try {
                        float d = getResources().getDisplayMetrics().density;
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int)(w * d), (int)(h * d));
                        lp.leftMargin = (int)(x * d); lp.topMargin = (int)(y * d);
                        (activeVideoViewIsA ? nativeVideoView : nativeVideoViewB).setLayoutParams(lp);
                    } catch (Exception e) {}
                });
              }

          @android.webkit.JavascriptInterface
          public long getNativeVideoPosition() {
              try { return exoPlayer != null ? exoPlayer.getCurrentPosition() : 0L; } catch (Exception e) { return 0L; }
          }

          @android.webkit.JavascriptInterface
            public void showNativeImage(String url, float x, float y, float w, float h, String scaleType, String contentIdStr) {
                runOnUiThread(() -> {
                    try {
                        // Fix 2: safe-quote contentIdStr so JS key lookup is always valid.
                        String safeContentId = org.json.JSONObject.quote(contentIdStr == null ? "" : contentIdStr);
                        float d = getResources().getDisplayMetrics().density;
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int)(w * d), (int)(h * d));
                        lp.leftMargin = (int)(x * d); lp.topMargin = (int)(y * d);
                        android.widget.ImageView.ScaleType st =
                            "cover".equals(scaleType) ? android.widget.ImageView.ScaleType.CENTER_CROP :
                            "fill".equals(scaleType)  ? android.widget.ImageView.ScaleType.FIT_XY :
                            android.widget.ImageView.ScaleType.FIT_CENTER;
                        final android.widget.ImageView activeImgView  = activeImageViewIsA ? nativeImageView : nativeImageViewB;
                        final android.widget.ImageView preloadImgView = activeImageViewIsA ? nativeImageViewB : nativeImageView;
                        if (url.equals(preloadedImageUrl) && preloadImageReady) {
                            // Instant swap â image already decoded into preloadImgView
                            preloadImgView.setScaleType(st);
                            preloadImgView.setLayoutParams(lp);
                            preloadImgView.setVisibility(View.VISIBLE);
                            activeImgView.setVisibility(View.INVISIBLE);
                            // Trim Glide bitmap cache before cold load (Fire TV OOM fix).
                            try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE); } catch (Throwable ignored) {}
                            com.bumptech.glide.Glide.with(MainActivity.this).clear(activeImgView);
                            activeImageViewIsA = !activeImageViewIsA;
                            preloadedImageUrl = null; preloadImageReady = false;
                            // Fix 1: content-scoped ready callback on preloaded image swap (APK v3.16.14+).
                            webView.evaluateJavascript("try{var _f=window['__digipalNativeImageReady_'+" + safeContentId + "];if(typeof _f==='function')_f();else if(typeof window.__digipalNativeImageReady==='function')window.__digipalNativeImageReady();}catch(e){}", null);
                        } else {
                              // Cold fallback: load into inactive (preload) view — old content stays visible until first draw confirmed
                              com.bumptech.glide.Glide.with(MainActivity.this).clear(preloadImgView);
                              preloadedImageUrl = null; preloadImageReady = false;
                              final android.widget.ImageView incoming = preloadImgView;
                              final android.widget.ImageView outgoing = activeImgView;
                              incoming.setLayoutParams(lp);
                              incoming.setScaleType(st);
                              incoming.setVisibility(View.INVISIBLE);
                              // Trim Glide bitmap cache before cold load (Fire TV OOM fix).
                              try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE); } catch (Throwable ignored) {}
                              com.bumptech.glide.Glide.with(MainActivity.this)
                                  .load(url)
                                  .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                      @Override
                                      public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                              Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                              boolean isFirstResource) {
                                          incoming.setVisibility(View.INVISIBLE);
                                          // Fix 1: call error callback on Glide failure — do NOT signal ready.
                                          webView.evaluateJavascript("try{var _ef=window['__digipalNativeImageError_'+" + safeContentId + "];if(typeof _ef==='function')_ef('glide_load_failed');}catch(e){}", null);
                                          return false;
                                      }
                                      @Override
                                      public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                              com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                              com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                          // Wait for first draw confirmation — prevents brown/golden square on rapid transitions
                                          android.view.ViewTreeObserver vto = incoming.getViewTreeObserver();
                                          vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                                              @Override
                                              public boolean onPreDraw() {
                                                  android.view.ViewTreeObserver live = incoming.getViewTreeObserver();
                                                  if (live.isAlive()) live.removeOnPreDrawListener(this);
                                                  incoming.setVisibility(View.VISIBLE);
                                                  incoming.setAlpha(1f);
                                                  outgoing.setVisibility(View.INVISIBLE);
                                                  activeImageViewIsA = !activeImageViewIsA;
                                                  try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE); } catch (Throwable ignored) {}
                                                  com.bumptech.glide.Glide.with(MainActivity.this).clear(outgoing);
                                                   webView.evaluateJavascript("try{var _f=window['__digipalNativeImageReady_'+" + safeContentId + "];if(typeof _f==='function')_f();else if(typeof window.__digipalNativeImageReady==='function')window.__digipalNativeImageReady();}catch(e){}", null);
                                                  return true;
                                              }
                                          });
                                          return false;
                                      }
                                  })
                                  .into(incoming);
                          }
                      } catch (Exception e) { android.util.Log.e("DigipalNative", "showNativeImage error", e); }
                  });
              }

          @android.webkit.JavascriptInterface


            public void hideNativeImage() {
                runOnUiThread(() -> {
                    try {
                        com.bumptech.glide.Glide.with(MainActivity.this).clear(nativeImageView);
                        com.bumptech.glide.Glide.with(MainActivity.this).clear(nativeImageViewB);
                        nativeImageView.setVisibility(View.INVISIBLE);
                        nativeImageViewB.setVisibility(View.INVISIBLE);
                        preloadedImageUrl = null; preloadImageReady = false;
                    } catch (Exception e) {}
                });
            }

          @android.webkit.JavascriptInterface
            public void setNativeImageRect(float x, float y, float w, float h) {
                runOnUiThread(() -> {
                    try {
                        float d = getResources().getDisplayMetrics().density;
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int)(w * d), (int)(h * d));
                        lp.leftMargin = (int)(x * d); lp.topMargin = (int)(y * d);
                        (activeImageViewIsA ? nativeImageView : nativeImageViewB).setLayoutParams(lp);
                    } catch (Exception e) {}
                });
              }

            @android.webkit.JavascriptInterface
              public void preloadNativeVideo(String url) {
                  runOnUiThread(() -> {
                      try {
                          if (url.equals(preloadedVideoUrl) && preloadPlayer != null) return;
                          if (preloadPlayer != null) {
                              try { preloadPlayer.release(); } catch (Throwable ignored) {}
                              preloadPlayer = null;
                          }
                          // Detach any old preload from the inactive view
                          final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
                          preloadView.setPlayer(null);
                          preloadedVideoUrl = null; preloadVideoReady = false;
                          android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                          android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                          am.getMemoryInfo(mi);
                          if (mi.lowMemory) return;
                          // Size the inactive view to full screen so ExoPlayer has a real surface to decode into
                          preloadView.setLayoutParams(new FrameLayout.LayoutParams(
                              FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                               preloadPlayer = buildCachedExoPlayer();
                               if (useTextureViewRenderer()) {
                                   final android.view.TextureView preloadTex = activeVideoViewIsA ? nativeTexViewB : nativeTexViewA;
                                   preloadView.setPlayer(null);
                                   preloadPlayer.setVideoTextureView(preloadTex);
                                   preloadTex.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                                   preloadTex.setVisibility(View.VISIBLE); preloadTex.setAlpha(0f);
                               } else {
                                   preloadView.setPlayer(preloadPlayer);
                                   preloadView.setVisibility(View.VISIBLE); preloadView.setAlpha(0f);
                               }
                          preloadPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(url)));
                          preloadPlayer.setVolume(0f);       // silent â not visible yet
                          preloadPlayer.setPlayWhenReady(false);
                          preloadPlayer.prepare();
                          preloadedVideoUrl = url;
                          // Mark ready when first frame renders into the B surface
                          final androidx.media3.exoplayer.ExoPlayer capturedPreload = preloadPlayer;
                          preloadPlayer.addListener(new androidx.media3.common.Player.Listener() {
                              @Override public void onRenderedFirstFrame() {
                                  if (capturedPreload == preloadPlayer) preloadVideoReady = true;
                                  capturedPreload.removeListener(this);
                              }
                          });
                      } catch (Exception e) { android.util.Log.w("DigipalNative", "preloadNativeVideo error: " + e.getMessage()); }
                  });
              }

            @android.webkit.JavascriptInterface
              public void preloadNativeImage(String url) {
                  runOnUiThread(() -> {
                      try {
                          if (url.equals(preloadedImageUrl) && preloadImageReady) return;
                          // Low-memory gate: skip image preload under memory pressure (Fire TV OOM fix).
                          android.app.ActivityManager _am2 = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                          if (_am2 != null) { android.app.ActivityManager.MemoryInfo _mi2 = new android.app.ActivityManager.MemoryInfo(); _am2.getMemoryInfo(_mi2); if (_mi2.lowMemory) return; }
                          // Clear any previous preload from the inactive view
                          final android.widget.ImageView preloadImgView = activeImageViewIsA ? nativeImageViewB : nativeImageView;
                          com.bumptech.glide.Glide.with(MainActivity.this).clear(preloadImgView);
                          preloadedImageUrl = null; preloadImageReady = false;
                          preloadImgView.setVisibility(View.INVISIBLE);
                          // Load into the inactive view with a full-screen layout so Glide decodes at display size
                          preloadImgView.setLayoutParams(new FrameLayout.LayoutParams(
                              FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                          preloadedImageUrl = url;
                          com.bumptech.glide.Glide.with(MainActivity.this)
                              .load(url)
                              .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                  @Override
                                  public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                          Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                          boolean isFirstResource) {
                                      if (url.equals(preloadedImageUrl)) { preloadImageReady = false; }
                                      return false;
                                  }
                                  @Override
                                  public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                          com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                          com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                      if (url.equals(preloadedImageUrl)) { preloadImageReady = true; }
                                      return false;
                                  }
                              })
                              .into(preloadImgView);
                      } catch (Exception e) { android.util.Log.w("DigipalNative", "preloadNativeImage error: " + e.getMessage()); }
                  });
              }

            @android.webkit.JavascriptInterface
              public void cancelNativePreload() {
                  runOnUiThread(() -> {
                      try {
                          if (preloadPlayer != null) { try { preloadPlayer.release(); } catch (Throwable ignored) {} preloadPlayer = null; }
                          preloadedVideoUrl = null; preloadVideoReady = false;
                          // Detach preload player from the inactive video view
                          final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
                          try { preloadView.setPlayer(null); } catch (Throwable ignored) {}
                          // Clear both image views
                          try { com.bumptech.glide.Glide.with(MainActivity.this).clear(nativeImageView); } catch (Throwable ignored) {}
                          try { com.bumptech.glide.Glide.with(MainActivity.this).clear(nativeImageViewB); } catch (Throwable ignored) {}
                          preloadedImageUrl = null; preloadImageReady = false;
                      } catch (Exception e) {}
                  });
              }

            @android.webkit.JavascriptInterface
                public void receiveMessage(String json) {
                    // Handles cross-origin JS commands from canvas/widget/iframe slides.
                    // ASK_TOUCH: dispatches a synthetic MotionEvent to the WebView, bypassing Android's
                    // user-gesture requirement for media autoplay (OptiSigns technique).
                    // JS usage: window.Android.receiveMessage(JSON.stringify({ event: "sendDataToPlayer", data: { command: "ASK_TOUCH" } }))
                    try {
                        org.json.JSONObject msg = new org.json.JSONObject(json);
                        String event = msg.optString("event", "");
                        if ("sendDataToPlayer".equals(event)) {
                            org.json.JSONObject data = msg.optJSONObject("data");
                            if (data != null && "ASK_TOUCH".equals(data.optString("command", ""))) {
                                runOnUiThread(() -> {
                                    try {
                                        long now = android.os.SystemClock.uptimeMillis();
                                        float cx = webView.getWidth() / 2f;
                                        float cy = webView.getHeight() / 2f;
                                        android.view.MotionEvent down = android.view.MotionEvent.obtain(
                                            now, now, android.view.MotionEvent.ACTION_DOWN, cx, cy, 0);
                                        android.view.MotionEvent up = android.view.MotionEvent.obtain(
                                            now, now + 50, android.view.MotionEvent.ACTION_UP, cx, cy, 0);
                                        webView.dispatchTouchEvent(down);
                                        webView.dispatchTouchEvent(up);
                                        down.recycle();
                                        up.recycle();
                                    } catch (Throwable ignored) {}
                                });
                            }
                        }
                    } catch (Throwable ignored) {}
                }

              @android.webkit.JavascriptInterface
              public void setNativeFirstMode(boolean enabled) {
                  nativeFirstRendering = enabled;
                  android.util.Log.i("DigipalNative", "[nativeFirst] nativeFirstRendering=" + enabled);
              }

              @android.webkit.JavascriptInterface
              public void setWebViewDormant(boolean dormant) {
                  runOnUiThread(() -> {
                      try {
                          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                              if (dormant) {
                                  webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false);
                              } else {
                                  webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
                    dormantGeneration++; // Fix 3: cancel any pending delayed WebView recreation
                              }
                          }
                          android.util.Log.d("DigipalNative", "[nativeFirst] setWebViewDormant=" + dormant);
                          if (nativeFirstRendering && dormant && currentPlayerUrl != null) {
                              // Per-slide WebView recreation: destroy V8 heap + GPU compositor while native
                              // overlay covers the screen, then reload fresh for the next web slide.
                              // (OptiSigns technique — eliminates cumulative memory leak between slides.)
                              // Fix 8: skip WebView recreation for short slides (< 30 s) — mixed image/design
                              // playlists need the WebView imminently; recreating causes black frames.
                              // P1 Fix 8: never recreate the main WebView while the isolated per-slide
                              // WebView renderer (task #1875) is the active renderer -- that separate
                              // WebView, not this one, is what's covering the screen, so destroying
                              // `webView` here only reloads/flashes the React control shell for no benefit.
                              if (playlistScheduler != null
                                      && "isolated_webview".equals(playlistScheduler.getCurrentRendererKind())) {
                                  android.util.Log.d("DigipalNative",
                                      "[nativeFirst] WebView recreate skipped — isolated_webview renderer active");
                                  return;
                              }
                              final boolean longSlide = (currentNativeSlideDurationMs > 30_000L);
                              if (!longSlide) {
                                  android.util.Log.d("DigipalNative", "[nativeFirst] WebView recreate skipped — slide < 30 s");
                                  return;
                              }
                              final int myGen = ++dormantGeneration; // Fix 3: capture recreation token
                              new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                  try {
                                      if (myGen != dormantGeneration) { android.util.Log.d("DigipalNative", "[nativeFirst] WebView recreate cancelled gen=" + myGen); return; } // Fix 3
                                      final android.webkit.WebView oldWv = webView;
                                      if (rootLayout != null) rootLayout.removeView(oldWv);
                                      webView = new android.webkit.WebView(MainActivity.this);
                                      setupWebView();
                                      webView.setFocusableInTouchMode(true);
                                      webView.requestFocus();
                                      if (rootLayout != null) {
                                          rootLayout.addView(webView, 0, new FrameLayout.LayoutParams(
                                              FrameLayout.LayoutParams.MATCH_PARENT,
                                              FrameLayout.LayoutParams.MATCH_PARENT
                                          ));
                                      }
                                      webView.loadUrl(currentPlayerUrl);
                                      if (mediaDownloadManager != null) mediaDownloadManager.setWebView(webView);
                                      try { oldWv.stopLoading(); oldWv.destroy(); } catch (Throwable ignored2) {}
                                      android.util.Log.i("DigipalNative", "[nativeFirst] WebView recreated — V8/GPU heap cleared");
                                  } catch (Throwable e) {
                                      android.util.Log.e("DigipalNative", "[nativeFirst] WebView recreate failed", e);
                                  }
                              }, 400);
                          }
                      } catch (Throwable e) {
                          android.util.Log.e("DigipalNative", "setWebViewDormant error", e);
                      }
                  });
              }

                @JavascriptInterface
                public void requestNativeGC() {
                    try {
                        long now = System.currentTimeMillis();
                        if (now - lastGcMs < 5 * 60 * 1000L) {
                            android.util.Log.d("DigipalNative", "[gc_skipped] requestNativeGC: within 5-min cooldown");
                            return;
                        }
                        lastGcMs = now;
                        // Trim Glide in-memory cache so decoded bitmaps from the
                        // outgoing slide are released before ExoPlayer allocates for
                        // the incoming one.  On low-RAM devices (e.g. BHV5AW) this
                        // frees 30-60 MB at the playlist_transition peak.
                        runOnUiThread(() -> {
                            try {
                                com.bumptech.glide.Glide.get(MainActivity.this).clearMemory();
                            } catch (Throwable ignored) {}
                        });
                        Runtime.getRuntime().gc();
                        System.gc();
                        android.util.Log.d("DigipalNative", "[gc] requestNativeGC: Glide clearMemory + GC nudged");
                    } catch (Throwable e) {
                        android.util.Log.e("DigipalNative", "requestNativeGC error", e);
                    }
                }


                @android.webkit.JavascriptInterface
                public void setNativePlaylist(String json) {
                    if (json == null || json.isEmpty()) return;
                    if (playlistScheduler != null) {
                        playlistScheduler.setPlaylist(json);
                        android.util.Log.i("DigipalNative", "[nativeLoop] setNativePlaylist → PlaylistScheduler");
                        // Task 1952: an empty playlist means the native loop is
                        // releasing the screen back to the WebView. Nothing else
                        // re-shows it, so undo the dormant state here or the
                        // device is left on a permanent black screen.
                        boolean nativeLoopReleased = false;
                        try { nativeLoopReleased = new org.json.JSONArray(json).length() == 0; } catch (Throwable ignored) {}
                        if (nativeLoopReleased) {
                            runOnUiThread(() -> {
                                try {
                                    if (heartbeatRunnable != null) {
                                        heartbeatHandler.removeCallbacks(heartbeatRunnable);
                                        heartbeatRunnable = null;
                                    }
                                    if (webView != null) {
                                        try { webView.resumeTimers(); } catch (Throwable ignored2) {}
                                        webView.setAlpha(1f);
                                        webView.setVisibility(View.VISIBLE);
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            try { webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Throwable ignored3) {}
                                        }
                                        android.util.Log.i("DigipalNative", "[nativeLoop] empty playlist -> WebView re-activated");
                                    }
                                } catch (Throwable ignored4) {}
                            });
                        }
                        try { io.sentry.Breadcrumb _plBc = new io.sentry.Breadcrumb("Playlist loaded"); _plBc.setCategory("playlist"); _plBc.setType("info"); _plBc.setLevel(io.sentry.SentryLevel.INFO); try { org.json.JSONArray _slides = new org.json.JSONArray(json); _plBc.setData("slide_count", _slides.length()); } catch (Throwable _pe) {} io.sentry.Sentry.addBreadcrumb(_plBc); } catch (Throwable ignored) {}
                        if (assetCacheManager != null) {
                            // Asset downloads handled exclusively by PlaylistRepository.startRevisionPipeline()
                            // via MediaDownloadManager — do NOT download here to avoid duplicate work.
                        }
                    } else {
                        // Codex fix 7: PlaylistScheduler isn't constructed yet (very early bridge
                        // call). Buffer the JSON and flush it once the scheduler is ready instead
                        // of falling back to the legacy JS-driven NativePlaylistManager.
                        pendingNativePlaylistJson = json;
                        android.util.Log.i("DigipalNative", "[nativeLoop] setNativePlaylist: buffered (scheduler not ready yet)");
                    }
                }

                @android.webkit.JavascriptInterface
                public void onNativeRendererReady(String slideId) {
                    if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                    armStablePlaybackTimer();
                }

                @android.webkit.JavascriptInterface
                public void onNativeRendererError(String slideId, String error) {
                    if (playlistScheduler != null) playlistScheduler.onRendererError(slideId, error);
                    if (reliabilitySupervisor != null) reliabilitySupervisor.reportError("renderer", error);
                    if (recoveryCoordinator != null) {
                        recoveryCoordinator.reportSlideFailure(slideId, null, error, "webview",
                                memoryBudgetManager != null ? memoryBudgetManager.getCurrentTier() : null);
                    }
                    cancelStablePlaybackTimer();
                }

                @android.webkit.JavascriptInterface
                public void reloadNativePlaylist() {
                    // JS will call setNativePlaylist again with refreshed slide data
                    android.util.Log.i("DigipalNative", "[nativeLoop] reloadNativePlaylist signal received");
                      if (playlistScheduler != null) {
                          playlistScheduler.reloadActiveRevisionFromRoom();
                      }
                }

                @android.webkit.JavascriptInterface
                public void clearPendingCrash() {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .remove("pending_crash_type")
                        .remove("pending_crash_stack")
                        .remove("pending_crash_at")
                        .remove("pending_crash_free_mb")
                        .remove("pending_crash_total_mb")
                        .remove("pending_crash_ready")
                        .apply();
                }

      }


      // ─── Scheduler-direct video dispatch ────────────────────────────────────────
      // Called by schedulerPlayVideo delegate instead of going through evaluateJavascript.
      // Full-screen layout; ExoPlayer errors notify scheduler directly.
      private void playNativeVideoForScheduler(String url, String objectFit, boolean loop, float volume, String slideId, int contentId) {
          // Must be called on UI thread (called from runOnUiThread inside delegate).
          try {
              boolean fromPreload = url.equals(preloadedVideoUrl) && preloadPlayer != null;
              if (videoReadyHandler != null && videoReadyRunnable != null) {
                  videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null;
              }
              if (nativeVideoListener != null) {
                  if (exoPlayer != null) exoPlayer.removeListener(nativeVideoListener); nativeVideoListener = null;
              }
              // Full-screen physical-pixel dimensions (no window.innerWidth JS needed)
              android.util.DisplayMetrics _dm = getResources().getDisplayMetrics();
              FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(_dm.widthPixels, _dm.heightPixels);
              int resizeMode = "cover".equals(objectFit) ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM :
                               "fill".equals(objectFit)  ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL :
                               androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
              final androidx.media3.ui.PlayerView activeView  = activeVideoViewIsA ? nativeVideoView : nativeVideoViewB;
              final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
              final boolean useTexture = useTextureViewRenderer();
              final android.view.TextureView activeTexView  = activeVideoViewIsA ? nativeTexViewA : nativeTexViewB;
              final android.view.TextureView incomingTexView = activeVideoViewIsA ? nativeTexViewB : nativeTexViewA;
              if (pendingOldPlayer != null) {
                  android.util.Log.w("DigipalNative", "[Fix4] pendingOldPlayer non-null at playNativeVideoForScheduler start — prior swap incomplete for slide=" + slideId);
                  try { pendingOldPlayer.stop(); pendingOldPlayer.release(); } catch (Throwable ignored) {} pendingOldPlayer = null;
              }
              if (fromPreload) {
                  final androidx.media3.exoplayer.ExoPlayer oldPlayer = exoPlayer;
                  pendingOldPlayer = oldPlayer;
                  exoPlayer = preloadPlayer; preloadPlayer = null; preloadedVideoUrl = null;
                  exoPlayer.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF); // scheduler owns loop via natural-end listener
                  exoPlayer.setVolume(volume); exoPlayer.play();
                  startBufferWatchdog(slideId); // Fix 2: detect STATE_BUFFERING stall
                  preloadView.setResizeMode(resizeMode); preloadView.setLayoutParams(lp);
                  if (preloadVideoReady) {
                      stopBufferWatchdog(); // Fix 2: already ready — no stall risk
                      // Alpha swap — incoming becomes visible, outgoing fades out (fixes Fire TV blank SurfaceView)
                      if (useTexture) { incomingTexView.setAlpha(1f); incomingTexView.setVisibility(View.VISIBLE); activeTexView.setAlpha(0f); activeTexView.setVisibility(View.INVISIBLE); }
                      else { preloadView.setAlpha(1f); preloadView.setVisibility(View.VISIBLE); activeView.setAlpha(0f); activeView.setVisibility(View.INVISIBLE); }
                      hideNativeImagesForVideo();
                      android.util.Log.d("RendererOwner", "owner=video hideImages=true useTexture=" + useTextureViewRenderer());
                      activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                      pendingOldPlayer = null;
                      if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                      if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                      attachLoopAdvanceListener(exoPlayer, slideId);
                  } else {
                      final boolean[] done = {false};
                      nativeVideoListener = new androidx.media3.common.Player.Listener() {
                          @Override public void onRenderedFirstFrame() {
                              if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                              stopBufferWatchdog(); // Fix 2
                              if (done[0]) return; done[0] = true;
                              runOnUiThread(() -> {
                                  if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                  // Alpha swap — incoming becomes visible, outgoing fades out (fixes Fire TV blank SurfaceView)
                                  if (useTexture) { incomingTexView.setAlpha(1f); incomingTexView.setVisibility(View.VISIBLE); activeTexView.setAlpha(0f); activeTexView.setVisibility(View.INVISIBLE); }
                                  else { preloadView.setAlpha(1f); preloadView.setVisibility(View.VISIBLE); activeView.setAlpha(0f); activeView.setVisibility(View.INVISIBLE); }
                                  hideNativeImagesForVideo();
                                  android.util.Log.d("RendererOwner", "owner=video hideImages=true useTexture=" + useTextureViewRenderer());
                                  activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                                  pendingOldPlayer = null;
                                  if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                  if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                                  attachLoopAdvanceListener(exoPlayer, slideId);
                              });
                          }
                          @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                              android.util.Log.w("DigipalMetrics", "[sched preload onPlayerError] slide=" + slideId + " " + error.getMessage());
                              try { if (!(error.getCause() instanceof androidx.media3.exoplayer.ExoTimeoutException)) { io.sentry.Sentry.captureException(error); } } catch (Throwable ignored) {}
                              if (error.getCause() instanceof androidx.media3.exoplayer.ExoTimeoutException) return; // silence + let 2.5s fallback handle
                              if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                              stopBufferWatchdog(); // Fix 2
                              if (done[0]) return; done[0] = true;
                              if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                              pendingOldPlayer = null;
                              if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                              // Notify scheduler — it will retry or skip; no WebView hop needed
                              if (playlistScheduler != null) playlistScheduler.onRendererError(slideId, "exoplayer_preload_fatal");
                          }
                      };
                      exoPlayer.addListener(nativeVideoListener);
                      final android.os.Handler rh = new android.os.Handler(android.os.Looper.getMainLooper());
                      final Runnable rc = new Runnable() {
                          @Override public void run() {
                              videoReadyHandler = null; videoReadyRunnable = null; nativeVideoListener = null;
                              stopBufferWatchdog(); // Fix 2
                              if (done[0]) return; done[0] = true;
                              // Alpha swap — incoming becomes visible, outgoing fades out (fixes Fire TV blank SurfaceView)
                              if (useTexture) { incomingTexView.setAlpha(1f); incomingTexView.setVisibility(View.VISIBLE); activeTexView.setAlpha(0f); activeTexView.setVisibility(View.INVISIBLE); }
                              else { preloadView.setAlpha(1f); preloadView.setVisibility(View.VISIBLE); activeView.setAlpha(0f); activeView.setVisibility(View.INVISIBLE); }
                              hideNativeImagesForVideo();
                              android.util.Log.d("RendererOwner", "owner=video hideImages=true useTexture=" + useTextureViewRenderer());
                              activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                                pendingOldPlayer = null;
                                if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                // Notify scheduler so it can start the advance timer — without this the
                                // scheduler stays in PREPARING_CURRENT forever after a forced preload swap.
                                if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                                attachLoopAdvanceListener(exoPlayer, slideId);
                            }
                      };
                      videoReadyHandler = rh; videoReadyRunnable = rc; rh.postDelayed(rc, 2500);
                  }
              } else {
                  // Cold load
                  if (preloadPlayer != null) { try { preloadPlayer.release(); } catch (Throwable ignored) {} preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false; preloadView.setPlayer(null); }
                  final androidx.media3.exoplayer.ExoPlayer coldPlayer = buildCachedExoPlayer();
                  if (useTexture) {
                      preloadView.setPlayer(null);
                      coldPlayer.setVideoTextureView(incomingTexView);
                      incomingTexView.setLayoutParams(lp);
                      incomingTexView.setVisibility(View.VISIBLE); incomingTexView.setAlpha(0f);
                      android.util.Log.d("DigipalVideo", "[cold2] TextureView path alpha=0");
                  } else {
                      preloadView.setPlayer(coldPlayer); preloadView.setResizeMode(resizeMode); preloadView.setLayoutParams(lp);
                      preloadView.setVisibility(View.VISIBLE); preloadView.setAlpha(0f);
                      android.util.Log.d("DigipalVideo", "[cold2] SurfaceView path alpha=0");
                  }
                  coldPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(url)));
                  coldPlayer.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF); // scheduler owns loop via natural-end listener (attachLoopAdvanceListener); REPEAT_MODE_ONE caused 1-frame replay on single-video loops
                  coldPlayer.setVolume(volume); coldPlayer.prepare(); coldPlayer.play();
                  startBufferWatchdog(slideId); // Fix 2: detect STATE_BUFFERING stall
                  android.util.Log.d("DigipalVideo", "[cold-load diag] pvVisibility=" + (preloadView != null ? preloadView.getVisibility() : -1) + " pvAlpha=" + (preloadView != null ? preloadView.getAlpha() : -1f) + " state=" + coldPlayer.getPlaybackState() + " url_scheme=" + (url.contains("://") ? url.substring(0, url.indexOf("://")) : "?"));
                  coldPlayer.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                      @Override public void onVideoSizeChanged(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t, androidx.media3.common.VideoSize vs) {
                          android.util.Log.i("DigipalVideo", "[cold diag] onVideoSizeChanged " + vs.width + "x" + vs.height);
                      }
                      @Override public void onPlaybackStateChanged(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t, int state) {
                          android.util.Log.d("DigipalVideo", "[cold diag] onPlaybackStateChanged state=" + state + " (3=ready,4=ended)");
                      }
                      @Override public void onVideoDecoderInitialized(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t, String decoderName, long initDurationMs) {
                          android.util.Log.i("DigipalVideo", "[cold diag] decoder=" + decoderName + " initMs=" + initDurationMs);
                      }
                      @Override public void onRenderedFirstFrame(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t, Object output, long renderMs) {
                          android.util.Log.i("DigipalVideo", "[cold diag] onRenderedFirstFrame renderMs=" + renderMs + " pvAlpha=" + (preloadView != null ? preloadView.getAlpha() : -1f) + " texAlpha=" + (incomingTexView != null ? incomingTexView.getAlpha() : -1f));
                      }
                  });
                  final androidx.media3.exoplayer.ExoPlayer oldPlayer = exoPlayer;
                  exoPlayer = coldPlayer; pendingOldPlayer = oldPlayer;
                  final android.os.Handler rh = new android.os.Handler(android.os.Looper.getMainLooper());
                  final Runnable rc = new Runnable() {
                      @Override public void run() {
                          if (exoPlayer != null && nativeVideoListener != null) {
                              exoPlayer.removeListener(nativeVideoListener); nativeVideoListener = null;
                          }
                          videoReadyHandler = null; videoReadyRunnable = null;
                          android.util.Log.w("DigipalMetrics",
                              "[sched 8s timeout] cold-load slide=" + slideId + " — no first-frame; falling back to WebView");
                          stopBufferWatchdog(); // Fix 2
                          // Release failed player and hide all native surfaces before WebView takes over
                          final androidx.media3.exoplayer.ExoPlayer _timedOut = exoPlayer;
                          if (_timedOut != null) {
                              try { _timedOut.stop(); _timedOut.release(); } catch (Throwable ignored) {}
                              if (exoPlayer == _timedOut) exoPlayer = null;
                          }
                          hideNativeVideoSurfaces();
                          if (nativeImageView  != null) nativeImageView.setVisibility(android.view.View.INVISIBLE);
                          if (nativeImageViewB != null) nativeImageViewB.setVisibility(android.view.View.INVISIBLE);
                          if (webView != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                              try { webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Throwable ignored) {}
                          }
                          // Cancel pending Fire TV heartbeat — JS timers resume now.
                            if (heartbeatRunnable != null) {
                                heartbeatHandler.removeCallbacks(heartbeatRunnable);
                                heartbeatRunnable = null;
                                android.util.Log.d("RendererOwner", "native heartbeat stopped");
                            }
                            // Failed native video must report failure to the scheduler, not
                            // pretend to be ready via the retired legacy __digipalGotoSlide WebView
                            // hop. onRendererError() lets the scheduler retry/skip/advance and
                            // surfaces the failure in telemetry instead of silently faking success.
                            if (playlistScheduler != null) playlistScheduler.onRendererError(slideId, "native_video_timeout");
                      }
                  };
                  videoReadyHandler = rh; videoReadyRunnable = rc; rh.postDelayed(rc, 8000);
                  nativeVideoListener = new androidx.media3.common.Player.Listener() {
                      @Override public void onRenderedFirstFrame() {
                          if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                          stopBufferWatchdog(); // Fix 2
                          if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                          runOnUiThread(() -> {
                              // Alpha swap — incoming becomes visible, outgoing fades out (fixes Fire TV blank SurfaceView)
                              if (useTexture) { incomingTexView.setAlpha(1f); incomingTexView.setVisibility(View.VISIBLE); activeTexView.setAlpha(0f); activeTexView.setVisibility(View.INVISIBLE); }
                              else { preloadView.setAlpha(1f); preloadView.setVisibility(View.VISIBLE); activeView.setAlpha(0f); activeView.setVisibility(View.INVISIBLE); }
                              hideNativeImagesForVideo();
                              android.util.Log.d("RendererOwner", "owner=video hideImages=true useTexture=" + useTextureViewRenderer());
                              activeVideoViewIsA = !activeVideoViewIsA;
                              pendingOldPlayer = null;
                              if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} }
                              if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                              attachLoopAdvanceListener(exoPlayer, slideId);
                          });
                      }
                      @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                          android.util.Log.w("DigipalMetrics", "[sched cold onPlayerError] slide=" + slideId + " " + error.getMessage());
                          try { if (!(error.getCause() instanceof androidx.media3.exoplayer.ExoTimeoutException)) { io.sentry.Sentry.captureException(error); } } catch (Throwable ignored) {}
                          if (error.getCause() instanceof androidx.media3.exoplayer.ExoTimeoutException) return; // silence + let 8s fallback handle
                          stopBufferWatchdog(); // Fix 2
                          if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                          if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                          // Failed cold-load: release player, hide native surfaces, activate WebView
                          final androidx.media3.exoplayer.ExoPlayer _failedSched = exoPlayer;
                          exoPlayer = oldPlayer;
                          pendingOldPlayer = null;
                          if (_failedSched != null) {
                              try { _failedSched.stop(); _failedSched.release(); } catch (Throwable ignored) {}
                          }
                          if (preloadView != null) preloadView.setPlayer(null);
                          hideNativeVideoSurfaces();
                          if (nativeImageView  != null) nativeImageView.setVisibility(android.view.View.INVISIBLE);
                          if (nativeImageViewB != null) nativeImageViewB.setVisibility(android.view.View.INVISIBLE);
                          if (webView != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                              try { webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Throwable ignored) {}
                          }
                          // Failed native video must report failure to the scheduler, not
                            // pretend to be ready via the retired legacy __digipalGotoSlide WebView
                            // hop. onRendererError() lets the scheduler retry/skip/advance and
                            // surfaces the failure in telemetry instead of silently faking success.
                            if (playlistScheduler != null) playlistScheduler.onRendererError(slideId, "native_video_error");
                      }
                  };
                  exoPlayer.addListener(nativeVideoListener);
              }
          } catch (Exception e) { android.util.Log.e("DigipalNative", "playNativeVideoForScheduler error", e); }
      }

      /** True when running on an Amazon Fire TV / Fire Stick device. */
      private boolean isFireTv() {
          return android.os.Build.MANUFACTURER.equalsIgnoreCase("Amazon")
              || android.os.Build.MODEL.toUpperCase(Locale.ROOT).startsWith("AFT");
      }

      /** Returns true when TextureView renderer is selected (default for Fire TV). */
      private boolean useTextureViewRenderer() {
          String def = "texture" /* TextureView for all Android TV — avoids SurfaceView z-order issues on Android box */;
          String pref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
              .getString(PREF_VIDEO_RENDERER, def);
          return "texture".equals(pref);
      }

      /**
       * Releases both video PlayerViews and reattaches them with the currently-selected
       * surface type. Call after the user changes pref_video_renderer in settings.
       */
      /**
       * Full renderer reset: tears down all active players and surface bindings, then
       * re-establishes the view-pair state for the renderer mode currently in SharedPrefs.
       * Call after the user changes pref_video_renderer in settings.
       */
      private void resetVideoRenderer() {
          try {
              boolean useTexture = useTextureViewRenderer();
              android.util.Log.i("DigipalVideo", "[resetVideoRenderer] mode=" + (useTexture ? "texture" : "surface") + " — releasing players...");
              // 1. Detach texture/surface binding BEFORE release so ExoPlayer does not hold a dead surface
              if (exoPlayer != null) {
                  try {
                      if (useTexture) { exoPlayer.setVideoTextureView(null); }
                      else            { exoPlayer.setVideoSurface(null); }
                  } catch (Throwable ignored) {}
                  try { exoPlayer.stop(); exoPlayer.release(); } catch (Throwable ignored) {}
                  exoPlayer = null;
              }
              // 2. Release preload player (if any)
              if (preloadPlayer != null) {
                  try {
                      if (useTexture) { preloadPlayer.setVideoTextureView(null); }
                      preloadPlayer.stop(); preloadPlayer.release();
                  } catch (Throwable ignored) {}
                  preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false;
              }
              // 3. Detach and hide PlayerViews (SurfaceView path)
              if (nativeVideoView  != null) { try { nativeVideoView.setPlayer(null);  } catch (Throwable ignored) {} nativeVideoView.setAlpha(0f);  nativeVideoView.setVisibility(View.INVISIBLE);  }
              if (nativeVideoViewB != null) { try { nativeVideoViewB.setPlayer(null); } catch (Throwable ignored) {} nativeVideoViewB.setAlpha(0f); nativeVideoViewB.setVisibility(View.INVISIBLE); }
              // 4. Clear TextureView alpha/visibility (TextureView path)
              if (nativeTexViewA != null) { nativeTexViewA.setAlpha(0f); nativeTexViewA.setVisibility(View.INVISIBLE); }
              if (nativeTexViewB != null) { nativeTexViewB.setAlpha(0f); nativeTexViewB.setVisibility(View.INVISIBLE); }
              // 5. Reset slot pointer so next playNativeVideo() starts clean in slot A
              activeVideoViewIsA = true;
              android.util.Log.i("DigipalVideo", "[resetVideoRenderer] done — next pipeline will use " + (useTexture ? "TextureView" : "SurfaceView"));
          } catch (Throwable e) {
              android.util.Log.e("DigipalVideo", "resetVideoRenderer error", e);
          }
      }

      /** Detach player p from both TextureViews and both PlayerViews. Safe with null. */
        private void clearVideoOutput(androidx.media3.exoplayer.ExoPlayer p) {
            if (p == null) return;
            try { p.setVideoTextureView(null); } catch (Throwable ignored) {}
            try { p.setVideoSurface(null);     } catch (Throwable ignored) {}
            try { if (nativeVideoView  != null) nativeVideoView.setPlayer(null);  } catch (Throwable ignored) {}
            try { if (nativeVideoViewB != null) nativeVideoViewB.setPlayer(null); } catch (Throwable ignored) {}
        }

        /** Set all four video surfaces alpha=0/INVISIBLE — nothing covers WebView/design/kiosk. */
        private void hideNativeVideoSurfaces() {
            if (nativeVideoView  != null) { nativeVideoView.setAlpha(0f);  nativeVideoView.setVisibility(View.INVISIBLE);  }
            if (nativeVideoViewB != null) { nativeVideoViewB.setAlpha(0f); nativeVideoViewB.setVisibility(View.INVISIBLE); }
            if (nativeTexViewA   != null) { nativeTexViewA.setAlpha(0f);   nativeTexViewA.setVisibility(View.INVISIBLE);   }
            if (nativeTexViewB   != null) { nativeTexViewB.setAlpha(0f);   nativeTexViewB.setVisibility(View.INVISIBLE);   }
        }

        /** Clear output binding then stop+release player. Safe with null. */
        private void releaseVideoPlayer(androidx.media3.exoplayer.ExoPlayer p) {
            if (p == null) return;
            clearVideoOutput(p);
            try { p.stop(); p.release(); } catch (Throwable ignored) {}
        }

        /** Hide both native image views when video becomes the active renderer.
         *  nativeImageView/nativeImageViewB sit above TextureView/PlayerView in the
         *  FrameLayout, so they must be explicitly hidden or they cover the video.
         *  Call at every video-visible swap point (first frame ready / immediate swap). */
        private void hideNativeImagesForVideo() {
            try { com.bumptech.glide.Glide.with(this).clear(nativeImageView);  } catch (Throwable ignored) {}
            try { com.bumptech.glide.Glide.with(this).clear(nativeImageViewB); } catch (Throwable ignored) {}
            if (nativeImageView  != null) { nativeImageView.setAlpha(0f);  nativeImageView.setVisibility(View.INVISIBLE);  }
            if (nativeImageViewB != null) { nativeImageViewB.setAlpha(0f); nativeImageViewB.setVisibility(View.INVISIBLE); }
        }

        private void initNativeComponents() {
          try {
              playlistRepository = new PlaylistRepository(this);
              telemetryManager   = new TelemetryManager(this, playlistRepository, getServerUrl());
              if (cachedPairingCode != null) telemetryManager.setPairingCode(cachedPairingCode);
              telemetryManager.start();

                // Per-asset WebView policy task: track the Android System WebView
                // package/version for diagnostics — surfaced in logs/telemetry, not a
                // new dashboard.
                android.util.Log.i("DigipalWebView", "system webview = " + WebViewPolicy.currentWebViewPackageInfo(MainActivity.this));

                // ---- MemoryBudgetManager: 5-second memory tier poller ----
                memoryBudgetManager = new MemoryBudgetManager(
                    getApplicationContext(),
                    (oldTier, newTier) -> {
                        android.util.Log.i("DigipalMemory", "tier " + oldTier + " -> " + newTier);
                        runOnUiThread(() -> {
                            try {
                                if (newTier == MemoryBudgetManager.Tier.CRITICAL) {
                                    // CRITICAL: cancel preloads, release inactive renderer, clear Glide
                                    if (assetCacheManager != null) assetCacheManager.setMaxConcurrency(1);
                                    if (preloadPlayer != null) {
                                        try { preloadPlayer.release(); } catch (Throwable ignored) {}
                                        preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false;
                                    }
                                    // Low-memory WebView policy: on CRITICAL, aggressively destroy the
                                    // isolated WebView renderer if it is not the one currently on screen
                                    // — CRITICAL means "one renderer only, no idle WebView".
                                    if (isolatedWebRenderer != null && !isolatedWebRenderer.isShowing()) {
                                        try { isolatedWebRenderer.destroy(); isolatedWebRenderer = null; } catch (Throwable ignored) {}
                                        android.util.Log.w("DigipalMemory", "[CRITICAL] idle isolated WebView destroyed");
                                    }
                                    try { com.bumptech.glide.Glide.get(MainActivity.this).clearMemory();
                                    } catch (Throwable ignored) {}
                                    android.util.Log.w("DigipalMemory", "[CRITICAL] preloads cancelled, Glide cleared");
                                } else if (newTier == MemoryBudgetManager.Tier.LOW) {
                                    // LOW: reduce download concurrency, moderate Glide trim
                                    if (assetCacheManager != null) assetCacheManager.setMaxConcurrency(1);
                                    try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(
                                        android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE);
                                    } catch (Throwable ignored) {}
                                    android.util.Log.i("DigipalMemory", "[LOW] download concurrency reduced");
                                } else {
                                    // NORMAL / RECOVERED: restore full concurrency
                                    if (assetCacheManager != null) assetCacheManager.setMaxConcurrency(3);
                                    android.util.Log.i("DigipalMemory", "[NORMAL] concurrency restored");
                                }
                            } catch (Throwable e) {
                                android.util.Log.e("DigipalMemory", "tier action failed", e);
                            }
                        });
                    },
                    js -> runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); }));
                memoryBudgetManager.start();

                // ---- RecoveryCoordinator: ordered escalation router ----
                recoveryCoordinator = new RecoveryCoordinator(getApplicationContext(),
                    new RecoveryCoordinator.EscalationDelegate() {
                        @Override public void onSlideRetry(String sid, String reason) {
                            android.util.Log.i("DigipalRecovery", "[SLIDE_RETRY] " + sid + " " + reason);
                            if (playlistScheduler != null) runOnUiThread(() -> playlistScheduler.retryCurrentSlide());
                        }
                        @Override public void onSlideSkip(String sid, String reason) {
                            android.util.Log.w("DigipalRecovery", "[SLIDE_SKIP] " + sid + " " + reason);
                            if (playlistScheduler != null) runOnUiThread(() -> playlistScheduler.skipCurrentSlide());
                        }
                        @Override public void onRendererRebuild(String reason) {
                            android.util.Log.w("DigipalRecovery", "[RENDERER_REBUILD] " + reason);
                            runOnUiThread(() -> releaseAllRenderers());
                        }
                        @Override public void onWebViewRebuild(String reason) {
                            android.util.Log.w("DigipalRecovery", "[WEBVIEW_REBUILD] " + reason);
                            // Guard: if onRenderProcessGone already triggered a rebuild, skip re-entry
                            runOnUiThread(() -> { if (webView != null && !webViewRecoveryInProgress) recoverFromRenderProcessGone(webView); });
                        }
                        @Override public void onPlaylistRollback(String reason) {
                            android.util.Log.w("DigipalRecovery", "[PLAYLIST_ROLLBACK] " + reason);
                            // Roll back to last known-good revision via PlaylistRepository
                            if (playlistRepository != null && playlistScheduler != null) {
                                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                    PlaylistDatabase.PlaylistRevisionEntity last = playlistRepository.getLastKnownGood();
                                    if (last != null && last.localManifest != null && !last.localManifest.isEmpty()) {
                                        playlistScheduler.setPlaylist(last.localManifest);
                                        android.util.Log.i("DigipalRecovery", "[ROLLBACK] reverted to revision " + last.revisionId);
                                        try { io.sentry.Sentry.captureMessage("Playlist rollback to revision " + last.revisionId, io.sentry.SentryLevel.WARNING); } catch (Throwable _sbc) {}
                                    }
                                });
                            }
                        }
                        @Override public void onSoftRestart(String reason) {
                            android.util.Log.e("DigipalRecovery", "[SOFT_RESTART] " + reason);
                            AppRecoverManager.scheduleRecovery(getApplicationContext());
                        }
                        @Override public void onHardRestart(String reason) {
                            android.util.Log.e("DigipalRecovery", "[HARD_RESTART] " + reason);
                            AppRecoverManager.scheduleRecovery(getApplicationContext());
                        }
                    },
                    js -> runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); }));
                
                final ReliabilitySupervisor[] supRef = new ReliabilitySupervisor[1];

              playlistScheduler = new PlaylistScheduler(
                  new PlaylistScheduler.Delegate() {
                      @Override public void schedulerPlayVideo(PlaylistScheduler.SlidePlan s) {
                          final String _url = s.url; final String _fit = s.objectFit;
                          final boolean _loop = s.loop; final float _vol = s.volume;
                          final String _sid = s.slideId; final int _contentId = s.contentId;
                          final long _dur = s.durationMs;
                          if (healthMonitor != null) healthMonitor.setRendererTypeNative();
                          runOnUiThread(() -> {
                              try {
                                  currentNativeSlideDurationMs = _dur;
                                  // Set WebView dormant directly — no evaluateJavascript round-trip
                                  if (webView != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                      try { webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_BOUND, false); } catch (Throwable ignored) {}
                                  }
                                  try { io.sentry.Breadcrumb _vBc = new io.sentry.Breadcrumb("Slide start: VIDEO"); _vBc.setCategory("playback"); _vBc.setType("info"); _vBc.setLevel(io.sentry.SentryLevel.DEBUG); _vBc.setData("slide_id", _sid); _vBc.setData("content_id", _contentId); _vBc.setData("duration_ms", _dur); io.sentry.Sentry.addBreadcrumb(_vBc); } catch (Throwable _sbc) {}
                  playNativeVideoForScheduler(_url, _fit, _loop, _vol, _sid, _contentId);
                        MainActivity.this.startStallWatchdog(); // Fix 4
                              } catch (Throwable ignored) {}
                              if (supRef[0] != null) supRef[0].reportSchedulerAdvance();
                          });
                      }
                      @Override public void schedulerShowImage(PlaylistScheduler.SlidePlan s) {
                          final String _url = s.url; final String _sc = s.scaleType;
                          final String _sid = s.slideId;
                          final long _dur = s.durationMs;
                          if (healthMonitor != null) healthMonitor.setRendererTypeNative();
                          try { io.sentry.Breadcrumb _iBc = new io.sentry.Breadcrumb("Slide start: IMAGE"); _iBc.setCategory("playback"); _iBc.setType("info"); _iBc.setLevel(io.sentry.SentryLevel.DEBUG); _iBc.setData("slide_id", _sid); _iBc.setData("duration_ms", _dur); io.sentry.Sentry.addBreadcrumb(_iBc); } catch (Throwable _sbc) {}
                          runOnUiThread(() -> {
                              try {
                                  currentNativeSlideDurationMs = _dur;
                                  // Set WebView dormant directly — no evaluateJavascript round-trip
                                  if (webView != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                      try { webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_BOUND, false); } catch (Throwable ignored) {}
                                  }
                                  android.widget.ImageView.ScaleType st =
                                      "cover".equals(_sc) ? android.widget.ImageView.ScaleType.CENTER_CROP :
                                      "fill".equals(_sc)  ? android.widget.ImageView.ScaleType.FIT_XY :
                                      android.widget.ImageView.ScaleType.FIT_CENTER;
                                  // Full-screen layout (physical pixels) — no window.innerWidth JS needed
                                  android.util.DisplayMetrics _dm = getResources().getDisplayMetrics();
                                  FrameLayout.LayoutParams _lp = new FrameLayout.LayoutParams(_dm.widthPixels, _dm.heightPixels);
                                  final android.widget.ImageView activeImgView  = activeImageViewIsA ? nativeImageView : nativeImageViewB;
                                  final android.widget.ImageView preloadImgView = activeImageViewIsA ? nativeImageViewB : nativeImageView;
                                  if (_url.equals(preloadedImageUrl) && preloadImageReady) {
                                      // Instant swap — preloaded image already decoded
                                      preloadImgView.setScaleType(st);
                                      preloadImgView.setLayoutParams(_lp);
                                      preloadImgView.setVisibility(View.VISIBLE);
                                      activeImgView.setVisibility(View.INVISIBLE);
                                      try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE); } catch (Throwable ignored) {}
                                      com.bumptech.glide.Glide.with(MainActivity.this).clear(activeImgView);
                                      activeImageViewIsA = !activeImageViewIsA;
                                      preloadedImageUrl = null; preloadImageReady = false;
                                      if (playlistScheduler != null) playlistScheduler.onRendererReady(_sid);
                                  } else {
                                      // Cold load — load directly; keep view INVISIBLE until Glide succeeds
                                      activeImgView.setLayoutParams(_lp);
                                      activeImgView.setScaleType(st);
                                      activeImgView.setVisibility(View.INVISIBLE);
                                      com.bumptech.glide.Glide.with(MainActivity.this)
                                          .load(_url)
                                          .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                              @Override public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                                      Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                                  android.util.Log.w("DigipalNative", "[schedulerShowImage] Glide failed slide=" + _sid + ": " + (e != null ? e.getMessage() : "null"));
                                                  try { io.sentry.Sentry.captureMessage("Glide image load failed: slide=" + _sid + " err=" + (e != null ? e.getMessage() : "null"), io.sentry.SentryLevel.WARNING); } catch (Throwable _sbc) {}
                                                  try { io.sentry.Breadcrumb _bc = new io.sentry.Breadcrumb("Glide load failed slide=" + _sid + ": " + (e != null ? e.getMessage() : "null")); _bc.setLevel(io.sentry.SentryLevel.WARNING); _bc.setType("error"); io.sentry.Sentry.addBreadcrumb(_bc); } catch (Throwable ignored) {}
                                                  // Notify scheduler directly — it will retry/skip; no brown square lingers
                                                  if (playlistScheduler != null) playlistScheduler.onRendererError(_sid, "glide_load_failed");
                                                  return false;
                                              }
                                              @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                      com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                      com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                                  // Wait for pre-draw to prevent brown/golden-square GPU artifact
                                                  activeImgView.getViewTreeObserver().addOnPreDrawListener(
                                                      new android.view.ViewTreeObserver.OnPreDrawListener() {
                                                          @Override public boolean onPreDraw() {
                                                              activeImgView.getViewTreeObserver().removeOnPreDrawListener(this);
                                                              activeImgView.setVisibility(View.VISIBLE);
                                                              if (playlistScheduler != null) playlistScheduler.onRendererReady(_sid);
                                                              try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE); } catch (Throwable ignored) {}
                                                              return true;
                                                          }
                                                      });
                                                  activeImgView.invalidate();
                                                  return false;
                                              }
                                          })
                                          .into(activeImgView);
                                  }
                              } catch (Throwable e) { android.util.Log.e("DigipalNative", "schedulerShowImage error", e); }
                              if (supRef[0] != null) supRef[0].reportSchedulerAdvance();
                          });
                      }
                      @Override public void schedulerPreloadVideo(PlaylistScheduler.SlidePlan s) {
                          final String _url = s.url;
                          runOnUiThread(() -> {
                              try {
                                  // Preload video directly in Java — no WebView hop
                                  if (_url == null || _url.isEmpty() || _url.equals(preloadedVideoUrl)) return;
                                  if (preloadPlayer != null) { try { preloadPlayer.release(); } catch (Throwable ignored) {} preloadPlayer = null; }
                                  final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
                                  preloadView.setPlayer(null);
                                  preloadedVideoUrl = null; preloadVideoReady = false;
                                  android.app.ActivityManager _am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                                  android.app.ActivityManager.MemoryInfo _mi = new android.app.ActivityManager.MemoryInfo();
                                  _am.getMemoryInfo(_mi); if (_mi.lowMemory) return;
                                  preloadView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                                  preloadPlayer = buildCachedExoPlayer();
                                  if (useTextureViewRenderer()) {
                                    final android.view.TextureView _preloadTex = activeVideoViewIsA ? nativeTexViewB : nativeTexViewA;
                                    preloadView.setPlayer(null);
                                    preloadPlayer.setVideoTextureView(_preloadTex);
                                    _preloadTex.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                                    _preloadTex.setVisibility(View.VISIBLE); _preloadTex.setAlpha(0f);
                                  } else {
                                    preloadView.setPlayer(preloadPlayer);
                                    preloadView.setVisibility(View.VISIBLE); preloadView.setAlpha(0f);
                                  }
                                  preloadPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(_url)));
                                  preloadPlayer.setVolume(0f); preloadPlayer.setPlayWhenReady(false); preloadPlayer.prepare();
                                  preloadedVideoUrl = _url;
                                  final androidx.media3.exoplayer.ExoPlayer cap = preloadPlayer;
                                  cap.addListener(new androidx.media3.common.Player.Listener() {
                                      @Override public void onRenderedFirstFrame() { if (cap == preloadPlayer) preloadVideoReady = true; cap.removeListener(this); }
                                  });
                              } catch (Throwable ignored) {}
                          });
                      }
                      @Override public void schedulerPreloadImage(PlaylistScheduler.SlidePlan s) {
                          final String _url = s.url;
                          runOnUiThread(() -> {
                              try {
                                  // Preload image directly in Java — no WebView hop
                                  if (_url == null || _url.isEmpty() || (_url.equals(preloadedImageUrl) && preloadImageReady)) return;
                                  android.app.ActivityManager _am2 = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                                  android.app.ActivityManager.MemoryInfo _mi2 = new android.app.ActivityManager.MemoryInfo();
                                  _am2.getMemoryInfo(_mi2); if (_mi2.lowMemory) return;
                                  final android.widget.ImageView preloadImgView = activeImageViewIsA ? nativeImageViewB : nativeImageView;
                                  com.bumptech.glide.Glide.with(MainActivity.this).clear(preloadImgView);
                                  preloadedImageUrl = null; preloadImageReady = false;
                                  preloadImgView.setVisibility(View.INVISIBLE);
                                  preloadImgView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                                  preloadedImageUrl = _url;
                                  com.bumptech.glide.Glide.with(MainActivity.this).load(_url)
                                      .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                          @Override public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e, Object m,
                                                  com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t, boolean f) {
                                              if (_url.equals(preloadedImageUrl)) preloadImageReady = false; return false;
                                          }
                                          @Override public boolean onResourceReady(android.graphics.drawable.Drawable r, Object m,
                                                  com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t,
                                                  com.bumptech.glide.load.DataSource ds, boolean f) {
                                              if (_url.equals(preloadedImageUrl)) preloadImageReady = true; return false;
                                          }
                                      }).into(preloadImgView);
                              } catch (Throwable ignored) {}
                          });
                      }
                      // schedulerActivateWebView removed (task #1886) — the legacy shared
                      // long-lived WebView (__digipalGotoSlide) activation path was retired;
                      // WEBVIEW_DESIGN/KIOSK/URL slides now always dispatch through
                      // schedulerActivateIsolatedRenderer(). schedulerDeactivateWebView() is kept
                      // because it's still used before native VIDEO/IMAGE dispatch and before
                      // isolated-renderer activation.
                      @Override public void schedulerDeactivateWebView() {
                          // Hide WebView + pause timers — enforces single renderer ownership.
                          // WebView sits above native layers in FrameLayout z-order so it must be
                          // INVISIBLE while native video/image is the active renderer.
                          runOnUiThread(() -> {
                              try {
                                  if (webView != null) {
                                      webView.setAlpha(0f);
                                      webView.setVisibility(View.INVISIBLE);
                                      webView.pauseTimers();
                                        // v59 Fix 11: proof-of-pairing log. Every pauseTimers() call here
                                        // MUST be matched by a resumeTimers() call in
                                        // schedulerActivateIsolatedRenderer() before that WebView is asked
                                        // to run JS again -- grep logcat for "timers=paused"/"timers=resumed"
                                        // to confirm no slide is left with frozen JS.
                                        android.util.Log.d("RendererOwner", "owner=native webView hidden timers=paused");
                                        // Keepalive (task 1952): keep WebSocket + JS alive on ALL devices while
                                        // timers are paused -- not just Fire TV. pauseTimers() freezes
                                        // setInterval so the WS heartbeat and status polling stop; a Java
                                        // Handler fires evaluateJavascript every 25s instead. The
                                        if (heartbeatRunnable != null) {
                                            heartbeatHandler.removeCallbacks(heartbeatRunnable);
                                            heartbeatRunnable = null;
                                        }
                                        heartbeatRunnable = new Runnable() {
                                            @Override public void run() {
                                                try {
                                                    if (webView != null) {
                                                        webView.evaluateJavascript(
                                                            "try{window.__digipalHeartbeat&&window.__digipalHeartbeat();}catch(e){}",
                                                            null
                                                        );
                                                    }
                                                } catch (Throwable ignored2) {}
                                                heartbeatHandler.postDelayed(this, 25_000);
                                            }
                                        };
                                        heartbeatHandler.postDelayed(heartbeatRunnable, 25_000);
                                        android.util.Log.d("RendererOwner", "native keepalive started (all devices)");
                                    }
                                } catch (Throwable ignored) {}
                          });
                      }
                      @Override public void schedulerDeactivateWebViewForIsolatedRenderer() {
                            // Codex P0 Fix 1: WebView.pauseTimers() is PROCESS-WIDE — it freezes
                            // JS timers in every WebView instance in the app, not just the one
                            // being hidden. schedulerDeactivateWebView() (above) calls pauseTimers()
                            // and that is fine before native VIDEO/IMAGE dispatch (no WebView needs
                            // to run JS afterwards). But dispatch() was also calling it immediately
                            // before schedulerActivateIsolatedRenderer(), which froze the brand-new
                            // isolated renderer WebView's timers before its React/design/kiosk JS
                            // even had a chance to run — causing design and kiosk slides to never
                            // send ready() (blank/stuck slides, especially after Fire TV heartbeat
                            // handling suspended real JS timers). This variant just hides the main
                            // WebView WITHOUT touching timers, and does not start the Fire TV
                            // Java-heartbeat workaround (unnecessary since nothing is paused).
                            runOnUiThread(() -> {
                                try {
                                    if (webView != null) {
                                        webView.setAlpha(0f);
                                        webView.setVisibility(View.INVISIBLE);
                                        android.util.Log.d("RendererOwner", "owner=isolated webView hidden (timers not paused)");
                                    }
                                } catch (Throwable ignored) {}
                            });
                        }
                        @Override public void schedulerActivateIsolatedRenderer(PlaylistScheduler.SlidePlan s) {
                          // Isolated per-slide WebView renderer (task #1875) — the only
                          // WebView-delegated rendering path for WEBVIEW_DESIGN/KIOSK/URL slides
                          // (legacy shared long-lived WebView retired task #1886). Loads the
                          // standalone /tv/render/:pairingCode/:contentId route in a WebView that
                          // is never shared across slides, so a crash/hang on one design cannot
                          // take down subsequent slides. Reports failures via
                          // onIsolatedRendererFailed(), which retries the same slide instead of
                          // falling back to any legacy WebView path.
                          runOnUiThread(() -> {
                              try {
                                  // Codex P0 Fix 1 (defensive): if this WebView's timers were
                                  // ever paused by schedulerDeactivateWebView() (native VIDEO/IMAGE
                                  // path), resume them before loading isolated-renderer content --
                                  // otherwise the freshly-loaded design/kiosk JS would never get a
                                  // running timer loop to send ready() or run its own animations.
                                  if (webView != null) {
                                      webView.resumeTimers();
                                      // v59 Fix 11: proof-of-pairing log -- pairs with
                                      // "owner=native webView hidden timers=paused" above.
                                      android.util.Log.d("RendererOwner", "owner=isolated webView timers=resumed");
                                  }
                                  if (healthMonitor != null) healthMonitor.setRendererTypeWeb();
                                  hideNativeVideoSurfaces();
                                  hideNativeImagesForVideo();
                                  final String code = cachedPairingCode;
                                  if (code == null || code.isEmpty()) {
                                      android.util.Log.w("DigipalNative",
                                          "[isolatedRenderer] no pairing code cached — falling back for slide " + s.slideId);
                                      if (playlistScheduler != null) {
                                          playlistScheduler.onIsolatedRendererFailed(s.slideId, "no_pairing_code");
                                      }
                                      return;
                                  }
                                  if (isolatedWebRenderer == null) {
                                      isolatedWebRenderer = new IsolatedWebRenderer(
                                          MainActivity.this, rootLayout,
                                          new IsolatedWebRenderer.Listener() {
                                              @Override public void onRendererReady(String slideId) {
                                                  if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                                                  // P1 Fix 7: the isolated per-slide WebView is a separate
                                                  // WebView instance from the main React shell's `webView`
                                                  // field, so its readiness never reaches window.__digipal
                                                  // NativeRendererReady on the main WebView. Without this call,
                                                  // waitForNativeRendererReady() in playerBridge.ts always falls
                                                  // through to its ~2s timeout instead of firing as soon as the
                                                  // isolated slide is actually ready, delaying setWebViewDormant(true).
                                                  if (webView != null) {
                                                      final String quotedSlideId = org.json.JSONObject.quote(slideId);
                                                      runOnUiThread(() -> {
                                                          try {
                                                              webView.evaluateJavascript(
                                                                  "window.__digipalNativeRendererReady && window.__digipalNativeRendererReady(" + quotedSlideId + ")",
                                                                  null);
                                                          } catch (Throwable ignored) {}
                                                      });
                                                  }
                                              }
                                              @Override public void onRendererFailed(String slideId, String reason) {
                                                  if (isolatedWebRenderer != null) isolatedWebRenderer.hide();
                                                  if (playlistScheduler != null) {
                                                      playlistScheduler.onIsolatedRendererFailed(slideId, reason);
                                                  }
                                              }
                                          }, telemetryManager, mediaDownloadManager);
                                  }
                                  String base = getServerUrl();
                                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                                    // Per-asset WebView policy: safe default reproduces the old
                                    // applyWebViewProfile(slideType) two-bucket behavior exactly;
                                    // future per-content overrides can be layered on here.
                                    WebViewPolicy policy = WebViewPolicy.forSlideType(s.type);
                                    // slideId (playlist item id), not contentId, must be the query param
                                    // consumed by /tv/render -- Digipal ready()/error()/heartbeat() calls
                                    // are keyed by slideId so a superseded slide's stale callbacks can be
                                    // told apart from the current one (see stale-callback guards in
                                    // PlaylistScheduler + RenderBridge). Using contentId here caused
                                    // ready/error callbacks to always be ignored as stale whenever two
                                    // playlist entries reference the same content.
                                    String url = base + "/tv/render/"
                                        + android.net.Uri.encode(code)
                                        + "/" + s.contentId
                                        + "?slideId=" + android.net.Uri.encode(s.slideId == null ? "" : s.slideId);
                                    if (policy.readyTimeoutMs > 0) {
                                        url += "&readyTimeoutMs=" + policy.readyTimeoutMs;
                                    }
                                    if (playlistScheduler != null) playlistScheduler.setLastWebViewPolicy(policy.name);
                                    isolatedWebRenderer.prepare(s.slideId, url, policy);
                              } catch (Throwable ignored) {
                                  if (playlistScheduler != null) {
                                      playlistScheduler.onIsolatedRendererFailed(s.slideId, "activate_exception");
                                  }
                              }
                          });
                      }
                      @Override public void schedulerDeactivateIsolatedRenderer() {
                          runOnUiThread(() -> {
                              try {
                                  if (isolatedWebRenderer != null) {
                                      isolatedWebRenderer.hide();
                                      // Low-memory WebView policy: on CRITICAL tier, reuse of a hidden
                                      // WebView is disallowed — destroy it immediately so no idle WebView
                                      // is ever held. NORMAL/LOW keep the instance alive for reuse.
                                      if (memoryBudgetManager != null
                                              && memoryBudgetManager.getCurrentTier() == MemoryBudgetManager.Tier.CRITICAL) {
                                          isolatedWebRenderer.destroy();
                                          isolatedWebRenderer = null;
                                      }
                                  }
                              } catch (Throwable ignored) {}
                          });
                      }
                      @Override public void schedulerStopVideo() {
                          runOnUiThread(() -> {
                              try {
                                  // Stop video directly — no WebView hop
                                  if (videoReadyHandler != null && videoReadyRunnable != null) {
                                      videoReadyHandler.removeCallbacks(videoReadyRunnable);
                                      videoReadyHandler = null; videoReadyRunnable = null;
                                  }
                                  if (nativeVideoListener != null) {
                                      if (exoPlayer != null) exoPlayer.removeListener(nativeVideoListener);
                                      nativeVideoListener = null;
                                  }
                                  releaseVideoPlayer(exoPlayer); exoPlayer = null;
                                  if (pendingOldPlayer != null) { releaseVideoPlayer(pendingOldPlayer); pendingOldPlayer = null; }
                                  hideNativeVideoSurfaces();
                              } catch (Throwable ignored) {}
                          });
                      }
                      @Override public void schedulerHideImage() {
                          runOnUiThread(() -> {
                              try {
                                  // Hide image views directly — no WebView hop
                                  com.bumptech.glide.Glide.with(MainActivity.this).clear(nativeImageView);
                                  com.bumptech.glide.Glide.with(MainActivity.this).clear(nativeImageViewB);
                                  nativeImageView.setVisibility(View.INVISIBLE);
                                  nativeImageViewB.setVisibility(View.INVISIBLE);
                                  preloadedImageUrl = null; preloadImageReady = false;
                              } catch (Throwable ignored) {}
                          });
                      }
                      @Override public void schedulerOnStateChanged(PlaylistScheduler.State state, String slideId) {
                          android.util.Log.d("DigipalScheduler", "[state] " + state + " slide=" + slideId);
                          if (telemetryManager != null) {
                              telemetryManager.setCurrentSlide(
                                  String.valueOf(playlistScheduler.getActiveRevisionId()),
                                  slideId, state.name());
                          }
                          if (assetCacheManager != null) {
                              if (state == PlaylistScheduler.State.PLAYING) {
                                  assetCacheManager.setMaxConcurrency(1);
                              } else if (state == PlaylistScheduler.State.IDLE
                                      || state == PlaylistScheduler.State.RECOVERING_RENDERER) {
                                  assetCacheManager.setMaxConcurrency(3);
                              }
                          }
                          // Trim Glide bitmap cache on each slide advance — releases off-screen decoded bitmaps
                          if (state == PlaylistScheduler.State.PLAYING) {
                              try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN); } catch (Throwable ignored) {}
                          }
                          if (healthMonitor != null) healthMonitor.onSchedulerStateChanged(state);
                          // Fix: report advance + update stall-state for all slide types
                          if (supRef[0] != null) {
                              supRef[0].setSchedulerState(state);
                              if (state == PlaylistScheduler.State.PLAYING) supRef[0].reportSchedulerAdvance();
                          }
                      }
                  },
                  playlistRepository, telemetryManager,
                  getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
              );

              // Codex fix 7: flush any playlist JSON that arrived (from the WebView
              // bridge) before PlaylistScheduler was constructed, instead of relying
              // on the removed legacy NativePlaylistManager fallback path.
              if (pendingNativePlaylistJson != null) {
                  playlistScheduler.setPlaylist(pendingNativePlaylistJson);
                  android.util.Log.i("DigipalNative", "[nativeLoop] flushed buffered playlist → PlaylistScheduler");
                  pendingNativePlaylistJson = null;
              }

              assetCacheManager = new AssetCacheManager(this, playlistRepository);

              // ── Local-first asset delivery ─────────────────────────────────────────
              // Populate localPathCache on every successful download so PlaylistScheduler
              // can serve file:// URIs instead of expiring GCS signed URLs.
              assetCacheManager.setOnAssetReadyListener((url, localPath) ->
                  localPathCache.put(url, localPath));

              // Wire AssetResolver to PlaylistScheduler — ConcurrentHashMap lookup is
              // safe to call on the main thread from showCurrent().
              playlistScheduler.setAssetResolver(url -> localPathCache.get(url));

              // Wire MemoryBudgetManager so baseline renderer diagnostics telemetry
              // (slide_shown/slide_ready/renderer_timeout/slide_failed) can report the
              // active memory tier alongside renderer kind and ready latency.
              playlistScheduler.setMemoryBudgetManager(memoryBudgetManager);

              // Pre-populate from Room: assets downloaded in previous sessions are
              // immediately available for local-first playback after a reboot.
              final PlaylistRepository _repo = playlistRepository;
              new Thread(() -> {
                  try {
                      java.util.List<PlaylistDatabase.AssetEntity> ready =
                          _repo.getDb().assetDao().findAllReady();
                      for (PlaylistDatabase.AssetEntity a : ready) {
                          if (a.url != null && !a.url.isEmpty()
                                  && a.localPath != null && !a.localPath.isEmpty()) {
                              localPathCache.put(a.url, a.localPath);
                          }
                      }
                      android.util.Log.d("DigipalAsset",
                          "[local_first] pre-loaded " + ready.size() + " READY assets");
                  } catch (Exception e) {
                      android.util.Log.w("DigipalAsset",
                          "localPathCache warm-up failed: " + e.getMessage());
                  }
              }, "LocalPathCacheWarmup").start();

              pdfPrerenderer    = new PdfPrerenderer(this, playlistRepository);
              playlistRepository.setPdfPrerenderer(pdfPrerenderer); // task #1891: enables native IMAGE expansion of WEBVIEW_PDF slides

              reliabilitySupervisor = new ReliabilitySupervisor(
                  this,
                  new ReliabilitySupervisor.RecoveryDelegate() {
                      @Override public void softRecover(String reason) {
                          android.util.Log.i("DigipalReliability", "[soft] " + reason);
                      }
                      @Override public void mediumRecover(String reason) {
                          android.util.Log.w("DigipalReliability", "[medium] " + reason);
                          try { io.sentry.Sentry.captureMessage("Player medium recover: " + reason, io.sentry.SentryLevel.WARNING); } catch (Throwable _sbc) {}
                          // Release all media renderers before re-booting — prevents ExoPlayer leaks on long sessions
                          runOnUiThread(() -> releaseAllRenderers());
                          if (playlistScheduler != null) playlistScheduler.boot();
                      }
                      @Override public void hardRecover(String reason) {
                          android.util.Log.e("DigipalReliability", "[hard] " + reason);
                          AppRecoverManager.scheduleRecovery(getApplicationContext());
                      }
                  },
                  telemetryManager
              );
              supRef[0] = reliabilitySupervisor;
                reliabilitySupervisor.setRecoveryCoordinator(recoveryCoordinator);
                reliabilitySupervisor.setMemoryBudgetManager(memoryBudgetManager);
                reliabilitySupervisor.setPlaylistRepository(playlistRepository);
                reliabilitySupervisor.startExternallyClocked();

              // Hand heartbeat stale check to HealthMonitor; it drives reliability checks too.
              stopHeartbeatWatchdog();
              healthMonitor = new HealthMonitor(
                  telemetryManager, reliabilitySupervisor,
                  () -> isNetworkConnectedForWatchdog(),
                  () -> runOnUiThread(() -> { try { forcePlayerReload(); } catch (Throwable ignored) {} })
              );
              healthMonitor.start();

              // ── Sentry crash & ANR reporting ─────────────────────────────────────────
              if (!BuildConfig.SENTRY_DSN.isEmpty()) {
                  try {
                      io.sentry.android.core.SentryAndroid.init(this, options -> {
                          options.setDsn(BuildConfig.SENTRY_DSN);
                          options.setRelease(BuildConfig.VERSION_NAME);
                          options.setEnvironment("production");
                          options.setTracesSampleRate(0.02); // 2% perf tracing — negligible overhead
                          options.setAttachStacktrace(true); // stack trace on captureMessage() calls
                      });
                      // Permanent session tags — set once, appear on every event/breadcrumb
                      io.sentry.Sentry.configureScope(scope -> {
                          scope.setTag("build_flavor",  BuildConfig.BUILD_FLAVOR);
                          scope.setTag("app_version",   BuildConfig.VERSION_NAME);
                          scope.setTag("git_sha",       BuildConfig.GIT_SHA);
                          scope.setTag("device_model",  android.os.Build.MODEL);
                          scope.setTag("android_sdk",   String.valueOf(android.os.Build.VERSION.SDK_INT));
                      });
                      // App start / restart breadcrumb
                      io.sentry.Breadcrumb _restartBc = new io.sentry.Breadcrumb("Player process started");
                      _restartBc.setCategory("lifecycle");
                      _restartBc.setType("info");
                      _restartBc.setLevel(io.sentry.SentryLevel.INFO);
                      _restartBc.setData("version",    BuildConfig.VERSION_NAME);
                      _restartBc.setData("git_sha",    BuildConfig.GIT_SHA);
                      _restartBc.setData("build_flavor", BuildConfig.BUILD_FLAVOR);
                      io.sentry.Sentry.addBreadcrumb(_restartBc);
                  } catch (Throwable ignored) {}
              }

              // Boot restore — play last ACTIVE revision from Room without network
              playlistScheduler.boot();
              android.util.Log.i("DigipalNative", "[v3.11.0] initNativeComponents complete");
          } catch (Throwable e) {
              android.util.Log.e("DigipalNative", "initNativeComponents error: " + e.getMessage());
          }
      }


    

      private void scheduleAppRelaunch(long delayMs) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastRelaunchScheduleElapsedMs < RELAUNCH_DEBOUNCE_MS) {
                Log.w("Digipal", "Skipping duplicate relaunch request");
                return;
            }
            lastRelaunchScheduleElapsedMs = now;

            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(this, 1002, intent, flags);
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager == null) return;

            long earliest = now + delayMs;
            alarmManager.setWindow(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    earliest,
                    5_000L,
                    pendingIntent);
        } catch (Throwable t) {
            Log.e("Digipal", "scheduleAppRelaunch failed", t);
        }
    }

    private boolean isAllowedMainWebViewUrl(String rawUrl) {
        if (rawUrl == null) return false;
        if (rawUrl.startsWith("about:blank")) return true;
        try {
            android.net.Uri uri = android.net.Uri.parse(rawUrl);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if ("appassets.androidplatform.net".equalsIgnoreCase(host)) return true;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            android.net.Uri server = android.net.Uri.parse(getServerUrl());
            String serverHost = server.getHost();
            return serverHost != null && host != null && serverHost.equalsIgnoreCase(host);
        } catch (Throwable t) {
            return false;
        }
    }

    private void notifyNativeVideoReady(String contentId) {
        if (webView == null) return;
        String safeContentId = org.json.JSONObject.quote(contentId == null ? "" : contentId);
        webView.evaluateJavascript(
                "try{var _f=window['__digipalNativeVideoReady_'+"
                        + safeContentId
                        + "];if(typeof _f==='function')_f();"
                        + "else if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady();"
                        + "}catch(e){}",
                null);
    }

    private String getOrCreateInstallId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String id = prefs.getString("install_id", null);
        if (id == null || id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("install_id", id).apply();
        }
        return id;
    }

    private boolean isAutoRelaunchEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_RELAUNCH, false);
    }

    private String getServerUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(KEY_SERVER_URL, null);
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }
        return BuildConfig.SERVER_URL;
    }

    // Fix 4: ExoPlayer stall watchdog — detects and recovers from position-stuck video after signed URL refresh.
    private void startStallWatchdog() {
        if (stallRunnable != null) { stallHandler.removeCallbacks(stallRunnable); stallRunnable = null; }
        stallLastPositionMs = -1L;
        stallLastCheckMs = System.currentTimeMillis();
        stallRunnable = new Runnable() {
            @Override public void run() {
                try {
                    final androidx.media3.exoplayer.ExoPlayer ep = exoPlayer;
                    if (ep == null || !ep.getPlayWhenReady()) { stallHandler.postDelayed(this, STALL_CHECK_MS); return; }
                    if (ep.getPlaybackState() == androidx.media3.common.Player.STATE_READY) {
                        long pos = ep.getCurrentPosition();
                        long now = System.currentTimeMillis();
                        if (stallLastPositionMs >= 0 && pos == stallLastPositionMs) {
                            if ((now - stallLastCheckMs) >= STALL_THRESHOLD_MS) {
                                android.util.Log.w("DigipalNative", "[stallWatchdog] stalled at " + pos + "ms — seeking forward");
                                try { ep.seekTo(pos + 500); } catch (Throwable ignored2) {}
                                stallLastPositionMs = -1L; stallLastCheckMs = now;
                            }
                        } else { stallLastPositionMs = pos; stallLastCheckMs = now; }
                    }
                } catch (Throwable ignored) {}
                stallHandler.postDelayed(this, STALL_CHECK_MS);
            }
        };
        stallHandler.postDelayed(stallRunnable, STALL_CHECK_MS);
    }
    private void stopStallWatchdog() {
        if (stallRunnable != null) { stallHandler.removeCallbacks(stallRunnable); stallRunnable = null; }
        stallLastPositionMs = -1L;
    }

    // Fix 2: start a 5s repeating check; if STATE_BUFFERING and bufferedPosition unchanged for 3 ticks (15s), signal error.
    private void startBufferWatchdog(String slideId) {
        stopBufferWatchdog();
        bufWatchdogSlideId        = slideId;
        bufWatchdogLastBufferedMs = -1L;
        bufWatchdogStallChecks    = 0;
        bufWatchdogRunnable = new Runnable() {
            @Override public void run() {
                try {
                    final androidx.media3.exoplayer.ExoPlayer ep = exoPlayer;
                    if (ep == null) return; // player released
                    if (ep.getPlaybackState() == androidx.media3.common.Player.STATE_BUFFERING) {
                        long buf = ep.getBufferedPosition();
                        if (bufWatchdogLastBufferedMs < 0 || buf > bufWatchdogLastBufferedMs) {
                            bufWatchdogLastBufferedMs = buf;
                            bufWatchdogStallChecks = 0; // progress — reset counter
                        } else {
                            bufWatchdogStallChecks++;
                            android.util.Log.w("DigipalBufWatchdog",
                                "[bufStall] tick=" + bufWatchdogStallChecks + "/" + BUF_WATCHDOG_STALL_TICKS
                                + " bufferedMs=" + buf + " slide=" + bufWatchdogSlideId);
                            if (bufWatchdogStallChecks >= BUF_WATCHDOG_STALL_TICKS) {
                                android.util.Log.e("DigipalBufWatchdog",
                                    "[bufStall] 15s buffering stall — signalling error for slide=" + bufWatchdogSlideId);
                                stopBufferWatchdog();
                                if (playlistScheduler != null) playlistScheduler.onRendererError(bufWatchdogSlideId, "buffering_stall");
                                return;
                            }
                        }
                    } else {
                        // Not buffering — reset stall counter; watchdog keeps running until cancelled
                        bufWatchdogStallChecks = 0;
                    }
                } catch (Throwable ignored) {}
                bufWatchdogHandler.postDelayed(this, BUF_WATCHDOG_INTERVAL_MS);
            }
        };
        bufWatchdogHandler.postDelayed(bufWatchdogRunnable, BUF_WATCHDOG_INTERVAL_MS);
    }

    private void stopBufferWatchdog() {
        if (bufWatchdogRunnable != null) { bufWatchdogHandler.removeCallbacks(bufWatchdogRunnable); bufWatchdogRunnable = null; }
        bufWatchdogLastBufferedMs = -1L;
        bufWatchdogStallChecks    = 0;
    }

    private void loadPlayerUrl(String baseUrl) { loadPlayerUrl(baseUrl, false); }

    private void loadPlayerUrl(String baseUrl, boolean freshReload) {
        if (baseUrl.startsWith("http://")) {
            try {
                String host = new java.net.URI(baseUrl).getHost();
                if (host == null || !UrlPolicy.isPrivateHost(host)) {
                    showError("Security Error", "HTTP connections are only allowed to local network servers. Use https:// for public servers.");
                    return;
                }
            } catch (Throwable e) {
                showError("Invalid URL", "Could not parse server address.");
                return;
            }
        }
        String playerUrl = baseUrl;
        if (!playerUrl.endsWith("/")) {
            playerUrl += "/";
        }
        playerUrl += "player?platform=android_tv";
        if (freshReload) playerUrl += "&freshReload=1";
        currentPlayerUrl = playerUrl;
        webView.loadUrl(playerUrl);
    }

    /** One-shot listener: advance the scheduler when the video reaches its natural end.
     *  Uses STATE_ENDED (REPEAT_MODE_OFF) instead of DISCONTINUITY_REASON_AUTO_TRANSITION
     *  (REPEAT_MODE_ONE) so the video stops cleanly with no first-frame replay. */
      private void attachLoopAdvanceListener(androidx.media3.exoplayer.ExoPlayer player, String slideId) {
          if (player == null) return;
          player.addListener(new androidx.media3.common.Player.Listener() {
              @Override
              public void onPlaybackStateChanged(int playbackState) {
                  if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                      player.removeListener(this);
                      android.util.Log.i("VideoLoop", "[naturalEnd] slideId=" + slideId);
                      if (exoPlayer == player && playlistScheduler != null)
                          playlistScheduler.onSlideNaturalEnd(slideId);
                  }
              }
          });
      }

      private androidx.media3.exoplayer.ExoPlayer buildCachedExoPlayer() {
          androidx.media3.datasource.DefaultHttpDataSource.Factory httpFactory =
              new androidx.media3.datasource.DefaultHttpDataSource.Factory();
          androidx.media3.datasource.DefaultDataSource.Factory upstreamFactory =
              new androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory);
          androidx.media3.datasource.cache.CacheDataSource.Factory cacheFactory =
              new androidx.media3.datasource.cache.CacheDataSource.Factory()
                  .setCache(videoCache)
                  .setUpstreamDataSourceFactory(upstreamFactory)
                  .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
          androidx.media3.exoplayer.DefaultLoadControl loadControl =
              new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                  .setBufferDurationsMs(
                      1500,
                      5000,
                      500,
                      1000)
                  .build();
          androidx.media3.exoplayer.DefaultRenderersFactory renderersFactory =
                new androidx.media3.exoplayer.DefaultRenderersFactory(this)
                    .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
            androidx.media3.exoplayer.ExoPlayer player =
                new androidx.media3.exoplayer.ExoPlayer.Builder(this, renderersFactory)
                    .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory))
                    .setLoadControl(loadControl)
                    .build();
            // Cap decode resolution on low-memory devices (Fire TV Stick Lite/4K) to prevent OOM stalls on 4K content
            int memClass = ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryClass();
            if (memClass < 256) {
                player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters().buildUpon()
                        .setMaxVideoSize(1920, 1080)
                        .build());
            }
            return player;
        }

      private void showError(String title, String message) {
        errorContainer.removeAllViews();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.parseColor("#ef4444"));
        titleView.setTextSize(28);
        titleView.setGravity(android.view.Gravity.CENTER);
        layout.addView(titleView);

        TextView msgView = new TextView(this);
        msgView.setText(message);
        msgView.setTextColor(Color.parseColor("#94a3b8"));
        msgView.setTextSize(16);
        msgView.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 24;
        msgView.setLayoutParams(params);
        layout.addView(msgView);

        errorContainer.addView(layout, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        errorContainer.setVisibility(View.VISIBLE);
    }

    private void retryConnection() {
        webView.postDelayed(() -> {
            String serverUrl = getServerUrl();
            loadPlayerUrl(serverUrl);
        }, 5000);
    }

    /** If the app booted from the local versioned player shell and it just failed to render,
     *  roll back to the last-known-good local snapshot instead of retrying the same broken
     *  version forever (local player shell hardening task). Returns true if a rollback load
     *  was issued (caller should skip its normal error/retry handling). */
    private boolean maybeRollbackLocalShell() {
        if (!bootedFromLocalShell || playerShellManager == null) return false;
        bootedFromLocalShell = false;
        try {
            String rollbackUrl = playerShellManager.rollbackToLastGood(getServerUrl());
            if (rollbackUrl != null) {
                hasHttpError = false;
                webView.postDelayed(() -> {
                    currentPlayerUrl = rollbackUrl;
                    webView.loadUrl(rollbackUrl);
                }, 300);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Fires MOUNT_WATCHDOG_MS after a page finishes loading with no HTTP error, if the JS side
     *  never called Android.reportAppMounted() by then. Means the shell rendered blank — most
     *  commonly a locally cached shell (local player shell hardening task) that is missing a
     *  referenced asset. Recover the same way a hard load failure recovers: roll back to the
     *  last known-good local shell if one exists, otherwise drop the broken "current" pointer so
     *  the next attempt loads straight from the network instead of repeating the same failure. */
    private void onMountWatchdogTimeout() {
        if (appMountConfirmed) return;
        Log.w("Digipal", "[shell] app did not report mount within " + MOUNT_WATCHDOG_MS
                + "ms of onPageFinished — treating shell as broken");
        if (maybeRollbackLocalShell()) return;
        if (playerShellManager != null) {
            try { playerShellManager.invalidateCurrent(); } catch (Throwable ignored) {}
        }
        bootedFromLocalShell = false;
        hasHttpError = false;
        webView.postDelayed(() -> loadPlayerUrl(getServerUrl()), 300);
    }

    // ---- Crash resilience: renderer recovery, memory pressure, ANR watchdog ----

    private final long[] renderGoneTimestamps = new long[3];
    private int renderGoneIdx = 0;
    private volatile boolean webViewRecoveryInProgress = false;
    private long lastMemoryReloadMs = 0L;
    // --- Self-heal watchdog (heartbeat-based WebView reload) ----------------
    // The web player calls Android.heartbeat() from its JS event loop every few
    // seconds. This main-thread Handler keeps ticking even when the WebView JS
    // freezes (e.g. page wedged "Offline" after a brief server loss), so if
    // heartbeats stop while the network is still up we reload the WebView once
    // (with back-off) to un-wedge it. Complements WatchdogService (dead process)
    // and the ANR watchdog (hung UI thread).
    private volatile long lastHeartbeatMs = 0;
    private volatile boolean heartbeatReceived = false;
    private long lastWatchdogReloadMs = 0;
    private android.os.Handler heartbeatWatchdogHandler;
    private Runnable heartbeatWatchdogRunnable;
    private static final long HEARTBEAT_STALE_MS = 90000;
    private static final long WATCHDOG_CHECK_INTERVAL_MS = 15000;
    private static final long WATCHDOG_RELOAD_BACKOFF_MS = 120000;
    private Thread anrWatchdogThread;
    private volatile boolean anrWatchdogRunning = false;
    private final android.os.Handler anrMainHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Called when the WebView renderer process is killed (OOM or GPU driver crash).
     * Rebuilds a fresh WebView in place and reloads the player so the screen
     * recovers on its own instead of dropping to the launcher. Falls back to a
     * full activity relaunch if the renderer keeps dying (crash loop).
     */
    private void recoverFromRenderProcessGone(WebView deadView) {
        if (webViewRecoveryInProgress) {
            android.util.Log.d("DigipalRecovery", "[recoverFromRenderProcessGone] skipping re-entry");
            return;
        }
        webViewRecoveryInProgress = true;
        // Report WebView crash to RecoveryCoordinator for escalation tracking
        if (recoveryCoordinator != null) {
            recoveryCoordinator.reportWebViewCrash("render_process_gone",
                    memoryBudgetManager != null ? memoryBudgetManager.getCurrentTier() : null);
        }
        cancelStablePlaybackTimer();
        long now = System.currentTimeMillis();
        renderGoneTimestamps[renderGoneIdx % renderGoneTimestamps.length] = now;
        renderGoneIdx++;
        if (renderGoneIdx >= renderGoneTimestamps.length) {
            long oldest = Long.MAX_VALUE;
            for (long t : renderGoneTimestamps) { if (t > 0 && t < oldest) oldest = t; }
            if (now - oldest < 60_000L) {
                // Renderer is crash-looping — relaunch the whole activity with backoff.
                webViewRecoveryInProgress = false;
                if (isAutoRelaunchEnabled()) scheduleAppRelaunch(5000);
                isUserClosing = true;
                finish();
                return;
            }
        }
        try {
            if (deadView != null && rootLayout != null) {
                try { rootLayout.removeView(deadView); } catch (Throwable ignored) {}
            }
            try { if (deadView != null) deadView.destroy(); } catch (Throwable ignored) {}

            webView = new WebView(this);
            setupWebView();
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
            if (rootLayout != null) {
                rootLayout.addView(webView, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            }
            if (mediaDownloadManager != null) mediaDownloadManager.setWebView(webView);
            try { webView.evaluateJavascript(
                "window.__digipalRecoveryReason='webview_renderer_gone';"
                +"window.dispatchEvent(new CustomEvent('android-recovery',"
                +"{detail:{reason:'webview_renderer_gone'}}));", null);
            } catch (Throwable ignored) {}
            // Task 1952: the old WebView was destroyed while process-wide timers
            // were paused by the native loop. resumeTimers() or the rebuilt
            // WebView boots with frozen JS and can never heartbeat or poll.
            try {
                if (heartbeatRunnable != null) {
                    heartbeatHandler.removeCallbacks(heartbeatRunnable);
                    heartbeatRunnable = null;
                }
            } catch (Throwable ignored) {}
            try { webView.resumeTimers(); } catch (Throwable ignored) {}
            loadPlayerUrl(getServerUrl(), true);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> webViewRecoveryInProgress = false, 8000);
        } catch (Throwable e) {
            webViewRecoveryInProgress = false;
            if (isAutoRelaunchEnabled()) scheduleAppRelaunch(3000);
            isUserClosing = true;
            finish();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        handleMemoryPressure(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        handleMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
    }

    /**
     * React to Android memory-pressure callbacks. On moderate pressure we ask the
     * web player to drop caches; on critical pressure we clear the WebView cache
     * and schedule a clean reload (debounced) so the OS does not kill us first.
     */
    private void handleMemoryPressure(int level) {
        // Low-memory WebView policy backstop: Android's own low-memory signal (not just
        // MemoryBudgetManager's 5s poll) should immediately kill an idle isolated WebView
        // and trim Glide, regardless of the current tier reading.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            try {
                if (isolatedWebRenderer != null && !isolatedWebRenderer.isShowing()) {
                    isolatedWebRenderer.destroy();
                    isolatedWebRenderer = null;
                    android.util.Log.w("DigipalMemory", "[onTrimMemory] idle isolated WebView destroyed");
                }
                if (preloadPlayer != null) {
                    try { preloadPlayer.release(); } catch (Throwable ignored) {}
                    preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false;
                }
                com.bumptech.glide.Glide.get(MainActivity.this).clearMemory();
            } catch (Throwable ignored) {}
        }
        try {
            if (webView == null) return;
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                    || level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('android-memory-pressure',{detail:{level:'critical'}}));",
                    null);
                try { webView.clearCache(false); } catch (Throwable ignored) {}
                long now = System.currentTimeMillis();
                if (now - lastMemoryReloadMs > 120_000L) {
                    lastMemoryReloadMs = now;
                    // Give the player a few seconds to recover gracefully, then
                    // reload natively as a safety net if we are still alive.
                    webView.postDelayed(() -> {
                        try { webView.evaluateJavascript(
                            "window.__digipalRecoveryReason='memory_critical_reload';", null);
                        } catch (Throwable ignored) {}
                        try { loadPlayerUrl(getServerUrl()); } catch (Throwable ignored) {}
                    }, 4000);
                }
            } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('android-memory-pressure',{detail:{level:'moderate'}}));",
                    null);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Lightweight ANR watchdog: posts a heartbeat to the main-thread Handler every
     * few seconds. If the UI thread fails to process several consecutive
     * heartbeats it is hung, so we relaunch and kill the stuck process.
     */
    private void startAnrWatchdog() {
        if (anrWatchdogThread != null) return;
        anrWatchdogRunning = true;
        anrWatchdogThread = new Thread(() -> {
            int missed = 0;
            while (anrWatchdogRunning) {
                final java.util.concurrent.atomic.AtomicBoolean ticked =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
                try { anrMainHandler.post(() -> ticked.set(true)); } catch (Throwable ignored) {}
                try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
                if (ticked.get()) {
                    missed = 0;
                } else {
                    missed++;
                    if (missed >= 3 && activityVisible && isAutoRelaunchEnabled()) {
                        // Main thread hung ~15s â schedule relaunch then kill the stuck process.
                        scheduleAppRelaunch(2000);
                        android.os.Process.killProcess(android.os.Process.myPid());
                        return;
                    }
                }
            }
        }, "digipal-anr-watchdog");
        anrWatchdogThread.setDaemon(true);
        anrWatchdogThread.start();
    }

    private void stopAnrWatchdog() {
        anrWatchdogRunning = false;
        if (anrWatchdogThread != null) {
            try { anrWatchdogThread.interrupt(); } catch (Throwable ignored) {}
            anrWatchdogThread = null;
        }
    }

    // Reloads the WebView when the web player stops heart-beating (frozen JS)
    // while the device still has a network. Armed only after the first
    // heartbeat, so the setup screen and cold start are never disturbed.
    private void startHeartbeatWatchdog() {
        heartbeatWatchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        heartbeatWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (heartbeatReceived) {
                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeatMs > HEARTBEAT_STALE_MS
                                && isNetworkConnectedForWatchdog()
                                && now - lastWatchdogReloadMs > WATCHDOG_RELOAD_BACKOFF_MS) {
                            lastWatchdogReloadMs = now;
                            lastHeartbeatMs = now; // avoid immediate re-fire
                            android.util.Log.w("DigipalWatchdog",
                                "Player heartbeat stale with network up - reloading WebView");
                            forcePlayerReload();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (heartbeatWatchdogHandler != null) {
                        heartbeatWatchdogHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS);
                    }
                }
            }
        };
        heartbeatWatchdogHandler.postDelayed(heartbeatWatchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS);
    }

    private void stopHeartbeatWatchdog() {
        if (heartbeatWatchdogHandler != null) {
            heartbeatWatchdogHandler.removeCallbacksAndMessages(null);
            heartbeatWatchdogHandler = null;
        }
    }

    private void forcePlayerReload() {
        try { io.sentry.Breadcrumb _frBc = new io.sentry.Breadcrumb("Player page forced reload"); _frBc.setCategory("lifecycle"); _frBc.setType("info"); _frBc.setLevel(io.sentry.SentryLevel.WARNING); io.sentry.Sentry.addBreadcrumb(_frBc); } catch (Throwable _sbc) {}
        runOnUiThread(() -> {
            try {
                if (errorContainer != null) errorContainer.setVisibility(View.GONE);
                // Task 1952: a reload fired while the native loop owns the screen
                // must undo the dormant state, or the revived page boots with
                // frozen timers behind an invisible WebView and loops forever.
                if (heartbeatRunnable != null) {
                    heartbeatHandler.removeCallbacks(heartbeatRunnable);
                    heartbeatRunnable = null;
                }
                if (webView != null) {
                    try { webView.resumeTimers(); } catch (Throwable ignored) {}
                    try { webView.setAlpha(1f); webView.setVisibility(View.VISIBLE); } catch (Throwable ignored) {}
                }
                String url = webView != null ? webView.getUrl() : null;
                if (webView != null && url != null && !url.startsWith("about:")) {
                    String freshUrl = url;
                    try {
                        if (!url.contains("freshReload=")) {
                            freshUrl = url + (url.contains("?") ? "&" : "?") + "freshReload=1";
                        }
                    } catch (Throwable ignored) {}
                    webView.loadUrl(freshUrl);
                } else {
                    loadPlayerUrl(getServerUrl(), true);
                }
            } catch (Exception e) {
                try { loadPlayerUrl(getServerUrl(), true); } catch (Exception ignored) {}
            }
        });
    }

    private boolean isNetworkConnectedForWatchdog() {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Exception e) {
            return true; // fail-open: do not suppress self-heal if network is unknown
        }
    }

    /** Arm the 3-minute stable playback timer. Resets on each successful renderer-ready event.
       *  When it fires cleanly it calls AppRecoverManager.onCleanStart() to reset the crash counter. */
      /**
       * P0 Fix 5: refreshes the native debug overlay TextView with the same fields
       * exposed via getRendererStatus() (rendererKind, memoryTier, webViewPolicy,
       * shellSource, slide/content ids, last error). Runs on a 2s tick while the
       * overlay is enabled; no-ops silently if the overlay view was never created
       * (e.g. the WebView-unavailable early-return path in onCreate).
       */
      private void updateNativeDebugOverlayText() {
          if (nativeDebugOverlay == null || !nativeDebugOverlayEnabled) return;
          try {
              StringBuilder sb = new StringBuilder();
              sb.append("appVersion: ").append(BuildConfig.VERSION_NAME)
                  .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
              if (playlistScheduler != null) {
                  sb.append("renderer: ").append(playlistScheduler.getCurrentRendererKind()).append("\n");
                  sb.append("memoryTier: ").append(playlistScheduler.getMemoryTierName()).append("\n");
                  sb.append("webViewPolicy: ").append(playlistScheduler.getLastWebViewPolicyName()).append("\n");
                  sb.append("shellSource: ").append(playlistScheduler.getShellSourceName()).append("\n");
                  sb.append("slideId: ").append(playlistScheduler.getCurrentSlideId()).append("\n");
                  sb.append("contentId: ").append(playlistScheduler.getCurrentContentId()).append("\n");
                  sb.append("renderMode: ").append(playlistScheduler.getCurrentRenderMode()).append("\n");
                  sb.append("fallbackUsed: ").append(playlistScheduler.isLastFallbackUsed()).append("\n");
                  sb.append("retryCount: ").append(playlistScheduler.getRetryCountForSlide()).append("\n");
                  sb.append("consecutiveFailures: ").append(playlistScheduler.getConsecutiveFailures());
                  String lastErr = playlistScheduler.getLastErrorMessage();
                  if (lastErr != null && !lastErr.isEmpty()) {
                      sb.append("\nlastError: ").append(lastErr);
                  }
              } else {
                  sb.append("renderer: unknown (no scheduler)");
              }
              nativeDebugOverlay.setText(sb.toString());
          } catch (Throwable ignored) {}
      }

      private void armStablePlaybackTimer() {
          stablePlaybackHandler.removeCallbacks(stablePlaybackRunnable != null ? stablePlaybackRunnable : () -> {});
          stablePlaybackRunnable = () -> {
              android.util.Log.i("DigipalRecovery", "[stable] 3-min clean window — resetting crash counter");
              AppRecoverManager.onCleanStart(getApplicationContext());
          };
          stablePlaybackHandler.postDelayed(stablePlaybackRunnable, STABLE_PLAYBACK_MS);
      }

      /** Cancel the stable playback timer (call on any renderer error). */
      private void cancelStablePlaybackTimer() {
          if (stablePlaybackRunnable != null) {
              stablePlaybackHandler.removeCallbacks(stablePlaybackRunnable);
          }
      }

      private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    private void openSetupScreen() {
        isUserClosing = true;
        Intent intent = new Intent(this, ServerSetupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("show_settings", true);
        startActivity(intent);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
          if (keyCode == KeyEvent.KEYCODE_HOME
                  || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                  || keyCode == KeyEvent.KEYCODE_MENU) {
              return true;
          }
          if (keyCode == KeyEvent.KEYCODE_BACK) {
              // Back is a no-op in kiosk/signage mode â swallow to prevent accidental navigation.
              return true;
          }
          if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                  || keyCode == KeyEvent.KEYCODE_ENTER
                  || keyCode == KeyEvent.KEYCODE_BUTTON_A
                  || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
              long now = System.currentTimeMillis();
              if (now - dpadFirstPressMs > 5_000L) { dpadPressCount = 0; dpadFirstPressMs = now; }
              dpadPressCount++;
              if (dpadPressCount == 5) {
                  // 5 DPAD presses: toggle video renderer (TextureView ↔ SurfaceView) + restart pipeline
                  dpadPressCount = 0;
                  android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                  String cur = prefs.getString(PREF_VIDEO_RENDERER, "texture" /* TextureView for all Android TV — avoids SurfaceView z-order issues on Android box */);
                  String next = "texture".equals(cur) ? "surface" : "texture";
                  prefs.edit().putString(PREF_VIDEO_RENDERER, next).apply();
                  android.util.Log.i("DigipalVideo", "[DPAD5] renderer toggled: " + cur + " -> " + next);
                  runOnUiThread(() -> {
                      resetVideoRenderer();
                      // Brief toast-style feedback via diagnostics overlay
                      if (diagnosticsOverlay != null) hideDiagnosticsOverlay();
                      showDiagnosticsOverlay();
                  });
                  return true;
              }
              if (dpadPressCount >= 7) {
                  dpadPressCount = 0;
                  if (diagnosticsOverlay != null) hideDiagnosticsOverlay();
                  else showDiagnosticsOverlay();
                  return true;
              }
          }
          if (webView != null) webView.dispatchKeyEvent(event);
          return true;
      }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        hideSystemUI();
        if (webView != null) webView.onResume();
        // Resume PlaylistScheduler slide timer with remaining duration.
        if (playlistScheduler != null) playlistScheduler.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pause PlaylistScheduler so advance() does not fire while backgrounded.
        if (playlistScheduler != null) playlistScheduler.pause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityVisible = false;
    }


      /**
       * Releases all native media renderers (ExoPlayer instances + Glide requests).
       * Call from onDestroy(), WebView crash recovery, and mediumRecover() to prevent
       * ExoPlayer SurfaceView leaks that cause OOM on long Fire TV sessions.
       */
      private void releaseAllRenderers() {
          try {
              stopStallWatchdog();
              stopBufferWatchdog();
              // Cancel pending first-frame ready timer
              if (videoReadyHandler != null && videoReadyRunnable != null) {
                  videoReadyHandler.removeCallbacks(videoReadyRunnable);
                  videoReadyHandler = null; videoReadyRunnable = null;
              }
              // Remove stale ExoPlayer listener before releasing
              if (nativeVideoListener != null) {
                  if (exoPlayer != null) {
                      try { exoPlayer.removeListener(nativeVideoListener); } catch (Throwable ignored) {}
                  }
                  nativeVideoListener = null;
              }
              // Release active ExoPlayer
              if (exoPlayer != null) {
                  try { exoPlayer.stop(); exoPlayer.release(); } catch (Throwable ignored) {}
                  exoPlayer = null;
              }
              // Release preload ExoPlayer (was missing from onDestroy — OOM source on long sessions)
              if (preloadPlayer != null) {
                  try { preloadPlayer.stop(); preloadPlayer.release(); } catch (Throwable ignored) {}
                  preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false;
              }
              // Release pending-old ExoPlayer (held during dual-buffer swap)
              if (pendingOldPlayer != null) {
                  try { pendingOldPlayer.stop(); pendingOldPlayer.release(); } catch (Throwable ignored) {}
                  pendingOldPlayer = null;
              }
              // Detach players from all video surfaces and hide everything
              hideNativeVideoSurfaces();
              // Cancel all pending Glide decodes (prevent callbacks on destroyed activity)
              if (nativeImageView != null) {
                  try { com.bumptech.glide.Glide.with(MainActivity.this).clear(nativeImageView); } catch (Throwable ignored) {}
                  nativeImageView.setVisibility(View.INVISIBLE);
              }
              if (nativeImageViewB != null) {
                  try { com.bumptech.glide.Glide.with(MainActivity.this).clear(nativeImageViewB); } catch (Throwable ignored) {}
                  nativeImageViewB.setVisibility(View.INVISIBLE);
              }
              preloadedImageUrl = null; preloadImageReady = false;
              android.util.Log.i("DigipalNative", "[releaseAllRenderers] complete");
          } catch (Throwable e) {
              android.util.Log.e("DigipalNative", "releaseAllRenderers error", e);
          }
      }

    @Override
    protected void onDestroy() {
        stopAnrWatchdog();
        stopHeartbeatWatchdog();
        if (memoryBudgetManager != null) { memoryBudgetManager.stop(); memoryBudgetManager = null; }
          if (healthMonitor != null) { healthMonitor.stop(); healthMonitor = null; }
        if (isAutoRelaunchEnabled() && !isUserClosing) {
            scheduleAppRelaunch(3000);
        }
        // AppRecoverManager (WorkManager) is reserved for ReliabilitySupervisor.hardRecover().
        // Using it alongside scheduleAppRelaunch creates a recovery race condition.
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        releaseAllRenderers(); // releases exoPlayer, preloadPlayer, pendingOldPlayer, Glide
        if (videoCache != null) { try { videoCache.release(); } catch (Throwable ignored) {} videoCache = null; }
        activityAlive = false;
        if (playlistScheduler   != null) { try { playlistScheduler.shutdown();      } catch (Throwable ignored) {} }
        if (assetCacheManager  != null) { try { assetCacheManager.shutdown();       } catch (Throwable ignored) {} }
        if (mediaDownloadManager != null) { try { mediaDownloadManager.shutdown();  } catch (Throwable ignored) {} }
        if (reliabilitySupervisor != null) { try { reliabilitySupervisor.stop();    } catch (Throwable ignored) {} }
        super.onDestroy();
    }
      @Override
      public boolean dispatchKeyEvent(KeyEvent event) {
          if (webView != null && event.getAction() == KeyEvent.ACTION_DOWN) {
              int k = event.getKeyCode();
              if (k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_HOME
                      && k != KeyEvent.KEYCODE_APP_SWITCH && k != KeyEvent.KEYCODE_MENU) {
                  webView.dispatchKeyEvent(event);
              }
          }
          return super.dispatchKeyEvent(event);
      }

      private void showDiagnosticsOverlay() {
            if (diagnosticsOverlay != null) return;
            try {
                android.widget.FrameLayout root = findViewById(android.R.id.content);

                String ip = "unavailable";
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    if (wm != null) ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
                } catch (Throwable ignored) {}
                long uptimeSec = android.os.SystemClock.elapsedRealtime() / 1000;
                String uptime = String.format(Locale.ROOT, "%dh %02dm %02ds", uptimeSec / 3600, (uptimeSec % 3600) / 60, uptimeSec % 60);
                String ver = "?";
                try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
                final String curRenderer = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_VIDEO_RENDERER, "texture" /* TextureView for all Android TV — avoids SurfaceView z-order issues on Android box */);

                // Container — vertical LinearLayout so we can stack info + renderer buttons
                android.widget.LinearLayout container = new android.widget.LinearLayout(this);
                container.setBackgroundColor(0xCC000000);
                container.setOrientation(android.widget.LinearLayout.VERTICAL);
                container.setGravity(android.view.Gravity.CENTER);
                container.setPadding(60, 60, 60, 60);

                // Diagnostics info text
                android.widget.TextView infoTv = new android.widget.TextView(this);
                infoTv.setTextColor(0xFFFFFFFF);
                infoTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
                infoTv.setGravity(android.view.Gravity.CENTER);
                infoTv.setText("DIGIPAL DIAGNOSTICS\n"
                    + "\nPackage: " + getPackageName()
                    + "\nVersion:  " + ver
                    + "\nIP:       " + ip
                    + "\nUptime:   " + uptime
                    + "\nDevice:   " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + "\n\nPress SELECT x7 to dismiss");
                container.addView(infoTv);

                // --- VIDEO RENDERER SETTINGS ROW ---
                android.widget.TextView rendLabel = new android.widget.TextView(this);
                rendLabel.setText("\n\nVIDEO RENDERER (persisted in SharedPreferences)");
                rendLabel.setTextColor(0xFFCCCCCC);
                rendLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                rendLabel.setGravity(android.view.Gravity.CENTER);
                container.addView(rendLabel);

                // Button row: TextureView | SurfaceView
                android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
                btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                btnRow.setGravity(android.view.Gravity.CENTER);
                btnRow.setPadding(0, 20, 0, 20);

                android.widget.Button btnTexture = new android.widget.Button(this);
                btnTexture.setText("TextureView" + ("texture".equals(curRenderer) ? " [ACTIVE]" : ""));
                btnTexture.setFocusable(true);
                btnTexture.setOnClickListener(v -> {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_VIDEO_RENDERER, "texture").apply();
                    android.util.Log.i("DigipalVideo", "[Settings] renderer set to texture");
                    runOnUiThread(() -> { resetVideoRenderer(); hideDiagnosticsOverlay(); showDiagnosticsOverlay(); });
                });

                android.widget.Button btnSurface = new android.widget.Button(this);
                btnSurface.setText("SurfaceView" + ("surface".equals(curRenderer) ? " [ACTIVE]" : ""));
                btnSurface.setFocusable(true);
                btnSurface.setOnClickListener(v -> {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_VIDEO_RENDERER, "surface").apply();
                    android.util.Log.i("DigipalVideo", "[Settings] renderer set to surface");
                    runOnUiThread(() -> { resetVideoRenderer(); hideDiagnosticsOverlay(); showDiagnosticsOverlay(); });
                });

                android.widget.LinearLayout.LayoutParams btnLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                btnLp.setMargins(20, 0, 20, 0);
                btnRow.addView(btnTexture, btnLp);
                btnRow.addView(btnSurface, btnLp);
                container.addView(btnRow);

                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
                root.addView(container, lp);
                diagnosticsOverlay = container;
                diagDismissHandler.postDelayed(this::hideDiagnosticsOverlay, 30_000L);
            } catch (Throwable e) {
                android.util.Log.e("Digipal", "showDiagnosticsOverlay failed", e);
            }
        }

        private void hideDiagnosticsOverlay() {
          diagDismissHandler.removeCallbacksAndMessages(null);
          if (diagnosticsOverlay != null) {
              try {
                  android.widget.FrameLayout root = findViewById(android.R.id.content);
                  root.removeView(diagnosticsOverlay);
              } catch (Throwable ignored) {}
              diagnosticsOverlay = null;
          }
      }
  
}

