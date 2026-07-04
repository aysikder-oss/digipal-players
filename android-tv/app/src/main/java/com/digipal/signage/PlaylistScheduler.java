package com.digipal.signage;

import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PlaylistScheduler — native state machine that owns playlist timing.
 *
 * Unlike NativePlaylistManager (JS-driven), this class:
 *   - Restores last known-good playlist from Room on boot (no network needed)
 *   - Manages WebView dormant state to reclaim 50-100 MB on Fire TV 4K Plus
 *   - Implements soft recovery: extend current slide on renderer failure
 *   - Reports all state transitions to TelemetryManager
 *
 * Reliability improvements (v3.12.0):
 *   - Generation counter: stale advance() callbacks from superseded setPlaylist()
 *     calls are silently dropped, preventing wrong-slide advancement.
 *   - Per-slide retry: onRendererError() retries the same slide up to 3x before
 *     skipping to the next one (prevents flash-bang on transient decode errors).
 *   - Empty playlist stop: setPlaylist("[]") now calls stop() + clears the active
 *     Room revision so boot() won't restore stale content after a commanded reload.
 *   - Pause/resume: pause() records remaining slide time; resume() reschedules
 *     the advance callback for exactly the remaining duration.
 *
 * State machine:
 *   IDLE -> BOOTING -> RESTORING_LAST_GOOD -> PREPARING_CURRENT
 *   -> PLAYING -> PREPARING_NEXT -> TRANSITIONING -> PLAYING (loop)
 *   Any state -> DEGRADED_PLAYBACK -> RECOVERING_RENDERER -> PLAYING
 */
public class PlaylistScheduler {

    private static final String TAG = "PlaylistScheduler";
    private static final int MAX_FAILURES = 5; // consecutive failed *slides* before DEGRADED_PLAYBACK (P2)
    private static final int MAX_SLIDE_RETRIES = 2; // retries for the *same* slide before skipping (P2)
    private static final long FALLBACK_EXTEND_MS = 5_000L;

    public enum State {
        IDLE, BOOTING, RESTORING_LAST_GOOD,
        PREPARING_CURRENT, PLAYING,
        PREPARING_NEXT, TRANSITIONING,
        DEGRADED_PLAYBACK, RECOVERING_RENDERER
    }

    public enum SlideType { VIDEO, IMAGE, WEBVIEW_DESIGN, WEBVIEW_KIOSK, WEBVIEW_WEBSITE, WEBVIEW_WIDGET, WEBVIEW_CANVA, WEBVIEW_PDF, WEBVIEW_TEXT, WEBVIEW_AUDIO, WEBVIEW_DIRECTORY, WEBVIEW_URL }

      /** True for any WEBVIEW_* variant -- all of them are dispatched to the isolated
       *  per-slide WebView renderer via the same {@code default:} branch in
       *  {@link #showCurrent}; only VIDEO/IMAGE have a dedicated native path. Kept as a
       *  single source of truth so adding a new WEBVIEW_* category never requires
       *  touching every WEBVIEW_DESIGN/WEBVIEW_KIOSK/WEBVIEW_URL enumeration site. */
      public static boolean isWebviewType(SlideType t) {
          return t != null && t != SlideType.VIDEO && t != SlideType.IMAGE;
      }

    public static class SlidePlan {
        public String slideId = "";
        public SlideType type = SlideType.WEBVIEW_URL;
        public String url = "";
        public long durationMs = 10_000;
        public int contentId;
        public String objectFit = "contain";
        public boolean loop = true;
        public float volume = 0f;
        public String scaleType = "contain";
        public String fallbackUrl = "";
        /** "native" / "pre-rendered" / "webview" — set by the client for design content
         *  (renderer observability task); only the client knows whether an IMAGE slide is
         *  a real image or a pre-rendered design snapshot (task #1879). Empty for non-design slides. */
        public String renderMode = "";
        /** PDF-only: per-page duration in ms sent by the client (contentSettings.pdfPageDuration).
         *  -1 means unset -- expandPdfIfPrerendered() falls back to durationMs per page. */
        public long pdfPageDurationMs = -1;
    }

    public interface AssetResolver {
        /** Returns an absolute local file path if a READY local copy exists; null otherwise.
         *  Must be safe to call on the main thread (e.g. a ConcurrentHashMap lookup). */
        String resolveLocalPath(String url);
    }

    public interface Delegate {
        void schedulerPlayVideo(SlidePlan slide);
        void schedulerShowImage(SlidePlan slide);
        void schedulerPreloadVideo(SlidePlan slide);
        void schedulerPreloadImage(SlidePlan slide);
        void schedulerDeactivateWebView();
        // Isolated per-slide WebView renderer (task #1875) is the only WebView-delegated
        // rendering path for WEBVIEW_DESIGN/WEBVIEW_KIOSK/WEBVIEW_URL slides (task #1886).
        void schedulerActivateIsolatedRenderer(SlidePlan slide);
        void schedulerDeactivateIsolatedRenderer();
        void schedulerStopVideo();
        void schedulerHideImage();
        void schedulerOnStateChanged(State state, String slideId);
    }

    /** Isolated per-slide WebView renderer (task #1875) is now the only WebView-delegated
     *  rendering path for WEBVIEW_DESIGN/WEBVIEW_KIOSK/WEBVIEW_URL slides. The legacy
     *  shared long-lived WebView (__digipalGotoSlide) path was retired in task #1886. */

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Delegate delegate;
    private final PlaylistRepository repository;
    private final TelemetryManager telemetry;
    private final ExecutorService dbExec = Executors.newSingleThreadExecutor();

    private State state = State.IDLE;
    private List<SlidePlan> slides = new ArrayList<>();
    private int currentIndex = 0;
    private boolean running = false;
    private int consecutiveFailures = 0;
    private long activeRevisionId = -1;
    private long slideStartMs;
    private Runnable advanceRunnable;

    /** Incremented on every stop()/setPlaylist() to invalidate stale advance callbacks. */
    private int generation = 0;

    /** Optional resolver for local-first media delivery. Set by MainActivity after init. */
    private AssetResolver assetResolver;
    public void setAssetResolver(AssetResolver r) { this.assetResolver = r; }

    /** Optional — set by MainActivity after init so baseline renderer diagnostics can
     *  report which memory tier was active for a given slide dispatch (baseline renderer
     *  diagnostics task). Purely observational; never gates renderer decisions itself. */
    private MemoryBudgetManager memoryBudgetManager;
    public void setMemoryBudgetManager(MemoryBudgetManager m) { this.memoryBudgetManager = m; }
    private String currentMemoryTier() {
        return memoryBudgetManager != null ? memoryBudgetManager.getCurrentTier().name() : "unknown";
    }

