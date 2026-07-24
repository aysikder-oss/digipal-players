package com.nexuscast.player;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import android.net.wifi.WifiManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import android.view.PixelCopy;
import java.io.ByteArrayOutputStream;
import android.os.Handler;
import android.os.Looper;
  import android.text.format.Formatter;
  public class MainActivity extends Activity {

    /** Set true in onResume, false in onStop â read by WatchdogService.inForeground(). */
    public static volatile boolean activityVisible = false;
    /** Set true in onCreate, false in onDestroy â used by WatchdogService to detect real crashes vs Home-press. */
    public static volatile boolean activityAlive = false;

    private WebView webView;
    private final android.os.Handler heartbeatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private long lastGcMs = 0;
    private FrameLayout rootLayout;
    private FrameLayout errorContainer;
    private static final String PREFS_NAME = "DigipalPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";
    private static final String KEY_CHECK_SEC = "relaunch_check_sec";
    private static final int PERMISSION_REQUEST_CAMERA = 1001;
    private boolean isUserClosing = false;
    private View customView;
    private FrameLayout customViewContainer;
    private HardwareManager hardwareManager;

    private static final java.util.regex.Pattern PRIVATE_IP_PATTERN = java.util.regex.Pattern.compile(
        "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}" +
        "|192\\.168\\.\\d{1,3}\\.\\d{1,3}" +
        "|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|localhost" +
        "|\\[::1\\])$"
    );
    private MediaDownloadManager mediaDownloadManager;
    private int dpadPressCount = 0;
    private long dpadFirstPressMs = 0L;
    private android.view.View diagnosticsOverlay = null;
    private android.os.Handler diagDismissHandler = new android.os.Handler(android.os.Looper.getMainLooper());
      private androidx.media3.ui.PlayerView nativeVideoView;
      private android.widget.ImageView nativeImageView;
      private androidx.media3.exoplayer.ExoPlayer exoPlayer;
      // ExoPlayer on-disk video cache (OptiSigns-style LRU, 2 GB max)
      private static androidx.media3.datasource.cache.SimpleCache videoCache;
      private static final long VIDEO_CACHE_SIZE = 50L * 1024 * 1024;  // 50 MB streaming buffer (full copies live in AssetCacheManager/Room — local playback bypasses this cache)
    // Handler/Runnable for first-frame video ready callback (or 8s safety timeout)
    private android.os.Handler videoReadyHandler;
    private Runnable videoReadyRunnable;
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
    private boolean activeVideoViewIsA = true;
    private boolean activeImageViewIsA = true;
    // Old player held alive during swap-wait so activeView stays visible; released after new frame confirmed
    private androidx.media3.exoplayer.ExoPlayer pendingOldPlayer;
    // Native-first rendering mode (OptiSigns-style OOM elimination on low-mem Fire TV)
    private boolean nativeFirstRendering = false;
    // Native content loop — drives video/image slides via NativePlaylistManager without WebView
    private volatile String pendingNativePlaylistJson; // Codex fix 7: buffered playlist JSON until PlaylistScheduler is constructed
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
          // WorkManager crash recovery: cancel pending recovery and reset crash counter on clean start
          AppRecoverManager.onCleanStart(this);
          // Install uncaught exception handler to record crash + schedule WorkManager recovery
          final Thread.UncaughtExceptionHandler _prevCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
          Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
              try { AppRecoverManager.scheduleRecovery(getApplicationContext()); } catch (Throwable ignored) {}
              try {
                  CrashCounter.recordCrash(getApplicationContext());
                  android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                  android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                  if (am != null) am.getMemoryInfo(mi);
                  String stack = android.util.Log.getStackTraceString(throwable);
                  if (stack.length() > 4000) stack = stack.substring(0, 4000);
                  long now = System.currentTimeMillis();
                  getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                      .putString("pending_crash_type", throwable.getClass().getSimpleName())
                      .putString("pending_crash_stack", stack)
                      .putLong("pending_crash_at", now)
                      .putInt("pending_crash_free_mb", (int)(mi.availMem / 1048576L))
                      .putInt("pending_crash_total_mb", (int)(mi.totalMem / 1048576L))
                      .putBoolean("pending_crash_ready", true)
                      .commit();
              } catch (Throwable ignored) {}
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

        // Native media overlay views (ExoPlayer video + Glide image).
        nativeVideoView = new androidx.media3.ui.PlayerView(this);
        nativeVideoView.setUseController(false);
        nativeVideoView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        nativeVideoView.setVisibility(View.INVISIBLE);
        root.addView(nativeVideoView, new FrameLayout.LayoutParams(1, 1));
          // Dual-buffer B video view â preloaded content renders here while A is visible
          nativeVideoViewB = new androidx.media3.ui.PlayerView(this);
          nativeVideoViewB.setUseController(false);
          nativeVideoViewB.setBackgroundColor(android.graphics.Color.TRANSPARENT);
          nativeVideoViewB.setVisibility(View.INVISIBLE);
          root.addView(nativeVideoViewB, new FrameLayout.LayoutParams(1, 1));

        nativeImageView = new RecyclingSafeImageView(this);
        nativeImageView.setVisibility(View.INVISIBLE);
        root.addView(nativeImageView, new FrameLayout.LayoutParams(1, 1));
          // Dual-buffer B image view â preloaded image loads here while A is visible
          nativeImageViewB = new RecyclingSafeImageView(this);
          nativeImageViewB.setVisibility(View.INVISIBLE);
          root.addView(nativeImageViewB, new FrameLayout.LayoutParams(1, 1));

        setContentView(root);


        requestCameraPermission();

        hardwareManager = new HardwareManager(this, new HardwareManager.HardwareListener() {
            @Override
            public void onDeviceConnected(JSONObject device) {
                runOnUiThread(() -> {
                    String js = "javascript:window.dispatchEvent(new CustomEvent('hw:deviceConnected', {detail: " + device.toString() + "}))";
                    webView.evaluateJavascript(js, null);
                });
            }

            @Override
            public void onDeviceDisconnected(JSONObject device) {
                runOnUiThread(() -> {
                    String js = "javascript:window.dispatchEvent(new CustomEvent('hw:deviceDisconnected', {detail: " + device.toString() + "}))";
                    webView.evaluateJavascript(js, null);
                });
            }

            @Override
            public void onSignalCaptured(JSONObject signal) {
                runOnUiThread(() -> {
                    String js = "javascript:window.dispatchEvent(new CustomEvent('hw:signalCaptured', {detail: " + signal.toString() + "}))";
                    webView.evaluateJavascript(js, null);
                });
            }

            @Override
            public void onSignalEvent(JSONObject signal) {
                runOnUiThread(() -> {
                    String js = "javascript:window.dispatchEvent(new CustomEvent('hw:signalEvent', {detail: " + signal.toString() + "}))";
                    webView.evaluateJavascript(js, null);
                });
            }
        });
        boolean _stEnabled = getSharedPreferences("digipal_prefs", MODE_PRIVATE)
                .getBoolean("smart_triggers_enabled", false);
        if (_stEnabled) { try { hardwareManager.start(); } catch (Throwable ignored) {} }

        mediaDownloadManager = new MediaDownloadManager(this);
        mediaDownloadManager.setWebView(webView);
        mediaDownloadManager.cleanupOrphans();

        
          // Start crash watchdog â relaunches app within 10s if it crashes or is killed.
          Intent watchdogIntent = new Intent(this, WatchdogService.class);
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              startForegroundService(watchdogIntent);
          } else {
              startService(watchdogIntent);
          }
  
        String serverUrl = getServerUrl();
        loadPlayerUrl(serverUrl);

        startAnrWatchdog();
        initNativeComponents();
          } catch (Throwable _onCreate_err) {
              try {
                  android.widget.LinearLayout errRoot = new android.widget.LinearLayout(this);
                  errRoot.setOrientation(android.widget.LinearLayout.VERTICAL);
                  errRoot.setGravity(android.view.Gravity.CENTER);
                  errRoot.setBackgroundColor(android.graphics.Color.parseColor("#0a0e1a"));
                  android.widget.TextView errMsg = new android.widget.TextView(this);
                  errMsg.setText("Startup error (" + "ST" + "):\n"
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
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setTextZoom(100);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
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
            // Serve virtual https://appassets.androidplatform.net/media/<objectPath>
            // URLs (handed out by getLocalMediaWebUrl in the JS bridge) from the local
            // media cache, so cached media plays offline without raw file:// URLs.
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
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
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
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

                  // Inject offline playlist cache into page for immediate use by React app
                  if (cacheDb != null) {
                      final android.webkit.WebView _wv = webView;
                      new Thread(() -> {
                          try {
                              java.util.List<CacheDatabase.CacheObject> all = cacheDb.cacheDao().findAll();
                              if (all == null || all.isEmpty()) return;
                              StringBuilder js = new StringBuilder(
                                  "if(!window.__digipalOfflineCache)window.__digipalOfflineCache={};");
                              for (CacheDatabase.CacheObject item : all) {
                                  js.append("window.__digipalOfflineCache[\"")
                                    .append(item.key.replace("\\","\\\\").replace("\"","\\\""))
                                    .append("\"] = ")
                                    .append(item.json).append(";");
                              }
                              final String script = js.toString();
                              runOnUiThread(() -> _wv.evaluateJavascript(script, null));
                          } catch (Throwable e) {
                              android.util.Log.w("Digipal", "Offline cache inject: " + e.getMessage());
                          }
                      }).start();
                  }
                  // Inject APK crash stats and any pending crash report for web-player pickup
                  try {
                      android.content.SharedPreferences _cp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                      int _cc = _cp.getInt(CrashCounter.KEY_CRASH_COUNT, 0);
                      boolean _mx = _cp.getBoolean(CrashCounter.KEY_MAX_EXCEEDED, false);
                      boolean _hasCrash = _cp.getBoolean("pending_crash_ready", false);
                      StringBuilder _js = new StringBuilder();
                      _js.append("window.__digipalCrashStats={crashCount:").append(_cc)
                         .append(",isMaxExceeded:").append(_mx).append("};\n");
                      if (_hasCrash) {
                          String _type = _cp.getString("pending_crash_type", "UnknownError");
                          String _stack = _cp.getString("pending_crash_stack", "");
                          long _at = _cp.getLong("pending_crash_at", 0L);
                          int _free = _cp.getInt("pending_crash_free_mb", 0);
                          int _total = _cp.getInt("pending_crash_total_mb", 0);
                          _js.append("window.__digipalNativeCrash={");
                          _js.append("\"occurredAt\":").append(_at).append(",");
                          _js.append("\"errorType\":\"").append(_type.replace("\"", "")).append("\",");
                          _js.append("\"freeMemoryMb\":").append(_free).append(",");
                          _js.append("\"totalMemoryMb\":").append(_total).append(",");
                          String _safeStack = _stack.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
                          _js.append("\"stackTrace\":\"").append(_safeStack).append("\"");
                          _js.append("}");
                      } else {
                          _js.append("window.__digipalNativeCrash=null;");
                      }
                      final String _finalJs = _js.toString();
                      final android.webkit.WebView _wv2 = webView;
                      runOnUiThread(() -> { if (_wv2 != null) _wv2.evaluateJavascript(_finalJs, null); });
                  } catch (Throwable ignored) {}
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("Connection Lost", "Unable to reach the server. Retrying...");
                    retryConnection();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
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
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
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
                customViewContainer.removeView(customView);
                customView = null;
                customViewContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
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

        @JavascriptInterface
        public void heartbeat() {
            lastHeartbeatMs = System.currentTimeMillis();
            heartbeatReceived = true;
            if (healthMonitor != null) healthMonitor.onHeartbeatReceived();
        }

        @JavascriptInterface
        public String getConnectedDevices() {
            if (hardwareManager == null) return "[]";
            return hardwareManager.getConnectedDevices().toString();
        }

        @JavascriptInterface
        public void startLearnMode() {
            if (hardwareManager != null) {
                hardwareManager.startLearnMode();
            }
        }

        @JavascriptInterface
        public void stopLearnMode() {
            if (hardwareManager != null) {
                hardwareManager.stopLearnMode();
            }
        }

        @JavascriptInterface
        public void startBleScan() {
            if (hardwareManager != null) { hardwareManager.startBleScan(); }
        }

        @JavascriptInterface
        public void enableSmartTriggers() {
            getSharedPreferences("digipal_prefs", MODE_PRIVATE).edit()
                    .putBoolean("smart_triggers_enabled", true).apply();
            runOnUiThread(() -> { if (hardwareManager!=null) {
                try { hardwareManager.start(); } catch (Throwable ignored) {} } });
        }

        @JavascriptInterface
        public void disableSmartTriggers() {
            getSharedPreferences("digipal_prefs", MODE_PRIVATE).edit()
                    .putBoolean("smart_triggers_enabled", false).apply();
            runOnUiThread(() -> { if (hardwareManager!=null) {
                try { hardwareManager.stop(); } catch (Throwable ignored) {} } });
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
         *  getLocalMediaPath()'s file:// URLs, these work from an https:// origin page. */
        @JavascriptInterface
        public String getLocalMediaWebUrl(String objectPath) {
            if (mediaDownloadManager != null) {
                return mediaDownloadManager.getLocalMediaWebUrl(objectPath);
            }
            return "";
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
                  String androidId = android.provider.Settings.Secure.getString(
                      getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                  if (androidId != null && !androidId.isEmpty()) {
                      try {
                          java.security.MessageDigest md =
                              java.security.MessageDigest.getInstance("SHA-256");
                          byte[] hash = md.digest(androidId.getBytes("UTF-8"));
                          StringBuilder sb = new StringBuilder();
                          for (byte b : hash) sb.append(String.format("%02x", b));
                          o.put("deviceId", sb.toString().substring(0, 16));
                      } catch (java.security.NoSuchAlgorithmException ignored) {}
                  }
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
                    // These two fields are read by parseResourceStats() in
                    // playerBridge.ts to compute nativeMemRatio. Previously
                    // missing — caused nativeMemRatio to always report 0.
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
        public void notifyPaired(String url) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (prefs.getBoolean("cloud_pairing_pending", false)) {
                String serverUrl = (url != null && !url.isEmpty()) ? url : BuildConfig.SERVER_URL;
                prefs.edit()
                    .putString("server_mode", "cloud")
                    .putString(KEY_SERVER_URL, serverUrl)
                    .putBoolean("cloud_pairing_pending", false)
                    .apply();
            }
        }

          @JavascriptInterface
          public void captureScreenshot(String requestId) {
              if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                  // PixelCopy requires API 26+; signal null so the web side falls back to html2canvas
                  runOnUiThread(() ->
                      webView.evaluateJavascript(
                          "if(window.__digipalNativeScreenshot)window.__digipalNativeScreenshot('"
                              + requestId + "',null)", null));
                  return;
              }
              Bitmap bmp = Bitmap.createBitmap(
                      webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
              PixelCopy.request(
                      getWindow(),
                      new Rect(0, 0, webView.getWidth(), webView.getHeight()),
                      bmp,
                      copyResult -> {
                          String b64 = null;
                          if (copyResult == PixelCopy.SUCCESS) {
                              ByteArrayOutputStream baos = new ByteArrayOutputStream();
                              bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                              b64 = "data:image/jpeg;base64,"
                                      + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                          }
                          final String payload = b64;
                          runOnUiThread(() ->
                              webView.evaluateJavascript(
                                  "if(window.__digipalNativeScreenshot)window.__digipalNativeScreenshot('"
                                      + requestId + "'," + (payload == null ? "null" : "'" + payload + "'") + ")",
                                  null));
                      },
                      new Handler(Looper.getMainLooper()));
          }


          /**
           * Called by the React player after each successful server fetch.
           * Upserts the JSON into Room so it's available on the next boot.
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
           * Synchronously returns cached JSON by key (runs on the JS bridge thread, not main thread).
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
                        if (videoReadyHandler != null && videoReadyRunnable != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                        if (nativeVideoListener != null) { if (exoPlayer != null) exoPlayer.removeListener(nativeVideoListener); nativeVideoListener = null; }
                        float d = getResources().getDisplayMetrics().density;
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int)(w * d), (int)(h * d));
                        lp.leftMargin = (int)(x * d); lp.topMargin = (int)(y * d);
                        int resizeMode = "cover".equals(objectFit) ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM :
                                         "fill".equals(objectFit)  ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL :
                                         androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
                        final androidx.media3.ui.PlayerView activeView  = activeVideoViewIsA ? nativeVideoView : nativeVideoViewB;
                        final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
                        // Discard any pending-old player that was never cleaned up (e.g. back-to-back plays)
                          if (pendingOldPlayer != null) { try { pendingOldPlayer.stop(); pendingOldPlayer.release(); } catch (Throwable ignored) {} pendingOldPlayer = null; }
                          // DIAG: timing + device state for every video transition
                          final long diagT0 = android.os.SystemClock.elapsedRealtime();
                          final String diagPath = fromPreload ? (preloadVideoReady ? "preload-ready" : "preload-waiting") : "cold-load";
                          { android.app.ActivityManager.MemoryInfo diagMi = new android.app.ActivityManager.MemoryInfo(); ((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(diagMi); android.util.Log.i("DigipalMetrics", "[playNativeVideo] path=" + diagPath + " memAvailMB=" + (diagMi.availMem/1048576L) + " lowMem=" + diagMi.lowMemory + " url=…" + (url.length()>50 ? url.substring(url.length()-50) : url)); }
                          if (fromPreload) {
                              // Capture old player â keep it running on activeView until new frame confirmed visible
                              final androidx.media3.exoplayer.ExoPlayer oldPlayer = exoPlayer;
                              pendingOldPlayer = oldPlayer;
                              exoPlayer = preloadPlayer; preloadPlayer = null; preloadedVideoUrl = null;
                              exoPlayer.setRepeatMode(loop ? androidx.media3.common.Player.REPEAT_MODE_ONE : androidx.media3.common.Player.REPEAT_MODE_OFF);
                              exoPlayer.setVolume(volume); exoPlayer.play();
                              preloadView.setResizeMode(resizeMode); preloadView.setLayoutParams(lp);
                              if (preloadVideoReady) {
                                  preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE);
                                  activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                                  pendingOldPlayer = null; if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                  webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                                  if (contentId != null && !contentId.isEmpty()) { webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady_"+contentId+"==='function')window.__digipalNativeVideoReady_"+contentId+"()", null); }
                              } else {
                                  final boolean[] done = {false};
                                  nativeVideoListener = new androidx.media3.common.Player.Listener() {
                                      @Override public void onRenderedFirstFrame() {
                                          if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                                          if (done[0]) return; done[0] = true;
                                          runOnUiThread(() -> {
                                              if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                              preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE);
                                              activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                                              pendingOldPlayer = null; if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                              webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                                              if (contentId != null && !contentId.isEmpty()) { webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady_"+contentId+"==='function')window.__digipalNativeVideoReady_"+contentId+"()", null); }
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
                                          if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                                          if (done[0]) return; done[0] = true;
                                          if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                          pendingOldPlayer = null; if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                          webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                                          if (contentId != null && !contentId.isEmpty()) { webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady_"+contentId+"==='function')window.__digipalNativeVideoReady_"+contentId+"()", null); }
                                      }
                                  };
                                  exoPlayer.addListener(nativeVideoListener);
                                  final android.os.Handler rH = new android.os.Handler(android.os.Looper.getMainLooper());
                                  final Runnable rCb = new Runnable() {
                                      @Override public void run() {
                                          videoReadyHandler = null; videoReadyRunnable = null; nativeVideoListener = null;
                                          if (done[0]) return; done[0] = true;
                                          preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE);
                                          activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                                          pendingOldPlayer = null; if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                          webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                                          if (contentId != null && !contentId.isEmpty()) { webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady_"+contentId+"==='function')window.__digipalNativeVideoReady_"+contentId+"()", null); }
                                      }
                                  };
                                  videoReadyHandler = rH; videoReadyRunnable = rCb; rH.postDelayed(rCb, 2500);
                              }
                          } else {
                              // Cold load: use inactive (preload) view so old content stays visible
                              if (preloadPlayer != null) { try { preloadPlayer.release(); } catch (Throwable ignored) {} preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false; preloadView.setPlayer(null); }
                              final androidx.media3.exoplayer.ExoPlayer coldPlayer = buildCachedExoPlayer();
                              preloadView.setPlayer(coldPlayer); preloadView.setResizeMode(resizeMode); preloadView.setLayoutParams(lp);
                              coldPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(url)));
                              coldPlayer.setRepeatMode(loop ? androidx.media3.common.Player.REPEAT_MODE_ONE : androidx.media3.common.Player.REPEAT_MODE_OFF);
                              coldPlayer.setVolume(volume); coldPlayer.prepare(); coldPlayer.play();
                              final androidx.media3.exoplayer.ExoPlayer oldPlayer = exoPlayer;
                              exoPlayer = coldPlayer; pendingOldPlayer = oldPlayer;
                              final android.os.Handler rH2 = new android.os.Handler(android.os.Looper.getMainLooper());
                              final Runnable rCb2 = new Runnable() { @Override public void run() { videoReadyHandler = null; videoReadyRunnable = null; nativeVideoListener = null; long diagFbMs = android.os.SystemClock.elapsedRealtime() - diagT0; android.util.Log.w("DigipalMetrics", "[8s fallback fired] cold-load — onRenderedFirstFrame never arrived, latencyMs=" + diagFbMs); preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE); activeVideoViewIsA = !activeVideoViewIsA; pendingOldPlayer = null; if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} } webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                              if (contentId != null && !contentId.isEmpty()) { webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady_"+contentId+"==='function')window.__digipalNativeVideoReady_"+contentId+"()", null); } webView.evaluateJavascript("if(window.__digipalNativeMetrics)window.__digipalNativeMetrics({type:'videoFallback',path:'cold-load',latencyMs:" + diagFbMs + "})", null); } };
                              videoReadyHandler = rH2; videoReadyRunnable = rCb2; rH2.postDelayed(rCb2, 8000);
                              nativeVideoListener = new androidx.media3.common.Player.Listener() {
                                  @Override public void onRenderedFirstFrame() {
                                      if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                                      if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                      final long diagLatencyMs = android.os.SystemClock.elapsedRealtime() - diagT0;
                                      android.util.Log.i("DigipalMetrics", "[onRenderedFirstFrame] path=" + diagPath + " latencyMs=" + diagLatencyMs);
                                      runOnUiThread(() -> { preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE); activeVideoViewIsA = !activeVideoViewIsA; pendingOldPlayer = null; if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} } webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                                      if (contentId != null && !contentId.isEmpty()) { webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady_"+contentId+"==='function')window.__digipalNativeVideoReady_"+contentId+"()", null); } webView.evaluateJavascript("if(window.__digipalNativeMetrics)window.__digipalNativeMetrics({type:'videoReady',path:'" + diagPath + "',latencyMs:" + diagLatencyMs + "})", null); });
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
                                      if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                                      if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                      preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE); activeVideoViewIsA = !activeVideoViewIsA;
                                      pendingOldPlayer = null; if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} }
                                      webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                                      if (contentId != null && !contentId.isEmpty()) { webView.evaluateJavascript("if(typeof window.__digipalNativeVideoReady_"+contentId+"==='function')window.__digipalNativeVideoReady_"+contentId+"()", null); }
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
                        if (videoReadyHandler != null && videoReadyRunnable != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                        if (nativeVideoListener != null) { if (exoPlayer != null) exoPlayer.removeListener(nativeVideoListener); nativeVideoListener = null; }
                        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }
                        if (pendingOldPlayer != null) { try { pendingOldPlayer.stop(); pendingOldPlayer.release(); } catch (Throwable ignored) {} pendingOldPlayer = null; }
                        nativeVideoView.setVisibility(View.INVISIBLE);
                        if (nativeVideoViewB != null) nativeVideoViewB.setVisibility(View.INVISIBLE);
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
            public void showNativeImage(String url, float x, float y, float w, float h, String scaleType) {
                runOnUiThread(() -> {
                    try {
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
                            preloadImgView.setScaleType(st); preloadImgView.setLayoutParams(lp); preloadImgView.setVisibility(View.VISIBLE);
                            activeImgView.setVisibility(View.INVISIBLE); com.bumptech.glide.Glide.with(MainActivity.this).clear(activeImgView);
                            activeImageViewIsA = !activeImageViewIsA; preloadedImageUrl = null; preloadImageReady = false;
                            webView.evaluateJavascript("if(typeof window.__digipalNativeImageReady==='function')window.__digipalNativeImageReady()", null);
                        } else {
                              // Cold fallback: load into inactive (preload) view — old content stays visible until first draw confirmed
                              com.bumptech.glide.Glide.with(MainActivity.this).clear(preloadImgView);
                              preloadedImageUrl = null; preloadImageReady = false;
                              final android.widget.ImageView incoming = preloadImgView;
                              final android.widget.ImageView outgoing = activeImgView;
                              incoming.setLayoutParams(lp); incoming.setScaleType(st); incoming.setAlpha(0f); incoming.setVisibility(View.VISIBLE);
                              // Trim Glide bitmap cache before cold load (Fire TV OOM fix).
                              try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE); } catch (Throwable ignored) {}
                              com.bumptech.glide.Glide.with(MainActivity.this).load(url)
                                  .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                      @Override public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                          incoming.setVisibility(View.INVISIBLE); webView.evaluateJavascript("if(typeof window.__digipalNativeImageReady==='function')window.__digipalNativeImageReady()", null); return false; }
                                      @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                          // Wait for first draw confirmation — prevents brown/golden square on rapid transitions
                                          android.view.ViewTreeObserver vto = incoming.getViewTreeObserver();
                                          vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                                              @Override public boolean onPreDraw() {
                                                  android.view.ViewTreeObserver live = incoming.getViewTreeObserver();
                                                  if (live.isAlive()) live.removeOnPreDrawListener(this);
                                                  incoming.setAlpha(1f); outgoing.setVisibility(View.INVISIBLE); activeImageViewIsA = !activeImageViewIsA;
                                                  try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE); } catch (Throwable ignored) {}
                                                  com.bumptech.glide.Glide.with(MainActivity.this).clear(outgoing);
                                                  webView.evaluateJavascript("if(typeof window.__digipalNativeImageReady==='function')window.__digipalNativeImageReady()", null);
                                                  return true; } });
                                          return false; }
                                  }).into(incoming);
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
                        nativeImageView.setVisibility(View.INVISIBLE); nativeImageViewB.setVisibility(View.INVISIBLE);
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
                          if (preloadPlayer != null) { try { preloadPlayer.release(); } catch (Throwable ignored) {} preloadPlayer = null; }
                          final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
                          preloadView.setPlayer(null); preloadedVideoUrl = null; preloadVideoReady = false;
                          android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                          android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
                          if (mi.lowMemory) return;
                          preloadView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                          preloadPlayer = buildCachedExoPlayer();
                          preloadView.setPlayer(preloadPlayer);
                          preloadPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(url)));
                          preloadPlayer.setVolume(0f); preloadPlayer.setPlayWhenReady(false); preloadPlayer.prepare();
                          preloadedVideoUrl = url;
                          final androidx.media3.exoplayer.ExoPlayer captured = preloadPlayer;
                          preloadPlayer.addListener(new androidx.media3.common.Player.Listener() {
                              @Override public void onRenderedFirstFrame() {
                                  if (captured == preloadPlayer) preloadVideoReady = true;
                                  captured.removeListener(this);
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
                          // Low-memory gate: skip image preload under memory pressure.
                          // Mirrors the video preload gate — prevents Glide from decoding
                          // a second bitmap when headroom is insufficient (Fire TV OOM fix).
                          android.app.ActivityManager _am2 = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                          if (_am2 != null) {
                              android.app.ActivityManager.MemoryInfo _mi2 = new android.app.ActivityManager.MemoryInfo();
                              _am2.getMemoryInfo(_mi2);
                              if (_mi2.lowMemory) return;
                          }
                          final android.widget.ImageView preloadImgView = activeImageViewIsA ? nativeImageViewB : nativeImageView;
                          com.bumptech.glide.Glide.with(MainActivity.this).clear(preloadImgView);
                          preloadedImageUrl = null; preloadImageReady = false;
                          preloadImgView.setVisibility(View.INVISIBLE);
                          preloadImgView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                          preloadedImageUrl = url;
                          com.bumptech.glide.Glide.with(MainActivity.this).load(url)
                              .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                  @Override public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                      if (url.equals(preloadedImageUrl)) preloadImageReady = false; return false; }
                                  @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                      if (url.equals(preloadedImageUrl)) preloadImageReady = true; return false; }
                              }).into(preloadImgView);
                      } catch (Exception e) { android.util.Log.w("DigipalNative", "preloadNativeImage error: " + e.getMessage()); }
                  });
              }

            @android.webkit.JavascriptInterface
              public void cancelNativePreload() {
                  runOnUiThread(() -> {
                      try {
                          if (preloadPlayer != null) { try { preloadPlayer.release(); } catch (Throwable ignored) {} preloadPlayer = null; }
                          preloadedVideoUrl = null; preloadVideoReady = false;
                          final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
                          try { preloadView.setPlayer(null); } catch (Throwable ignored) {}
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
                              }
                          }
                          android.util.Log.d("DigipalNative", "[nativeFirst] setWebViewDormant=" + dormant);
                          if (nativeFirstRendering && dormant && currentPlayerUrl != null) {
                              // Per-slide WebView recreation: destroy V8 heap + GPU compositor while native
                              // overlay covers the screen, then reload fresh for the next web slide.
                              // (OptiSigns technique — eliminates cumulative memory leak between slides.)
                              new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                  try {
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
                        if (assetCacheManager != null) {
                            try {
                                org.json.JSONArray _arr = new org.json.JSONArray(json);
                                for (int _i = 0; _i < _arr.length(); _i++) {
                                    org.json.JSONObject _o = _arr.getJSONObject(_i);
                                    String _t = _o.optString("type",""), _u = _o.optString("url","");
                                    String _s = _o.optString("slideId",String.valueOf(_o.optInt("contentId",_i)));
                                    if (("VIDEO".equals(_t)||"IMAGE".equals(_t)) && _u!=null && !_u.isEmpty())
                                        assetCacheManager.downloadAsync("slide_"+_s,_u,"",
                                            new AssetCacheManager.DownloadCallback(){
                                                @Override public void onSuccess(String id,String p){
                                                    android.util.Log.d("DigipalAsset","[snp] "+id);}
                                                @Override public void onFailure(String id,String e){
                                                    android.util.Log.d("DigipalAsset","[snp] miss "+id);}
                                            });
                                }
                            } catch (Exception _e) {}
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
                }

                /**
                 * Task #2118: called by React's notifyContentVisible() via bridge.webViewSlideReady()
                 * when a WEBVIEW_* slide's content is fully visible (widget data fetched, design
                 * rendered). Signals PlaylistScheduler to start the advance timer only then —
                 * not at isolated-renderer first-paint — so the full duration is visible content.
                 */
                @android.webkit.JavascriptInterface
                public void webViewSlideReady() {
                    if (playlistScheduler != null) playlistScheduler.onWebViewSlideReady();
                }

                @android.webkit.JavascriptInterface
                public void onNativeRendererError(String slideId, String error) {
                    if (playlistScheduler != null) playlistScheduler.onRendererError(slideId, error);
                    if (reliabilitySupervisor != null) reliabilitySupervisor.reportError("renderer", error);
                }

                @android.webkit.JavascriptInterface
                public void reloadNativePlaylist() {
                    // JS will call setNativePlaylist again with refreshed slide data
                    android.util.Log.i("DigipalNative", "[nativeLoop] reloadNativePlaylist signal received");
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


      private void playNativeVideoForScheduler(String url, String objectFit, boolean loop, float volume, String slideId) {
          try {
              boolean fromPreload = url.equals(preloadedVideoUrl) && preloadPlayer != null;
              if (videoReadyHandler != null && videoReadyRunnable != null) {
                  videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null;
              }
              if (nativeVideoListener != null) {
                  if (exoPlayer != null) exoPlayer.removeListener(nativeVideoListener); nativeVideoListener = null;
              }
              android.util.DisplayMetrics _dm = getResources().getDisplayMetrics();
              FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(_dm.widthPixels, _dm.heightPixels);
              int resizeMode = "cover".equals(objectFit) ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM :
                               "fill".equals(objectFit)  ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL :
                               androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
              final androidx.media3.ui.PlayerView activeView  = activeVideoViewIsA ? nativeVideoView : nativeVideoViewB;
              final androidx.media3.ui.PlayerView preloadView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
              if (pendingOldPlayer != null) { try { pendingOldPlayer.stop(); pendingOldPlayer.release(); } catch (Throwable ignored) {} pendingOldPlayer = null; }
              if (fromPreload) {
                  final androidx.media3.exoplayer.ExoPlayer oldPlayer = exoPlayer;
                  pendingOldPlayer = oldPlayer;
                  exoPlayer = preloadPlayer; preloadPlayer = null; preloadedVideoUrl = null;
                  exoPlayer.setRepeatMode(loop ? androidx.media3.common.Player.REPEAT_MODE_ONE : androidx.media3.common.Player.REPEAT_MODE_OFF);
                  exoPlayer.setVolume(volume); exoPlayer.play();
                  preloadView.setResizeMode(resizeMode); preloadView.setLayoutParams(lp);
                  if (preloadVideoReady) {
                      preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE);
                      activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                      pendingOldPlayer = null;
                      if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                      if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                  } else {
                      final boolean[] done = {false};
                      nativeVideoListener = new androidx.media3.common.Player.Listener() {
                          @Override public void onRenderedFirstFrame() {
                              if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                              if (done[0]) return; done[0] = true;
                              runOnUiThread(() -> {
                                  if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                                  preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE);
                                  activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                                  pendingOldPlayer = null;
                                  if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                  if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                              });
                          }
                          @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                              android.util.Log.w("DigipalMetrics", "[sched preload onPlayerError] slide=" + slideId + " " + error.getMessage());
                              if (error.getCause() instanceof androidx.media3.exoplayer.ExoTimeoutException) return;
                              if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                              if (done[0]) return; done[0] = true;
                              if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                              pendingOldPlayer = null;
                              if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                              if (playlistScheduler != null) playlistScheduler.onRendererError(slideId, "exoplayer_preload_fatal");
                          }
                      };
                      exoPlayer.addListener(nativeVideoListener);
                      final android.os.Handler rh = new android.os.Handler(android.os.Looper.getMainLooper());
                      final Runnable rc = new Runnable() {
                          @Override public void run() {
                              videoReadyHandler = null; videoReadyRunnable = null; nativeVideoListener = null;
                              if (done[0]) return; done[0] = true;
                              preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE);
                              activeVideoViewIsA = !activeVideoViewIsA; preloadVideoReady = false;
                              pendingOldPlayer = null;
                              if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                          }
                      };
                      videoReadyHandler = rh; videoReadyRunnable = rc; rh.postDelayed(rc, 2500);
                  }
              } else {
                  if (preloadPlayer != null) { try { preloadPlayer.release(); } catch (Throwable ignored) {} preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false; preloadView.setPlayer(null); }
                  final androidx.media3.exoplayer.ExoPlayer coldPlayer = buildCachedExoPlayer();
                  preloadView.setPlayer(coldPlayer); preloadView.setResizeMode(resizeMode); preloadView.setLayoutParams(lp);
                  coldPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(url)));
                  coldPlayer.setRepeatMode(loop ? androidx.media3.common.Player.REPEAT_MODE_ONE : androidx.media3.common.Player.REPEAT_MODE_OFF);
                  coldPlayer.setVolume(volume); coldPlayer.prepare(); coldPlayer.play();
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
                              "[sched 8s timeout] cold-load slide=" + slideId + " — no first-frame; reporting error");
                          if (playlistScheduler != null)
                              playlistScheduler.onRendererError(slideId, "video_first_frame_timeout");
                      }
                  };
                  videoReadyHandler = rh; videoReadyRunnable = rc; rh.postDelayed(rc, 8000);
                  nativeVideoListener = new androidx.media3.common.Player.Listener() {
                      @Override public void onRenderedFirstFrame() {
                          if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                          if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                          runOnUiThread(() -> {
                              preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE);
                              activeVideoViewIsA = !activeVideoViewIsA;
                              pendingOldPlayer = null;
                              if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} }
                              if (playlistScheduler != null) playlistScheduler.onRendererReady(slideId);
                          });
                      }
                      @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                          android.util.Log.w("DigipalMetrics", "[sched cold onPlayerError] slide=" + slideId + " " + error.getMessage());
                          if (error.getCause() instanceof androidx.media3.exoplayer.ExoTimeoutException) return;
                          if (exoPlayer != null) exoPlayer.removeListener(this); nativeVideoListener = null;
                          if (videoReadyHandler != null) { videoReadyHandler.removeCallbacks(videoReadyRunnable); videoReadyHandler = null; videoReadyRunnable = null; }
                          preloadView.setVisibility(View.VISIBLE); activeView.setVisibility(View.INVISIBLE);
                          activeVideoViewIsA = !activeVideoViewIsA;
                          pendingOldPlayer = null;
                          if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} }
                          if (playlistScheduler != null) playlistScheduler.onRendererError(slideId, "exoplayer_cold_fatal");
                      }
                  };
                  exoPlayer.addListener(nativeVideoListener);
              }
          } catch (Exception e) { android.util.Log.e("DigipalNative", "playNativeVideoForScheduler error", e); }
      }

      private void initNativeComponents() {
          try {
              playlistRepository = new PlaylistRepository(this);
              telemetryManager   = new TelemetryManager(this, playlistRepository, getServerUrl());
              telemetryManager.start();

                // ---- MemoryBudgetManager: 5-second memory tier poller ----
                memoryBudgetManager = new MemoryBudgetManager(
                    getApplicationContext(),
                    (oldTier, newTier) ->
                        android.util.Log.i("NexuscastMemory", "tier " + oldTier + " -> " + newTier),
                    js -> runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); }));
                memoryBudgetManager.start();

                // ---- RecoveryCoordinator: ordered escalation router ----
                recoveryCoordinator = new RecoveryCoordinator(getApplicationContext(),
                    new RecoveryCoordinator.EscalationDelegate() {
                        @Override public void onSlideRetry(String sid, String reason) {
                            android.util.Log.i("NexuscastRecovery", "[SLIDE_RETRY] " + sid + " " + reason);
                        }
                        @Override public void onSlideSkip(String sid, String reason) {
                            android.util.Log.w("NexuscastRecovery", "[SLIDE_SKIP] " + sid + " " + reason);
                        }
                        @Override public void onRendererRebuild(String reason) {
                            android.util.Log.w("NexuscastRecovery", "[RENDERER_REBUILD] " + reason);
                            runOnUiThread(() -> releaseAllRenderers());
                        }
                        @Override public void onWebViewRebuild(String reason) {
                            android.util.Log.w("NexuscastRecovery", "[WEBVIEW_REBUILD] " + reason);
                        }
                        @Override public void onPlaylistRollback(String reason) {
                            android.util.Log.w("NexuscastRecovery", "[PLAYLIST_ROLLBACK] " + reason);
                        }
                        @Override public void onSoftRestart(String reason) {
                            android.util.Log.e("NexuscastRecovery", "[SOFT_RESTART] " + reason);
                            AppRecoverManager.scheduleRecovery(getApplicationContext());
                        }
                        @Override public void onHardRestart(String reason) {
                            android.util.Log.e("NexuscastRecovery", "[HARD_RESTART] " + reason);
                            AppRecoverManager.scheduleRecovery(getApplicationContext());
                        }
                    },
                    js -> runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); }));
                AppRecoverManager.setRecoveryCoordinator(recoveryCoordinator);

                final ReliabilitySupervisor[] supRef = new ReliabilitySupervisor[1];

              playlistScheduler = new PlaylistScheduler(
                  new PlaylistScheduler.Delegate() {
                      @Override public void schedulerPlayVideo(PlaylistScheduler.SlidePlan s) {
                          final String _url = s.url; final String _fit = s.objectFit;
                          final boolean _loop = s.loop; final float _vol = s.volume;
                          final String _sid = s.slideId;
                          if (healthMonitor != null) healthMonitor.setRendererTypeNative();
                          runOnUiThread(() -> {
                              try {
                                  if (webView != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                      try { webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_BOUND, false); } catch (Throwable ignored) {}
                                  }
                                  playNativeVideoForScheduler(_url, _fit, _loop, _vol, _sid);
                              } catch (Throwable ignored) {}
                              if (supRef[0] != null) supRef[0].reportSchedulerAdvance();
                          });
                      }
                      @Override public void schedulerShowImage(PlaylistScheduler.SlidePlan s) {
                          final String _url = s.url; final String _sc = s.scaleType;
                          final String _sid = s.slideId;
                          if (healthMonitor != null) healthMonitor.setRendererTypeNative();
                          runOnUiThread(() -> {
                              try {
                                  if (webView != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                      try { webView.setRendererPriorityPolicy(android.webkit.WebView.RENDERER_PRIORITY_BOUND, false); } catch (Throwable ignored) {}
                                  }
                                  android.widget.ImageView.ScaleType st =
                                      "cover".equals(_sc) ? android.widget.ImageView.ScaleType.CENTER_CROP :
                                      "fill".equals(_sc)  ? android.widget.ImageView.ScaleType.FIT_XY :
                                      android.widget.ImageView.ScaleType.FIT_CENTER;
                                  android.util.DisplayMetrics _dm = getResources().getDisplayMetrics();
                                  FrameLayout.LayoutParams _lp = new FrameLayout.LayoutParams(_dm.widthPixels, _dm.heightPixels);
                                  final android.widget.ImageView activeImgView  = activeImageViewIsA ? nativeImageView : nativeImageViewB;
                                  final android.widget.ImageView preloadImgView = activeImageViewIsA ? nativeImageViewB : nativeImageView;
                                  if (_url.equals(preloadedImageUrl) && preloadImageReady) {
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
                                      activeImgView.setLayoutParams(_lp);
                                      activeImgView.setScaleType(st);
                                      activeImgView.setVisibility(View.INVISIBLE);
                                      com.bumptech.glide.Glide.with(MainActivity.this)
                                          .load(_url)
                                          .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                              @Override public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                                      Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                                  android.util.Log.w("DigipalNative", "[schedulerShowImage] Glide failed slide=" + _sid + ": " + (e != null ? e.getMessage() : "null"));
                                                  try { io.sentry.Breadcrumb _bc = new io.sentry.Breadcrumb("Glide load failed slide=" + _sid + ": " + (e != null ? e.getMessage() : "null")); _bc.setLevel(io.sentry.SentryLevel.WARNING); _bc.setType("error"); io.sentry.Sentry.addBreadcrumb(_bc); } catch (Throwable ignored) {}
                                                  if (playlistScheduler != null) playlistScheduler.onRendererError(_sid, "glide_load_failed");
                                                  return false;
                                              }
                                              @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                      com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                      com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                                  activeImgView.setVisibility(View.VISIBLE);
                                                  if (playlistScheduler != null) playlistScheduler.onRendererReady(_sid);
                                                  try { com.bumptech.glide.Glide.get(MainActivity.this).trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE); } catch (Throwable ignored) {}
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
                                  preloadView.setPlayer(preloadPlayer);
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
                      @Override public void schedulerActivateWebView(PlaylistScheduler.SlidePlan s) {
                          if (healthMonitor != null) healthMonitor.setRendererTypeWeb();
                          final int _contentId = s.contentId;
                          runOnUiThread(() -> {
                              try {
                                  if (webView != null) {
                                      webView.resumeTimers();
                                      // Sync React playlist index to the WebView slide PlaylistScheduler intends.
                                      // The JS advance timer is suspended when nativeSchedulerActive; this keeps
                                      // WebView content in lock-step with the native state machine so two
                                      // playlists cannot play simultaneously on mixed-content playlists.
                                      webView.evaluateJavascript(
                                          "try{window.__digipalGotoSlide&&window.__digipalGotoSlide("
                                          + _contentId + ");}catch(e){}",
                                          null
                                      );
                                  }
                                  applyWebViewProfile(s.type);
                              } catch (Throwable ignored) {}
                          });
                      }
                      @Override public void schedulerDeactivateWebView() {
                          // WebView dormant enforced in schedulerPlayVideo/schedulerShowImage via setWebViewDormant(true)
                          // Pause V8/Blink timer loop to release JS heap pressure during native playback
                          runOnUiThread(() -> {
                              try {
                                  if (webView != null) {
                                      webView.pauseTimers();
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
                      @Override public void schedulerStopVideo() {
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
                                  if (exoPlayer != null) { try { exoPlayer.stop(); exoPlayer.clearMediaItems(); } catch (Throwable ignored) {} }
                                  // Task #2119: drain before release to let Amlogic codec flush buffers.
                                  if (pendingOldPlayer != null) { drainAndRelease(pendingOldPlayer); pendingOldPlayer = null; }
                                  final androidx.media3.ui.PlayerView activeView = activeVideoViewIsA ? nativeVideoView : nativeVideoViewB;
                                  final androidx.media3.ui.PlayerView inactiveView = activeVideoViewIsA ? nativeVideoViewB : nativeVideoView;
                                  activeView.setVisibility(View.INVISIBLE);
                                  inactiveView.setVisibility(View.INVISIBLE);
                              } catch (Throwable ignored) {}
                          });
                      }
                      @Override public void schedulerHideImage() {
                          runOnUiThread(() -> {
                              try {
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
              assetCacheManager.setOnAssetReadyListener((url, localPath) ->
                  localPathCache.put(url, localPath));

              playlistScheduler.setAssetResolver(url -> localPathCache.get(url));

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

              reliabilitySupervisor = new ReliabilitySupervisor(
                  this,
                  new ReliabilitySupervisor.RecoveryDelegate() {
                      @Override public void softRecover(String reason) {
                          android.util.Log.i("DigipalReliability", "[soft] " + reason);
                      }
                      @Override public void mediumRecover(String reason) {
                          android.util.Log.w("DigipalReliability", "[medium] " + reason);
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
                reliabilitySupervisor.startExternallyClocked();

              // Hand heartbeat stale check to HealthMonitor; it drives reliability checks too.
              stopHeartbeatWatchdog();
              healthMonitor = new HealthMonitor(
                  telemetryManager, reliabilitySupervisor,
                  () -> isNetworkConnectedForWatchdog(),
                  () -> runOnUiThread(() -> { try { forcePlayerReload(); } catch (Throwable ignored) {} })
              );
              healthMonitor.start();

              // Boot restore — play last ACTIVE revision from Room without network
              playlistScheduler.boot();
              android.util.Log.i("DigipalNative", "[v3.11.0] initNativeComponents complete");
          } catch (Throwable e) {
              android.util.Log.e("DigipalNative", "initNativeComponents error: " + e.getMessage());
          }
      }


    /**
     * Apply WebView security/performance settings appropriate for the slide type.
     * Must be called on the UI thread.
     *
     * DESIGN_KIOSK: full settings — file access, universal access, ALWAYS_ALLOW mixed content,
     *               renderer priority IMPORTANT (keeps renderer alive under memory pressure).
     * SIMPLE_URL  : restricted — no file access, no universal access,
     *               COMPATIBILITY_MODE (blocks active mixed content), renderer priority NORMAL.
     */
    private void applyWebViewProfile(PlaylistScheduler.SlideType slideType) {
        if (webView == null) return;
        android.webkit.WebSettings settings = webView.getSettings();
        boolean isDesignKiosk = slideType == PlaylistScheduler.SlideType.WEBVIEW_DESIGN
                || slideType == PlaylistScheduler.SlideType.WEBVIEW_KIOSK;
        if (isDesignKiosk) {
            settings.setAllowFileAccess(true);
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
            settings.setDatabaseEnabled(true);
            settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
            }
        } else {
            // SIMPLE_URL: restrict file access, block active mixed content
            settings.setAllowFileAccess(false);
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setDatabaseEnabled(false);
            settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
            }
        }
    }

      private void scheduleAppRelaunch(long delayMs) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, flags
            );

            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager != null) {
                long triggerAt = System.currentTimeMillis() + delayMs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                }
            }
        } catch (SecurityException e) {
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int flags = PendingIntent.FLAG_ONE_SHOT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
                AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (alarmManager != null) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pendingIntent);
                }
            } catch (Throwable ignored) {}
        }
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

    private void loadPlayerUrl(String baseUrl) { loadPlayerUrl(baseUrl, false); }

    private void loadPlayerUrl(String baseUrl, boolean freshReload) {
        if (baseUrl.startsWith("http://")) {
            try {
                String host = new java.net.URI(baseUrl).getHost();
                if (host == null || !PRIVATE_IP_PATTERN.matcher(host).matches()) {
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
        playerUrl += "player";
        if (freshReload) playerUrl += "?freshReload=1";
        currentPlayerUrl = playerUrl;
        webView.loadUrl(playerUrl);
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

    // ---- Crash resilience: renderer recovery, memory pressure, ANR watchdog ----

    private final long[] renderGoneTimestamps = new long[3];
    private int renderGoneIdx = 0;
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
        long now = System.currentTimeMillis();
        renderGoneTimestamps[renderGoneIdx % renderGoneTimestamps.length] = now;
        renderGoneIdx++;
        if (renderGoneIdx >= renderGoneTimestamps.length) {
            long oldest = Long.MAX_VALUE;
            for (long t : renderGoneTimestamps) { if (t > 0 && t < oldest) oldest = t; }
            if (now - oldest < 60_000L) {
                // Renderer is crash-looping â relaunch the whole activity with backoff.
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
        } catch (Throwable e) {
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
          if (keyCode == KeyEvent.KEYCODE_HOME
                  || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                  || keyCode == KeyEvent.KEYCODE_MENU
                  || keyCode == KeyEvent.KEYCODE_BACK) {
              return true;
          }
          if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                  || keyCode == KeyEvent.KEYCODE_ENTER
                  || keyCode == KeyEvent.KEYCODE_BUTTON_A
                  || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
              long now = System.currentTimeMillis();
              if (now - dpadFirstPressMs > 5_000L) { dpadPressCount = 0; dpadFirstPressMs = now; }
              dpadPressCount++;
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
        webView.onResume();
        // Resume PlaylistScheduler slide timer with remaining duration.
        if (playlistScheduler != null) playlistScheduler.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pause PlaylistScheduler so advance() does not fire while backgrounded.
        if (playlistScheduler != null) playlistScheduler.pause();
        webView.onPause();
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
      /**
       * Task #2119: Amlogic codec drain-before-release.
       * Mirrors the android-tv variant — see that file for full comment.
       */
      private void drainAndRelease(androidx.media3.exoplayer.ExoPlayer p) {
          if (p == null) return;
          if (p.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE) {
              try { p.release(); } catch (Throwable ignored) {}
              return;
          }
          final boolean[] released = {false};
          final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
          final Runnable timeoutRelease = () -> {
              if (released[0]) return;
              released[0] = true;
              try { p.release(); } catch (Throwable ignored) {}
          };
          p.addListener(new androidx.media3.common.Player.Listener() {
              @Override public void onPlaybackStateChanged(int state) {
                  if (state == androidx.media3.common.Player.STATE_IDLE) {
                      if (released[0]) return;
                      released[0] = true;
                      h.removeCallbacks(timeoutRelease);
                      try { p.removeListener(this); } catch (Throwable ignored) {}
                      try { p.release(); } catch (Throwable ignored) {}
                  }
              }
          });
          try { p.stop(); } catch (Throwable ignored) {}
          h.postDelayed(timeoutRelease, 150);
      }

      private void releaseAllRenderers() {
          try {
              if (videoReadyHandler != null && videoReadyRunnable != null) {
                  videoReadyHandler.removeCallbacks(videoReadyRunnable);
                  videoReadyHandler = null; videoReadyRunnable = null;
              }
              if (nativeVideoListener != null) {
                  if (exoPlayer != null) {
                      try { exoPlayer.removeListener(nativeVideoListener); } catch (Throwable ignored) {}
                  }
                  nativeVideoListener = null;
              }
              // Task #2119: drain before release — Amlogic codec buffer flush.
              if (exoPlayer != null) {
                  drainAndRelease(exoPlayer);
                  exoPlayer = null;
              }
              if (preloadPlayer != null) {
                  drainAndRelease(preloadPlayer);
                  preloadPlayer = null; preloadedVideoUrl = null; preloadVideoReady = false;
              }
              if (pendingOldPlayer != null) {
                  drainAndRelease(pendingOldPlayer);
                  pendingOldPlayer = null;
              }
              if (nativeVideoView != null) { try { nativeVideoView.setPlayer(null); } catch (Throwable ignored) {} nativeVideoView.setVisibility(View.INVISIBLE); }
              if (nativeVideoViewB != null) { try { nativeVideoViewB.setPlayer(null); } catch (Throwable ignored) {} nativeVideoViewB.setVisibility(View.INVISIBLE); }
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
        if (hardwareManager != null) {
            hardwareManager.stop();
        }
        if (isAutoRelaunchEnabled() && !isUserClosing) {
            scheduleAppRelaunch(3000);
        }
        // AppRecoverManager (WorkManager) is reserved for ReliabilitySupervisor.hardRecover().
        // Using it alongside scheduleAppRelaunch creates a recovery race condition.
        if (webView != null) {
            webView.destroy();
        }
        activityAlive = false;
        releaseAllRenderers();
        if (videoCache != null) { try { videoCache.release(); } catch (Throwable ignored) {} videoCache = null; }
        super.onDestroy();
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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
              android.widget.FrameLayout root = (android.widget.FrameLayout) getWindow().getDecorView();
              android.widget.TextView diagTv = new android.widget.TextView(this);
              diagTv.setBackgroundColor(0xCC000000);
              diagTv.setTextColor(0xFFFFFFFF);
              diagTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
              diagTv.setPadding(60, 60, 60, 60);
              diagTv.setGravity(android.view.Gravity.CENTER);
              String ip = "unavailable";
              try {
                  WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                  if (wm != null) ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
              } catch (Throwable ignored) {}
              long uptimeSec = android.os.SystemClock.elapsedRealtime() / 1000;
              String uptime = String.format("%dh %02dm %02ds", uptimeSec / 3600, (uptimeSec % 3600) / 60, uptimeSec % 60);
              String ver = "?";
              try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
              diagTv.setText("DIGIPAL DIAGNOSTICS\n\nPackage: " + getPackageName()
                      + "\nVersion:  " + ver
                      + "\nIP:       " + ip
                      + "\nUptime:   " + uptime
                      + "\n\nPress SELECT again or wait 10s to dismiss");
              android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                      android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                      android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
              root.addView(diagTv, lp);
              diagnosticsOverlay = diagTv;
              diagDismissHandler.postDelayed(this::hideDiagnosticsOverlay, 10_000L);
          } catch (Throwable e) {
              android.util.Log.e("Digipal", "showDiagnosticsOverlay failed", e);
          }
      }

      private void hideDiagnosticsOverlay() {
          try {
              diagDismissHandler.removeCallbacksAndMessages(null);
              if (diagnosticsOverlay != null) {
                  android.widget.FrameLayout root = (android.widget.FrameLayout) getWindow().getDecorView();
                  root.removeView(diagnosticsOverlay);
                  diagnosticsOverlay = null;
              }
          } catch (Throwable ignored) {}
      }
  
}
