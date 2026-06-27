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
import android.os.PowerManager;
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
  import android.text.format.Formatter;
  public class MainActivity extends Activity {

    /** Set true in onResume, false in onStop â read by WatchdogService.inForeground(). */
    public static volatile boolean activityVisible = false;
    /** Set true in onCreate, false in onDestroy â used by WatchdogService to detect real crashes vs Home-press. */
    public static volatile boolean activityAlive = false;

    private WebView webView;
    private FrameLayout rootLayout;
    private FrameLayout errorContainer;
    private PowerManager.WakeLock wakeLock;
    private MediaDownloadManager mediaDownloadManager;
    private static final String PREFS_NAME = "DigipalPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SERVER_MODE = "server_mode";
    private static final String KEY_AUTO_RELAUNCH = "auto_relaunch";
    private static final String KEY_CHECK_SEC = "relaunch_check_sec";
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
    private android.widget.ImageView nativeImageView;
    private androidx.media3.exoplayer.ExoPlayer exoPlayer;
      // ExoPlayer on-disk video cache (OptiSigns-style LRU, 2 GB max)
      private static androidx.media3.datasource.cache.SimpleCache videoCache;
      private static final long VIDEO_CACHE_SIZE = 2L * 1024 * 1024 * 1024; // 2 GB
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
    // Player URL tracked so WebView can be recreated with the same page between slides
    private String currentPlayerUrl = null;

    // Room-based offline playlist cache â survives boot, no blank screen on restart
    private CacheDatabase.AppDatabase cacheDb;

    private static final java.util.regex.Pattern PRIVATE_IP_PATTERN = java.util.regex.Pattern.compile(
        "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}" +
        "|192\\.168\\.\\d{1,3}\\.\\d{1,3}" +
        "|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|localhost" +
        "|\\[::1\\])$"
    );

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
              if (_prevCrashHandler != null) _prevCrashHandler.uncaughtException(thread, throwable);
              else android.os.Process.killProcess(android.os.Process.myPid());
          });
        // Initialise Room offline cache (build() is non-blocking; first query opens file on bg thread)
        cacheDb = CacheDatabase.getInstance(this);
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

        // Native video overlay (ExoPlayer PlayerView) â sits above WebView and errorContainer
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
        // Native image overlay (Glide ImageView) â sits above nativeVideoView
        nativeImageView = new RecyclingSafeImageView(this);
        nativeImageView.setVisibility(View.INVISIBLE);
        root.addView(nativeImageView, new FrameLayout.LayoutParams(1, 1));
        // Dual-buffer B image view â preloaded image loads here while A is visible
        nativeImageViewB = new RecyclingSafeImageView(this);
        nativeImageViewB.setVisibility(View.INVISIBLE);
        root.addView(nativeImageViewB, new FrameLayout.LayoutParams(1, 1));

        setContentView(root);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "digipal:wakelock"
        );

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
        startHeartbeatWatchdog();
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
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                hasHttpError = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!hasHttpError) {
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
                                  js.append("window.__digipalOfflineCache[\"")
                                    .append(item.key.replace("\\", "\\\\").replace("\"", "\\\""))
                                    .append("\"] = ").append(item.json).append(";");
                              }
                              final String script = js.toString();
                              runOnUiThread(() -> _wv.evaluateJavascript(script, null));
                          } catch (Throwable e) {
                              android.util.Log.w("Digipal", "Offline cache inject: " + e.getMessage());
                          }
                      }).start();
                  }
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    hasHttpError = true;
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
                        showError("Connecting...", "Server is starting up. Retrying...");
                        retryConnection();
                    }
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
            if (prefs.getBoolean(KEY_AUTO_RELAUNCH, true)) {
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
            if (prefs.getBoolean("cloud_pairing_pending", false)) {
                String serverUrl = (url != null && !url.isEmpty()) ? url : BuildConfig.SERVER_URL;
                prefs.edit()
                    .putString(KEY_SERVER_MODE, "cloud")
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
                              if (preloadVideoReady) {
                                  // First frame already rendered on preloadView surface â instant, zero-black swap
                                  preloadView.setVisibility(View.VISIBLE);
                                  activeView.setVisibility(View.INVISIBLE);
                                  activeVideoViewIsA = !activeVideoViewIsA;
                                  preloadVideoReady = false;
                                  // Release old player now that new content is confirmed visible
                                  pendingOldPlayer = null;
                                  if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                  webView.evaluateJavascript(
                                      "if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
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
                                              preloadView.setVisibility(View.VISIBLE);
                                              activeView.setVisibility(View.INVISIBLE);
                                              activeVideoViewIsA = !activeVideoViewIsA;
                                              preloadVideoReady = false;
                                              pendingOldPlayer = null;
                                              if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                              webView.evaluateJavascript(
                                                  "if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
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
                                          webView.evaluateJavascript(
                                              "if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                                      }
                                  };
                                  exoPlayer.addListener(nativeVideoListener);
                                  final android.os.Handler readyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                  final Runnable readyCb = new Runnable() {
                                      @Override public void run() {
                                          videoReadyHandler = null; videoReadyRunnable = null; nativeVideoListener = null;
                                          if (done[0]) return; done[0] = true;
                                          preloadView.setVisibility(View.VISIBLE);
                                          activeView.setVisibility(View.INVISIBLE);
                                          activeVideoViewIsA = !activeVideoViewIsA;
                                          preloadVideoReady = false;
                                          pendingOldPlayer = null;
                                          if (oldPlayer != null) { try { oldPlayer.release(); } catch (Throwable ignored) {} }
                                          webView.evaluateJavascript(
                                              "if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
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
                              preloadView.setPlayer(coldPlayer);
                              preloadView.setResizeMode(resizeMode);
                              preloadView.setLayoutParams(lp);
                              coldPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(url)));
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
                                      android.util.Log.w("DigipalMetrics", "[8s fallback fired] cold-load — onRenderedFirstFrame never arrived, latencyMs=" + diagFbMs);
                                      preloadView.setVisibility(View.VISIBLE);
                                      activeView.setVisibility(View.INVISIBLE);
                                      activeVideoViewIsA = !activeVideoViewIsA;
                                      pendingOldPlayer = null;
                                      if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} }
                                      webView.evaluateJavascript(
                                          "if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
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
                                          preloadView.setVisibility(View.VISIBLE);
                                          activeView.setVisibility(View.INVISIBLE);
                                          activeVideoViewIsA = !activeVideoViewIsA;
                                          pendingOldPlayer = null;
                                          if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} }
                                          webView.evaluateJavascript(
                                              "if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
                                          webView.evaluateJavascript("if(window.__digipalNativeMetrics)window.__digipalNativeMetrics({type:'videoReady',path:'" + diagPath + "',latencyMs:" + diagLatencyMs + "})", null);
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
                                      // On error: swap to preloadView anyway so old content doesn't linger
                                      preloadView.setVisibility(View.VISIBLE);
                                      activeView.setVisibility(View.INVISIBLE);
                                      activeVideoViewIsA = !activeVideoViewIsA;
                                      pendingOldPlayer = null;
                                      if (oldPlayer != null) { try { oldPlayer.stop(); oldPlayer.release(); } catch (Throwable ignored) {} }
                                      android.util.Log.w("DigipalMetrics", "[cold onPlayerError fatal] advancing playlist");
                                      webView.evaluateJavascript(
                                          "if(typeof window.__digipalNativeVideoReady==='function')window.__digipalNativeVideoReady()", null);
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
                        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }
                        // Also release any old player held alive during a swap-wait
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
                            webView.evaluateJavascript(
                                "if(typeof window.__digipalNativeImageReady==='function')window.__digipalNativeImageReady()", null);
                        } else {
                            // Fallback: load directly into active view
                            activeImgView.setLayoutParams(lp);
                            activeImgView.setScaleType(st);
                            activeImgView.setVisibility(View.INVISIBLE);
                            com.bumptech.glide.Glide.with(MainActivity.this)
                                .load(url)
                                .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                    @Override
                                    public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                            Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                            boolean isFirstResource) {
                                        webView.evaluateJavascript(
                                            "if(typeof window.__digipalNativeImageReady==='function')window.__digipalNativeImageReady()", null);
                                        return false;
                                    }
                                    @Override
                                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                            com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                        activeImgView.setVisibility(View.VISIBLE);
                                        webView.evaluateJavascript(
                                            "if(typeof window.__digipalNativeImageReady==='function')window.__digipalNativeImageReady()", null);
                                        return false;
                                    }
                                })
                                .into(activeImgView);
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
                          preloadView.setPlayer(preloadPlayer);
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
            // Fallback: try non-exact alarm if exact alarm permission not granted
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

    private void loadPlayerUrl(String baseUrl) {
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
        playerUrl += "player?platform=android_tv";
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
          return new androidx.media3.exoplayer.ExoPlayer.Builder(this)
              .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory))
              .build();
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
            loadPlayerUrl(getServerUrl());
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
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
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
                String url = webView != null ? webView.getUrl() : null;
                if (webView != null && url != null && !url.startsWith("about:")) {
                    webView.reload();
                } else {
                    loadPlayerUrl(getServerUrl());
                }
            } catch (Exception e) {
                try { loadPlayerUrl(getServerUrl()); } catch (Exception ignored) {}
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

    private void openSetupScreen() {
        isUserClosing = true;
        Intent intent = new Intent(this, ServerSetupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
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
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(24 * 60 * 60 * 1000L);
        }
        hideSystemUI();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        webView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityVisible = false;
    }

    @Override
    protected void onDestroy() {
        stopAnrWatchdog();
        stopHeartbeatWatchdog();
        if (isAutoRelaunchEnabled() && !isUserClosing) {
            scheduleAppRelaunch(3000);
        }
        // Schedule WorkManager crash recovery on unexpected (non-user) exit
        if (!isUserClosing) {
            AppRecoverManager.scheduleRecovery(this);
        }
        if (webView != null) {
            webView.destroy();
        }
        if (videoReadyHandler != null && videoReadyRunnable != null) {
            videoReadyHandler.removeCallbacks(videoReadyRunnable);
            videoReadyHandler = null; videoReadyRunnable = null;
        }
        if (exoPlayer != null) { exoPlayer.release(); exoPlayer = null; }
        activityAlive = false;
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
          diagDismissHandler.removeCallbacksAndMessages(null);
          if (diagnosticsOverlay != null) {
              try {
                  android.widget.FrameLayout root = (android.widget.FrameLayout) getWindow().getDecorView();
                  root.removeView(diagnosticsOverlay);
              } catch (Throwable ignored) {}
              diagnosticsOverlay = null;
          }
      }
  
}