    /** Renderer kind used for the currently dispatched slide — one of "native_video",
     *  "native_image", "isolated_webview", "main_webview" (baseline renderer diagnostics task). */
    private String currentRendererKind = "";

    /** Slide id / content id / render mode of the currently dispatched slide, kept in
     *  sync with currentRendererKind for the debug telemetry panel (debug/telemetry task). */
    private volatile String currentSlideId = "";
    private volatile int currentContentId = 0;
    private volatile String currentRenderMode = "";
    /** Wall-clock timestamps (System.currentTimeMillis()) of the most recent renderer
     *  lifecycle events, surfaced via getRendererStatus() so the debug overlay can show
     *  staleness (e.g. a stuck renderer shows no ready/error signal for a long time). 0 = never. */
    private volatile long lastReadyAtMs = 0L;
    private volatile long lastErrorAtMs = 0L;
    private volatile String lastErrorMessage = "";

    // ── Full renderer observability (renderer observability & telemetry task) ──────────
    /** Name of the WebViewPolicy applied to the current/last WebView-based slide.
     *  Set by MainActivity right after it computes the policy (per-asset WebView policy
     *  task); "" for native slides where no WebView policy applies. */
    private volatile String lastWebViewPolicy = "";
    public void setLastWebViewPolicy(String name) { lastWebViewPolicy = name == null ? "" : name; }
    /** "local" or "remote" — which player shell served this boot. Set once by MainActivity
     *  from PlayerShellManager.getLastBootSource() after the boot decision is made. */
    private volatile String shellSource = "unknown";
    public void setShellSource(String source) { shellSource = source == null ? "unknown" : source; }
    /** True if the current slide dispatch fell back away from its primary renderer
     *  (isolated-webview failure, degraded playback). Reset at the top of every showCurrent(). */
    private boolean lastFallbackUsed = false;

    // ── Debug status panel getters (surfaced to JS via WebAppInterface.getRendererStatus) ──
    public String getCurrentRendererKind() { return currentRendererKind; }
    public String getLastWebViewPolicyName() { return lastWebViewPolicy; }
    /** P2: expose per-slide retry count and consecutive-failure count for debug status. */
    public int getRetryCountForSlide() { return retryCountForSlide; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public String getShellSourceName() { return shellSource; }
    public boolean isLastFallbackUsed() { return lastFallbackUsed; }
    public String getMemoryTierName() { return currentMemoryTier(); }
    public String getCurrentSlideId() { return currentSlideId; }
    public int getCurrentContentId() { return currentContentId; }
    public String getCurrentRenderMode() { return currentRenderMode; }
    public long getLastReadyAtMs() { return lastReadyAtMs; }
    public long getLastErrorAtMs() { return lastErrorAtMs; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    /** Isolated renderer is now the only WebView-delegated rendering path (task #1886). */
    public static boolean isIsolatedRendererFeatureEnabled() { return true; }

      /** Pass MediaDownloadManager to repository for the atomic revision pipeline. */
      public void setMediaDownloadManager(MediaDownloadManager mdm) { repository.setMediaDownloadManager(mdm); }

      /** Pass WebView to repository so the pipeline can fire failure metrics events. */
      public void setWebView(android.webkit.WebView wv) { repository.setWebView(wv); }

  
    /** slideId currently being retried; "" when no retry is in progress. Reset only on
     *  genuine slide change (advance/skip/stop/setPlaylist), NOT at the top of showCurrent()
     *  (P2 fix: showCurrent() is also called to re-render the SAME slide during a retry). */
    private String retrySlideId = "";
    /** Number of consecutive failures for retrySlideId. Reset with retrySlideId. */
    private int retryCountForSlide = 0;

    /** Wall-clock time when pause() was called; -1 when not paused. */
    private long pausedAt = -1;

    /** Remaining slide time captured at pause(); -1 when not paused. */
    private long remainingMs = -1;

    // ── Wall-clock playlist timing ──────────────────────────────────────────
    /** SharedPreferences for persisting playlist epoch across process death. */
    private android.content.SharedPreferences prefs;
    private static final String KEY_PLAYLIST_EPOCH = "playlist_epoch_ms";
    private static final String KEY_PLS_WAS_STOPPED = "pls_was_stopped";
    /**
     * When > 0: use this as the advance-timer duration for the first slide only (wall-clock resume).
     * Reset to -1 immediately after first use in showCurrent().
     */
    private long firstSlideRemainingMs = -1L;
    /** Last JSON passed to setPlaylist(); used to re-parse local manifest on onReady. */
    private String lastSetJson = "";

    // ── Asset readiness grace period ────────────────────────────────────────
    /** Retry count for empty-URL grace period; resets whenever a slide has a usable URL. */
    private int assetGraceRetries = 0;
    /** Max 1-second retries before giving up and advancing past an unresolvable slide. */
    private static final int MAX_ASSET_GRACE_RETRIES = 30; // 30 × 1 s = 30 s max wait

    // ── Renderer-ready gate ─────────────────────────────────────────────────────────────
    /** Slide advance duration captured while waiting for renderer first-frame confirmation. */
    private long pendingAdvanceDurationMs = -1L;
    /** Generation at time the renderer-ready timeout was posted; -1 when not waiting. */
    private int rendererReadyTimeoutGen = -1;
    /** Safety runnable: starts advance timer if renderer never calls onRendererReady(). */
    private Runnable rendererReadyTimeout;
    /** Max time to wait for first-frame signal before starting the slide timer anyway. */
    private static final long RENDERER_READY_TIMEOUT_MS = 3_000L;

    public PlaylistScheduler(Delegate delegate, PlaylistRepository repository, TelemetryManager telemetry,
                             android.content.SharedPreferences prefs) {
        this.delegate = delegate;
        this.repository = repository;
        this.telemetry = telemetry;
        this.prefs = prefs;
    }

    /** Called from MainActivity.onCreate() -- restores last playlist from Room if available. */
    public void boot() {
        toState(State.BOOTING, "");
        dbExec.execute(() -> {
            // Honour a prior JS stop signal across reboots — prevents a removed video from
            // replaying when the device restarts before the server is reachable.
            if (prefs != null && prefs.getBoolean(KEY_PLS_WAS_STOPPED, false)) {
                Log.i(TAG, "[boot] pls_was_stopped=true — skipping Room restore, staying IDLE");
                handler.post(() -> toState(State.IDLE, ""));
                return;
            }
            PlaylistDatabase.PlaylistRevisionEntity rev = repository.getActive();
            if (rev != null) {
                List<PlaylistDatabase.SlideEntity> ents = repository.getSlidesForRevision(rev.id);
                List<SlidePlan> plans = entitiesToPlans(ents);
                handler.post(() -> {
                    if (!plans.isEmpty()) {
                        Log.i(TAG, "[boot] Restored " + plans.size() + " slides from Room");
                        toState(State.RESTORING_LAST_GOOD, "");
                        slides = plans;
                        activeRevisionId = rev.id;
                        // ── Wall-clock resume: jump to the slide that should be playing now ──
                        int[] wc = computeWallClockIndex(prefs);
                        currentIndex = wc[0];
                        firstSlideRemainingMs = wc[1];
                        Log.i(TAG, "[boot] wall-clock index=" + currentIndex
                                + " remainingMs=" + firstSlideRemainingMs);
                        running = true;
                        showCurrent();
                    } else {
                        toState(State.IDLE, "");
                    }
                });
            } else {
                handler.post(() -> toState(State.IDLE, ""));
            }
        });
    }

    /**
     * Called from setNativePlaylist JS bridge.
     * Passing "[]" stops playback and clears the active Room revision so boot()
     * does not restore stale content after a commanded page reload.
     */
    public synchronized void setPlaylist(String json) {
        lastSetJson = json;
        List<SlidePlan> newSlides = parseSlides(json);
        if (newSlides.isEmpty()) {
            stop();
            dbExec.execute(() -> repository.clearActiveRevision());
            // Persist stop signal across device reboots — boot() stays IDLE even when
            // the server is unreachable, preventing a removed video from ghost-playing.
            if (prefs != null) {
                prefs.edit().putBoolean(KEY_PLS_WAS_STOPPED, true).apply();
            }
            Log.i(TAG, "[setPlaylist] empty -- stopped and cleared active revision");
            return;
        }

        if (isSameStructure(newSlides)) {
            // Fix 4: check if the active video's signed URL changed — reload ExoPlayer seamlessly
            // so it doesn't serve an expired URL after ~1 hour of native playback.
            final SlidePlan activeOld = (currentIndex >= 0 && currentIndex < slides.size()) ? slides.get(currentIndex) : null;
            final SlidePlan activeNew = (currentIndex >= 0 && currentIndex < newSlides.size()) ? newSlides.get(currentIndex) : null;
            slides = newSlides;
            if (prefs != null) prefs.edit().remove(KEY_PLS_WAS_STOPPED).apply();
            if (activeOld != null && activeNew != null
                    && activeOld.type == SlideType.VIDEO
                    && !activeOld.url.equals(activeNew.url)
                    && running && delegate != null) {
                Log.i(TAG, "[setPlaylist] signed URL changed for active video contentId=" + activeNew.contentId + " — reloading ExoPlayer");
                final Delegate _d = delegate; final SlidePlan _slide = activeNew;
                handler.post(() -> _d.schedulerPlayVideo(_slide));
            } else {
                Log.d(TAG, "[setPlaylist] URL refresh -- keeping index=" + currentIndex);
            }
            return;
        }

        // Clear the was-stopped flag — real content is incoming.
        if (prefs != null) prefs.edit().remove(KEY_PLS_WAS_STOPPED).apply();
        stop();
        slides = newSlides;
        currentIndex = 0;
        consecutiveFailures = 0;

        final String finalJson = json;
        dbExec.execute(() -> {
              repository.startRevisionPipeline("default", finalJson, new PlaylistRepository.OnRevisionReady() {
                  @Override
                  public void onReady(long revId, String localManifestJson) {
                      repository.promoteRevisionToActive(revId);
                      activeRevisionId = revId;
                      Log.i(TAG, "[setPlaylist] revision activated revId=" + revId);
                      // Swap slides to local-file-resolved paths — the ONLY place
                      // raw /objects/ URLs are replaced with file:// URIs.
                      if (localManifestJson != null && !localManifestJson.isEmpty()) {
                          List<SlidePlan> local = parseSlides(localManifestJson);
                          if (!local.isEmpty()) {
                              handler.post(() -> {
                                  synchronized (PlaylistScheduler.this) {
                                      if (running && activeRevisionId == revId) {
                                          slides = local;
                                          Log.i(TAG, "[setPlaylist] slides updated from local manifest ("
                                                  + local.size() + " items)");
                                      }
                                  }
                              });
                          }
                      }
                  }
                  @Override
                  public void onFailed(long revId, String reason) {
                      Log.w(TAG, "[setPlaylist] pipeline failed revId=" + revId + " reason=" + reason);
                  }
              });
          });

        // Record wall-clock epoch so boot() can resume at the right slide after crash/restart
        if (prefs != null) {
            prefs.edit().putLong(KEY_PLAYLIST_EPOCH, System.currentTimeMillis()).apply();
        }
        firstSlideRemainingMs = -1L; // fresh start — no wall-clock offset
        running = true;
        showCurrent();
    }

    /** Force-advance to the next slide immediately (RecoveryCoordinator: SLIDE_SKIP). */
      public synchronized void skipCurrentSlide() {
          if (!running) return;
          retrySlideId = ""; retryCountForSlide = 0;
          if (advanceRunnable != null) { handler.removeCallbacks(advanceRunnable); advanceRunnable = null; }
          final int myGen = generation;
          handler.post(() -> { if (generation == myGen && running) advance(); });
      }

      /** Retry the current slide after 500 ms (RecoveryCoordinator: SLIDE_RETRY). */
      public synchronized void retryCurrentSlide() {
          if (!running) return;
          if (advanceRunnable != null) { handler.removeCallbacks(advanceRunnable); advanceRunnable = null; }
          final int myGen = generation;
          handler.postDelayed(() -> { if (generation == myGen && running) showCurrent(); }, 500);
      }

      /** Stop playback and cancel pending timer. */
      public synchronized void stop() {
        running = false;
        pausedAt = -1;
        remainingMs = -1;
        generation++;
        if (advanceRunnable != null) {
            handler.removeCallbacks(advanceRunnable);
            advanceRunnable = null;
        }
        if (rendererReadyTimeout != null) {
            handler.removeCallbacks(rendererReadyTimeout);
            rendererReadyTimeout = null;
        }
        rendererReadyTimeoutGen = -1;
        toState(State.IDLE, "");
        // Clear lingering native renderers immediately so old content vanishes on playlist switch
        if (delegate != null) {
            delegate.schedulerStopVideo();
            delegate.schedulerHideImage();
        }
    }

    /**
     * Pause slide timing. Records remaining duration so resume() can restart
     * the advance timer for exactly the time that was left.
     * Called from MainActivity.onPause().
     */
    public synchronized void pause() {
        if (!running || pausedAt >= 0) return;
        pausedAt = SystemClock.elapsedRealtime(); // Fix 13: monotonic — safe across DST changes
        long elapsed = pausedAt - slideStartMs;
        SlidePlan cur = (currentIndex < slides.size()) ? slides.get(currentIndex) : null;
        long dur = (cur != null) ? Math.max(1_000L, cur.durationMs) : 10_000L;
        remainingMs = Math.max(1_000L, dur - elapsed);
        if (advanceRunnable != null) handler.removeCallbacks(advanceRunnable);
        Log.d(TAG, "[pause] remainingMs=" + remainingMs);
    }

    /**
     * Resume slide timing. Reschedules the advance callback for the remaining
     * duration captured at pause() time.
     * Called from MainActivity.onResume().
     */
    public synchronized void resume() {
        if (!running || pausedAt < 0) return;
        long delay = (remainingMs > 0) ? remainingMs : 1_000L;
        pausedAt = -1;
        remainingMs = -1;
        final int myGen = generation;
        advanceRunnable = () -> {
            if (generation != myGen) return;
            advance();
        };
        handler.postDelayed(advanceRunnable, delay);
        Log.d(TAG, "[resume] rescheduled advance in " + delay + "ms");
    }

    /** Called when a renderer signals it is ready (first frame decoded). */
    public void onRendererReady(String slideId) {
        lastReadyAtMs = System.currentTimeMillis();
        // Stale-callback guard: a renderer (isolated WebView, ExoPlayer) can report
        // ready asynchronously after the scheduler has already advanced past the
        // slide that requested it (fast user-triggered skip, rapid playlist swap).
        // Without this check a late callback can incorrectly force State.PLAYING /
        // restart the advance timer for a slide that is no longer current.
        if (!slides.isEmpty() && currentIndex < slides.size()
                && !slides.get(currentIndex).slideId.equals(slideId)) {
            Log.d(TAG, "[ready] ignoring stale callback for " + slideId
                    + " (current=" + slides.get(currentIndex).slideId + ")");
            return;
        }
        long readyMs = SystemClock.elapsedRealtime() - slideStartMs; // Fix 13: monotonic
        Log.d(TAG, "[ready] " + slideId + " in " + readyMs + "ms");
        if (telemetry != null) telemetry.logEvent("slide_ready", slideId,
                "{\"readyMs\":" + readyMs
                + ",\"readyLatencyMs\":" + readyMs
                + ",\"firstFrameLatencyMs\":" + readyMs
                + ",\"rendererKind\":\"" + currentRendererKind + "\""
                + ",\"memoryTier\":\"" + currentMemoryTier() + "\""
                + ",\"webViewPolicy\":\"" + lastWebViewPolicy + "\""
                + ",\"shellSource\":\"" + shellSource + "\""
                + ",\"fallbackUsed\":" + lastFallbackUsed
                + ",\"loadTimeout\":false"
                + ",\"rendererCrash\":false}");
        // Cancel the safety timeout and start the advance timer — renderer confirmed
        // first-frame so the full configured slide duration is preserved.
        if (state == State.PREPARING_CURRENT && rendererReadyTimeoutGen == generation) {
            if (rendererReadyTimeout != null) {
                handler.removeCallbacks(rendererReadyTimeout);
                rendererReadyTimeout = null;
            }
            rendererReadyTimeoutGen = -1;
            toState(State.PLAYING, slideId);
            startAdvanceTimer(generation, pendingAdvanceDurationMs);
        }
    }

    /**
     * Called when a looping video reaches its natural end and loops back to the start.
     * Cancels the duration-based advance timer and moves to the next slide immediately,
     * preventing the user from seeing 1-2 s of replayed video before the scheduler fires.
     */
    public void onSlideNaturalEnd(String slideId) {
        handler.post(() -> {
            if (state != State.PLAYING) return;
            if (slides.isEmpty() || currentIndex >= slides.size()) return;
            if (!slides.get(currentIndex).slideId.equals(slideId)) return;
            Log.i(TAG, "[VideoLoop][Scheduler] naturalEnd reason=naturalEnd slide=" + slideId);
            if (advanceRunnable != null) { handler.removeCallbacks(advanceRunnable); advanceRunnable = null; }
            advance();
        });
    }

    /**
     * Called when a renderer encounters an unrecoverable error.
     * Retries the same slide up to MAX_SLIDE_RETRIES times before advancing.
     * Goes DEGRADED only after MAX_FAILURES consecutive failures.
     */
    public void onRendererError(String slideId, String error) {
        lastErrorAtMs = System.currentTimeMillis();
        lastErrorMessage = error == null ? "" : error;
        // Stale-callback guard: mirrors onRendererReady() -- ignore errors reported
        // for a slide that is no longer the one the scheduler is currently showing
        // (e.g. a superseded isolated WebView that keeps loading in the background
        // after the scheduler already advanced past it).
        if (!slides.isEmpty() && currentIndex < slides.size()
                && !slides.get(currentIndex).slideId.equals(slideId)) {
            Log.d(TAG, "[error] ignoring stale callback for " + slideId
                    + " (current=" + slides.get(currentIndex).slideId + "): " + error);
            return;
        }
        // P2 fix: track retries per-slide via retrySlideId/retryCountForSlide instead of a
        // single global counter reset at the top of showCurrent() (showCurrent() is also
        // used to re-render this SAME slide during a retry, which made the old counter unreliable).
        if (!slideId.equals(retrySlideId)) {
            retrySlideId = slideId;
            retryCountForSlide = 0;
            consecutiveFailures++; // count distinct failed slides, not individual retry attempts
        }
        retryCountForSlide++;
        Log.w(TAG, "[error] " + slideId + ": " + error + " (retry=" + retryCountForSlide + "/" + MAX_SLIDE_RETRIES
                + ", consecutiveFailures=" + consecutiveFailures + "/" + MAX_FAILURES + ")");
        // Bug fix (task P3): a failure that arrives while still PREPARING_CURRENT (i.e.
        // before onRendererReady() ever fired) leaves the 3s RENDERER_READY_TIMEOUT_MS
        // safety runnable armed. Without cancelling it here, that stale timeout can fire
        // mid-retry/mid-degraded-recovery and force State.PLAYING + start a *second*,
        // competing advance timer for the slide that just failed -- corrupting the
        // TRANSITIONING/DEGRADED_PLAYBACK state machine and double-advancing the playlist.
        if (rendererReadyTimeout != null) {
            handler.removeCallbacks(rendererReadyTimeout);
            rendererReadyTimeout = null;
        }
        rendererReadyTimeoutGen = -1;
        if (telemetry != null) telemetry.logEvent("slide_failed", slideId,
                "{\"error\":" + JSONObject.quote(error)
                + ",\"slideRetry\":" + retryCountForSlide
                + ",\"consecutiveFailures\":" + consecutiveFailures
                + ",\"rendererKind\":\"" + currentRendererKind + "\""
                + ",\"memoryTier\":\"" + currentMemoryTier() + "\""
                + ",\"webViewPolicy\":\"" + lastWebViewPolicy + "\""
                + ",\"shellSource\":\"" + shellSource + "\""
                + ",\"fallbackUsed\":" + lastFallbackUsed
                + ",\"loadTimeout\":false"
                + ",\"rendererCrash\":true"
                + ",\"rendererCrashReason\":" + JSONObject.quote(error) + "}");

        if (consecutiveFailures >= MAX_FAILURES) {
            degraded(slideId);
            return;
        }

        if (advanceRunnable != null) handler.removeCallbacks(advanceRunnable);

        if (retryCountForSlide <= MAX_SLIDE_RETRIES) {
            Log.d(TAG, "[error] retrying slide " + slideId + " in 500ms (attempt " + retryCountForSlide + ")");
            final int myGen = generation;
            handler.postDelayed(() -> {
                if (generation != myGen || !running) return;
                showCurrent();
            }, 500);
        } else {
            Log.w(TAG, "[error] max retries for " + slideId + " -- skipping");
            retrySlideId = ""; retryCountForSlide = 0;
            final int myGen = generation;
            handler.postDelayed(() -> {
                if (generation != myGen || !running) return;
                advance();
            }, 300);
        }
    }

    /**
     * Called by the isolated WebView renderer (task #1875) when it fails to load,
     * times out, or errors for a given slide. There is no legacy WebView fallback
     * (retired task #1886) — onRendererError() retries the same slide (which
     * recreates a fresh isolated WebView) up to MAX_SLIDE_RETRIES before skipping,
     * so a single isolated-renderer crash never becomes a hard failure for the screen.
     */
    public void onIsolatedRendererFailed(String slideId, String reason) {
        Log.w(TAG, "[isolated_renderer_failed] " + slideId + ": " + reason);
        if (telemetry != null) telemetry.logEvent("isolated_renderer_failed", slideId,
                "{\"reason\":" + JSONObject.quote(reason)
                + ",\"webViewPolicy\":\"" + lastWebViewPolicy + "\""
                + ",\"shellSource\":\"" + shellSource + "\"}");
        onRendererError(slideId, "isolated_renderer_failed: " + reason);
    }

    private void showCurrent() {
        if (!running || slides.isEmpty()) return;
        if (currentIndex >= slides.size()) currentIndex = 0;

        final SlidePlan slide = slides.get(currentIndex);
        currentSlideId = slide.slideId == null ? "" : slide.slideId;
        currentContentId = slide.contentId;
        currentRenderMode = slide.renderMode == null ? "" : slide.renderMode;

        // ── Asset readiness gate ─────────────────────────────────────────────
        // If a native slide's URL hasn't arrived yet (empty), wait up to 5 s before
        // falling back to the WebView renderer or skipping to the next slide.
        if ((slide.type == SlideType.VIDEO || slide.type == SlideType.IMAGE)
                && (slide.url == null || slide.url.isEmpty())) {
            if (assetGraceRetries < MAX_ASSET_GRACE_RETRIES) {
                assetGraceRetries++;
                Log.w(TAG, "[asset_gate] URL not ready for slide=" + slide.slideId
                        + " (retry " + assetGraceRetries + "/"+MAX_ASSET_GRACE_RETRIES+"), waiting 1s");
                if (telemetry != null) telemetry.logEvent("asset_grace", slide.slideId,
                        "{\"retry\":" + assetGraceRetries + "}");
                final int myGen = generation;
                handler.postDelayed(() -> {
                    if (generation != myGen || !running) return;
                    showCurrent();
                }, 1_000L);
                return;
            } else {
                Log.w(TAG, "[asset_gate] grace period exhausted for slide=" + slide.slideId + " -- advancing");
                if (telemetry != null) telemetry.logEvent("asset_grace_exhausted", slide.slideId, "{}");
                assetGraceRetries = 0;
                advance();
                return;
            }
        }
        assetGraceRetries = 0; // URL present — reset grace counter

        lastFallbackUsed = false; // reset per-slide; set true if isolated renderer/degraded fallback fires
        final long memoryBeforeMb = telemetry != null ? telemetry.currentMemMb() : -1;
        slideStartMs = SystemClock.elapsedRealtime(); // Fix 13: monotonic
        toState(State.PREPARING_CURRENT, slide.slideId);

        SlideType eff = slide.type;
        if ((eff == SlideType.VIDEO || eff == SlideType.IMAGE)
                && (slide.url == null || slide.url.isEmpty())) {
            eff = SlideType.WEBVIEW_URL;
        }

        // Local-first URL resolution: substitute file:// URI if a READY local copy exists.
        // assetResolver must be safe to call on the main thread (ConcurrentHashMap lookup).
        final SlidePlan dispatch;
        if (assetResolver != null
                && (eff == SlideType.VIDEO || eff == SlideType.IMAGE)
                && slide.url != null && !slide.url.isEmpty()) {
            String lp = assetResolver.resolveLocalPath(slide.url);
            if (lp != null && !lp.isEmpty()) {
                SlidePlan copy = new SlidePlan();
                copy.slideId = slide.slideId; copy.type = slide.type;
                copy.url = "file://" + lp;
                copy.durationMs = slide.durationMs; copy.contentId = slide.contentId;
                copy.objectFit = slide.objectFit; copy.loop = slide.loop;
                copy.volume = slide.volume; copy.scaleType = slide.scaleType;
                copy.fallbackUrl = slide.url; // original remote URL preserved as fallback
                dispatch = copy;
                Log.d(TAG, "[local_first] " + slide.slideId + " → " + lp);
            } else {
                dispatch = slide;
            }
        } else {
            dispatch = slide;
        }

        // Compute slide duration first — needed before arming the renderer-ready gate
        // so pendingAdvanceDurationMs is correct if onRendererReady fires synchronously.
        final long dur;
        if (firstSlideRemainingMs > 0) {
            dur = firstSlideRemainingMs;
            firstSlideRemainingMs = -1L;
            Log.d(TAG, "[showCurrent] wall-clock resume: advance in " + dur
                    + "ms (slide " + currentIndex + ")");
        } else {
            dur = Math.max(1_000L, slide.durationMs);
            if (slide.durationMs <= 0) {
                Log.w(TAG, "[showCurrent] zero/negative durationMs=" + slide.durationMs
                        + " for slide=" + slide.slideId + " — clamped to 1000ms");
            }
        }

        // Bug 2 fix: arm rendererReadyTimeoutGen and pendingAdvanceDurationMs BEFORE
        // dispatching to the renderer.  For preloaded videos that are already buffered,
        // schedulerPlayVideo() calls onRendererReady() synchronously on the same thread.
        // If rendererReadyTimeoutGen were still -1 when onRendererReady() ran, the
        // generation check would fail and the 3-second RENDERER_READY_TIMEOUT_MS
        // fallback would fire instead — stalling every preloaded slide by 3 seconds.
        final boolean isNativeSlide = (eff == SlideType.VIDEO || eff == SlideType.IMAGE);
        final boolean isWebviewSlide = isWebviewType(eff);
        final boolean needsReadyGate = isNativeSlide || isWebviewSlide;
        // P1 fix: per-slide-type ready timeout. Native VIDEO/IMAGE keep the original
        // 3s budget (ExoPlayer/Glide first-frame is normally fast). WEBVIEW_* slides use
        // the WebViewPolicy.readyTimeoutMs for their type (e.g. design/kiosk/website/canva
        // pages legitimately need longer than 3s to first-paint on slow/low-RAM devices),
        // falling back to the 3s constant only if a policy leaves it unset.
        final long effectiveReadyTimeoutMs;
        if (isWebviewSlide) {
            WebViewPolicy readyPolicy = WebViewPolicy.forSlideType(eff);
            effectiveReadyTimeoutMs = (readyPolicy != null && readyPolicy.readyTimeoutMs > 0)
                    ? readyPolicy.readyTimeoutMs : RENDERER_READY_TIMEOUT_MS;
        } else {
            effectiveReadyTimeoutMs = RENDERER_READY_TIMEOUT_MS;
        }
        if (needsReadyGate) {
            pendingAdvanceDurationMs = dur;
            rendererReadyTimeoutGen = generation;
            final int myGen = generation;
            final String mySlideId = slide.slideId;
            if (rendererReadyTimeout != null) handler.removeCallbacks(rendererReadyTimeout);
            rendererReadyTimeout = () -> {
                if (generation != myGen) return;
                Log.w(TAG, "[renderer_ready_timeout] no first-frame for slide " + mySlideId
                        + " after " + effectiveReadyTimeoutMs + "ms — starting timer anyway");
                if (telemetry != null) telemetry.logEvent("renderer_timeout", mySlideId,
                        "{\"pendingMs\":" + pendingAdvanceDurationMs
                        + ",\"readyLatencyMs\":" + effectiveReadyTimeoutMs
                        + ",\"firstFrameLatencyMs\":" + effectiveReadyTimeoutMs
                        + ",\"rendererKind\":\"" + currentRendererKind + "\""
                        + ",\"memoryTier\":\"" + currentMemoryTier() + "\""
                        + ",\"webViewPolicy\":\"" + lastWebViewPolicy + "\""
                        + ",\"shellSource\":\"" + shellSource + "\""
                        + ",\"fallbackUsed\":" + lastFallbackUsed
                        + ",\"loadTimeout\":true"
                        + ",\"rendererCrash\":false}");
                rendererReadyTimeout = null; rendererReadyTimeoutGen = -1;
                toState(State.PLAYING, mySlideId);
                startAdvanceTimer(myGen, pendingAdvanceDurationMs);
            };
            handler.postDelayed(rendererReadyTimeout, effectiveReadyTimeoutMs);
        }
        }

        // Renderer ownership contract:
          //  VIDEO / IMAGE  → native renderers (ExoPlayer / Glide via Delegate).
          //                   Scheduler enters PREPARING_CURRENT and waits for onRendererReady().
          //  WEBVIEW_DESIGN / WEBVIEW_KIOSK / WEBVIEW_URL → React TV player (main WebView).
          //                   Scheduler enters PLAYING immediately; advance timer drives the slide.
          //                   Only one path is active at a time: deactivateWebView is called before
          //                   any native render; activateWebView pauses native-loop playback.
          //                   (WebDesignRenderer, WebKioskRenderer, WebSlideRenderer were dead code
          //                    — never instantiated — and have been deleted.)
          switch (eff) {
              case VIDEO:
                  currentRendererKind = "native_video";
                  delegate.schedulerDeactivateWebView();
                  // Release any isolated per-slide WebView left active from a prior
                  // WEBVIEW_DESIGN/KIOSK/URL slide -- without this it stays alive
                  // (and visible on some z-order paths) behind the native video.
                  delegate.schedulerDeactivateIsolatedRenderer();
                  delegate.schedulerPlayVideo(dispatch);
                  break;
              case IMAGE:
                  currentRendererKind = "native_image";
                  delegate.schedulerDeactivateWebView();
                  delegate.schedulerDeactivateIsolatedRenderer();
                  delegate.schedulerShowImage(dispatch);
                  break;
              default:
                  // WEBVIEW_DESIGN / WEBVIEW_KIOSK / WEBVIEW_URL: handed to an isolated
                  // per-slide WebView that is not shared across slides — task #1875.
                  Log.i(TAG, "[dispatch] webview type=" + eff + " slide=" + slide.slideId);
                  // Isolated per-slide WebView renderer (task #1875) is the only WebView-delegated
                  // rendering path (legacy shared long-lived WebView retired task #1886). Any
                  // exception thrown here is caught and routed through onIsolatedRendererFailed,
                  // which retries the same slide via the generic renderer-error path instead of
                  // silently killing the scheduler's dispatch loop.
                  currentRendererKind = "isolated_webview";
                  try {
                      // Release native video/image before handing to the isolated WebView —
                      // prevents old content shadowing the WebView and frees the hardware
                      // decoder (critical on Fire TV).
                      delegate.schedulerStopVideo();
                      delegate.schedulerHideImage();
                      delegate.schedulerDeactivateWebView();
                      delegate.schedulerActivateIsolatedRenderer(slide);
                  } catch (Exception e) {
                      Log.e(TAG, "[dispatch] isolated webview activation failed slide=" + slide.slideId, e);
                      if (telemetry != null) telemetry.logEvent("webview_dispatch_error",
                              slide.slideId, "{\"error\":\"" + String.valueOf(e.getMessage()) + "\"}");
                      onIsolatedRendererFailed(slide.slideId, "dispatch_exception");
                  }
                  break;
          }

        final long memoryAfterMb = telemetry != null ? telemetry.currentMemMb() : -1;
        final String designRenderMode = !slide.renderMode.isEmpty() ? slide.renderMode
                : isWebviewType(eff) ? "webview"
                : (eff == SlideType.IMAGE || eff == SlideType.VIDEO) ? "native" : "n/a";
        if (telemetry != null) telemetry.logEvent("slide_shown", slide.slideId,
                "{\"type\":\"" + eff + "\",\"index\":" + currentIndex
                + ",\"rendererKind\":\"" + currentRendererKind + "\""
                + ",\"memoryTier\":\"" + currentMemoryTier() + "\""
                + ",\"webViewPolicy\":\"" + lastWebViewPolicy + "\""
                + ",\"shellSource\":\"" + shellSource + "\""
                + ",\"designRenderMode\":\"" + designRenderMode + "\""
                + ",\"fallbackUsed\":" + lastFallbackUsed
                + ",\"memoryBeforeMb\":" + memoryBeforeMb
                + ",\"memoryAfterMb\":" + memoryAfterMb + "}");

        schedulePreload(eff);

        // Legacy WebView slides: advance timer starts immediately (no first-frame callback).
        // Native slides and isolated-renderer slides: advance timer is started by
        // onRendererReady() or the RENDERER_READY_TIMEOUT_MS safety runnable armed above.
        if (!needsReadyGate) {
            toState(State.PLAYING, slide.slideId);
            startAdvanceTimer(generation, dur);
        }
    }

    private void schedulePreload(SlideType currentEff) {
        if (slides.size() < 2) return;
        final int nextIdx = (currentIndex + 1) % slides.size();
        final SlidePlan next = slides.get(nextIdx);
        toState(State.PREPARING_NEXT, next.slideId);
        final SlideType nextEff = (next.url == null || next.url.isEmpty())
                && (next.type == SlideType.VIDEO || next.type == SlideType.IMAGE)
                ? SlideType.WEBVIEW_URL : next.type;
        if (nextEff == SlideType.VIDEO && next.url != null && !next.url.isEmpty()) {
            delegate.schedulerPreloadVideo(next);
        } else if (nextEff == SlideType.IMAGE && next.url != null && !next.url.isEmpty()) {
            delegate.schedulerPreloadImage(next);
        }
    }

    /** Start the slide advance timer for the given generation and duration. */
    private void startAdvanceTimer(int myGen, long dur) {
        final int capturedGen = myGen;
        advanceRunnable = () -> {
            if (generation != capturedGen) {
                Log.d(TAG, "[advance] dropped stale callback (gen mismatch)");
                return;
            }
            Log.d(TAG, "[Scheduler] advance reason=duration gen=" + capturedGen);
            advance();
        };
        handler.postDelayed(advanceRunnable, dur);
    }

    private void advance() {
        if (!running) return;
        final SlidePlan cur = currentIndex < slides.size() ? slides.get(currentIndex) : null;
        final boolean curIsNative = cur != null
                && (cur.type == SlideType.VIDEO || cur.type == SlideType.IMAGE)
                && cur.url != null && !cur.url.isEmpty();
        final int nextIdx = (currentIndex + 1) % slides.size();
        final SlidePlan next = nextIdx < slides.size() ? slides.get(nextIdx) : null;
        final boolean nextIsNative = next != null
                && (next.type == SlideType.VIDEO || next.type == SlideType.IMAGE)
                && next.url != null && !next.url.isEmpty();

        if (curIsNative && !nextIsNative) {
            delegate.schedulerStopVideo();
            delegate.schedulerHideImage();
        }

        // Cross-native-type cleanup: stop ExoPlayer before showing image;
        // hide image view before starting video.  Without this, the outgoing
        // renderer stays on screen and covers the incoming one.
        if (curIsNative && nextIsNative && cur != null && next != null) {
            if (cur.type == SlideType.VIDEO && next.type == SlideType.IMAGE) {
                delegate.schedulerStopVideo();
            } else if (cur.type == SlideType.IMAGE && next.type == SlideType.VIDEO) {
                delegate.schedulerHideImage();
            }
        }

        consecutiveFailures = 0;
        toState(State.TRANSITIONING, next != null ? next.slideId : "");
        currentIndex = nextIdx;
        showCurrent();
    }

    private void degraded(String slideId) {
        toState(State.DEGRADED_PLAYBACK, slideId);
        if (telemetry != null) telemetry.logEvent("fallback_used", slideId, "{}");
        Log.w(TAG, "[degraded] " + consecutiveFailures + " failures -- extending " + FALLBACK_EXTEND_MS + "ms");
        if (advanceRunnable != null) handler.removeCallbacks(advanceRunnable);
        final int myGen = generation;
        advanceRunnable = () -> {
            if (generation != myGen) return;
            consecutiveFailures = 0;
            retrySlideId = ""; retryCountForSlide = 0;
            toState(State.RECOVERING_RENDERER, slideId);
            advance();
        };
        handler.postDelayed(advanceRunnable, FALLBACK_EXTEND_MS);
    }

    private void toState(State s, String slideId) {
        if (state != s) {
            Log.d(TAG, "[state] " + state + " -> " + s + " slide=" + slideId);
            state = s;
            delegate.schedulerOnStateChanged(s, slideId);
        }
    }

    public State getState() { return state; }
    public int getCurrentIndex() { return currentIndex; }
    public int getSlideCount() { return slides.size(); }
    public long getActiveRevisionId() { return activeRevisionId; }

    private boolean isSameStructure(List<SlidePlan> n) {
        if (n.size() != slides.size()) return false;
        for (int i = 0; i < slides.size(); i++) {
            SlidePlan a = slides.get(i), b = n.get(i);
            if (a.contentId != b.contentId) return false;
            if (a.type != b.type) return false;
            // Fix 4: also check playback settings — changes require full restart.
            // URL intentionally excluded — signed URL refreshes handled seamlessly below.
            if (a.durationMs != b.durationMs) return false;
            if (!a.objectFit.equals(b.objectFit)) return false;
            if (a.loop != b.loop) return false;
            if (Math.abs(a.volume - b.volume) > 0.001f) return false;
            if (!a.scaleType.equals(b.scaleType)) return false;
        }
        return true;
    }

    private List<SlidePlan> parseSlides(String json) {
        List<SlidePlan> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                SlidePlan s = new SlidePlan();
                s.slideId    = o.optString("slideId", String.valueOf(o.optInt("contentId", i)));
                String t     = o.optString("type", "WEBVIEW_DELEGATED");
                SlideType parsedType;
                  try {
                      parsedType = SlideType.valueOf(t);
                  } catch (IllegalArgumentException ex) {
                      // Unknown/legacy type string (e.g. old "WEBVIEW_DELEGATED" default,
                      // or a future client-side category this APK build predates) --
                      // fall back to the generic isolated-WebView renderer path rather
                      // than crashing parseSlides for the whole playlist.
                      parsedType = SlideType.WEBVIEW_URL;
                  }
                  s.type       = parsedType;
                s.url        = o.optString("url", "");
                s.durationMs = (long)(o.optDouble("duration", 10) * 1000);
                s.contentId  = o.optInt("contentId", 0);
                s.objectFit  = o.optString("objectFit", "contain");
                s.loop       = o.optBoolean("loop", true);
                s.volume     = (float) o.optDouble("volume", 0.0);
                s.scaleType  = o.optString("scaleType", "contain");
                s.fallbackUrl= o.optString("fallbackUrl", "");
                s.renderMode = o.optString("renderMode", "");
                double pdfPageDur = o.optDouble("pdfPageDuration", -1);
                s.pdfPageDurationMs = pdfPageDur >= 0 ? (long) (pdfPageDur * 1000) : -1;
                result.addAll(expandPdfIfPrerendered(s));
            }
        } catch (Exception e) { Log.e(TAG, "parseSlides: " + e.getMessage()); }
        return result;
    }

    /**
       * Task #1891: WEBVIEW_PDF slides whose PDF has been downloaded + prerendered to
       * page JPEGs (PdfPrerenderer, triggered from PlaylistRepository right after download)
       * are expanded here into one native IMAGE SlidePlan per page -- eliminating the
       * isolated WebView PDF viewer entirely for the common case. Falls back to the
       * original single WEBVIEW_PDF slide (isolated-WebView PDF path) when the PDF hasn't
       * been downloaded yet, prerendering failed, or the on-device JPEGs were pruned.
       * Called from both parseSlides() (fresh playlist from server) and entitiesToPlans()
       * (boot-time Room restore) so the expansion is applied consistently either way.
       */
      private List<SlidePlan> expandPdfIfPrerendered(SlidePlan pdfSlide) {
          if (pdfSlide.type != SlideType.WEBVIEW_PDF) return Collections.singletonList(pdfSlide);
          try {
              String assetId = "native_asset_" + pdfSlide.contentId + "_pdf";
              PlaylistDatabase.AssetEntity ae = repository.getAsset(assetId);
              if (ae == null || ae.prerenderedPages == null || ae.prerenderedPages.isEmpty()) {
                  return Collections.singletonList(pdfSlide);
              }
              JSONArray pages = new JSONArray(ae.prerenderedPages);
              List<SlidePlan> expanded = new ArrayList<>();
              long pageDurationMs = pdfSlide.pdfPageDurationMs >= 0 ? pdfSlide.pdfPageDurationMs : pdfSlide.durationMs;
              for (int i = 0; i < pages.length(); i++) {
                  String path = pages.optString(i, "");
                  if (path.isEmpty() || !new File(path).exists()) continue;
                  SlidePlan page = new SlidePlan();
                  page.slideId    = pdfSlide.slideId + "_p" + i;
                  page.type       = SlideType.IMAGE;
                  page.url        = "file://" + path;
                  page.durationMs = pageDurationMs;
                  page.contentId  = pdfSlide.contentId;
                  page.scaleType  = "contain";
                  page.objectFit  = "contain";
                  page.renderMode = "pdf-native";
                  expanded.add(page);
              }
              if (expanded.isEmpty()) {
                  Log.w(TAG, "[pdf-native] all prerendered pages missing on disk for " + assetId + " -- falling back to webview");
                  return Collections.singletonList(pdfSlide);
              }
              Log.i(TAG, "[pdf-native] expanded " + assetId + " into " + expanded.size() + " native IMAGE slides");
              return expanded;
          } catch (Exception e) {
              Log.e(TAG, "[pdf-native] expansion failed for slide=" + pdfSlide.slideId + ": " + e.getMessage());
              return Collections.singletonList(pdfSlide);
          }
      }

      private List<SlidePlan> entitiesToPlans(List<PlaylistDatabase.SlideEntity> ents) {
        List<SlidePlan> plans = new ArrayList<>();
        for (PlaylistDatabase.SlideEntity e : ents) {
            try {
                JSONObject cfg = new JSONObject(e.configJson);
                SlidePlan s = new SlidePlan();
                s.slideId    = e.slideId;
                try { s.type = SlideType.valueOf(e.type); } catch (Exception ex) { s.type = SlideType.WEBVIEW_URL; }
                  // Recover a specific WEBVIEW_* category from configJson for entities that
                  // were persisted as the generic WEBVIEW_URL fallback before this fix (task
                  // P0: normalizeSlideType) -- protects devices that already saved bad types.
                  if (s.type == SlideType.WEBVIEW_URL) {
                      String cfgType = cfg.optString("type", "");
                      if (!cfgType.isEmpty() && !"WEBVIEW_URL".equals(cfgType)) {
                          try { s.type = SlideType.valueOf(cfgType); } catch (Exception ignored) { /* keep WEBVIEW_URL */ }
                      }
                  }
                s.url        = cfg.optString("url", "");
                s.durationMs = e.durationMs;
                s.contentId  = cfg.optInt("contentId", 0);
                s.objectFit  = cfg.optString("objectFit", "contain");
                s.loop       = cfg.optBoolean("loop", true);
                s.volume     = (float) cfg.optDouble("volume", 0.0);
                s.scaleType  = cfg.optString("scaleType", "contain");
                s.renderMode = cfg.optString("renderMode", "");
                double pdfPageDur = cfg.optDouble("pdfPageDuration", -1);
                s.pdfPageDurationMs = pdfPageDur >= 0 ? (long) (pdfPageDur * 1000) : -1;
                plans.addAll(expandPdfIfPrerendered(s));
            } catch (Exception ex) { Log.w(TAG, "entitiesToPlans: " + ex.getMessage()); }
        }
        return plans;
    }

    /**
     * Computes which slide should be showing right now based on the persisted playlist epoch.
     * Returns int[]{index, remainingMs} where remainingMs is how long the active slide
     * still has to play. If no epoch is stored, returns {0, -1} (start from beginning).
     */
    private int[] computeWallClockIndex(android.content.SharedPreferences p) {
        if (p == null) return new int[]{0, -1};
        long epochMs = p.getLong(KEY_PLAYLIST_EPOCH, 0L);
        if (epochMs <= 0 || slides.isEmpty()) return new int[]{0, -1};
        long totalDuration = 0;
        for (SlidePlan s : slides) totalDuration += Math.max(1_000L, s.durationMs);
        if (totalDuration <= 0) return new int[]{0, -1};
        long elapsed = (System.currentTimeMillis() - epochMs) % totalDuration;
        if (elapsed < 0) elapsed = 0;
        long acc = 0;
        for (int i = 0; i < slides.size(); i++) {
            long dur = Math.max(1_000L, slides.get(i).durationMs);
            if (elapsed < acc + dur) {
                long remaining = Math.max(1_000L, (acc + dur) - elapsed);
                return new int[]{i, (int) remaining};
            }
            acc += dur;
        }
        return new int[]{0, (int) Math.max(1_000L, slides.get(0).durationMs)};
    }


    /** Release executor resources. Call from MainActivity.onDestroy(). */
    public void shutdown() {
        stop();
        try { dbExec.shutdownNow(); } catch (Throwable ignored) {}
    }

}

