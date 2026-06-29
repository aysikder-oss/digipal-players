package com.nexuscast.player;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
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
    private static final int MAX_FAILURES = 3;
    private static final int MAX_SLIDE_RETRIES = 3;
    private static final long FALLBACK_EXTEND_MS = 5_000L;

    public enum State {
        IDLE, BOOTING, RESTORING_LAST_GOOD,
        PREPARING_CURRENT, PLAYING,
        PREPARING_NEXT, TRANSITIONING,
        DEGRADED_PLAYBACK, RECOVERING_RENDERER
    }

    public enum SlideType { VIDEO, IMAGE, WEBVIEW_DESIGN, WEBVIEW_KIOSK, WEBVIEW_URL }

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
        void schedulerActivateWebView(SlidePlan slide);
        void schedulerDeactivateWebView();
        void schedulerStopVideo();
        void schedulerHideImage();
        void schedulerOnStateChanged(State state, String slideId);
    }

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

      /** Pass MediaDownloadManager to repository for the atomic revision pipeline. */
      public void setMediaDownloadManager(MediaDownloadManager mdm) { repository.setMediaDownloadManager(mdm); }

      /** Pass WebView to repository so the pipeline can fire failure metrics events. */
      public void setWebView(android.webkit.WebView wv) { repository.setWebView(wv); }

  
    /** Per-slide retry counter; reset in showCurrent(), incremented in onRendererError(). */
    private int slideRetryCount = 0;

    /** Wall-clock time when pause() was called; -1 when not paused. */
    private long pausedAt = -1;

    /** Remaining slide time captured at pause(); -1 when not paused. */
    private long remainingMs = -1;

    // ── Wall-clock playlist timing ──────────────────────────────────────────
    /** SharedPreferences for persisting playlist epoch across process death. */
    private android.content.SharedPreferences prefs;
    private static final String KEY_PLAYLIST_EPOCH = "playlist_epoch_ms";
    /**
     * When > 0: use this as the advance-timer duration for the first slide only (wall-clock resume).
     * Reset to -1 immediately after first use in showCurrent().
     */
    private long firstSlideRemainingMs = -1L;

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
        List<SlidePlan> newSlides = parseSlides(json);
        if (newSlides.isEmpty()) {
            stop();
            dbExec.execute(() -> repository.clearActiveRevision());
            Log.i(TAG, "[setPlaylist] empty -- stopped and cleared active revision");
            return;
        }

        if (isSameStructure(newSlides)) {
            slides = newSlides;
            Log.d(TAG, "[setPlaylist] URL refresh -- keeping index=" + currentIndex);
            return;
        }

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
    }

    /**
     * Pause slide timing. Records remaining duration so resume() can restart
     * the advance timer for exactly the time that was left.
     * Called from MainActivity.onPause().
     */
    public synchronized void pause() {
        if (!running || pausedAt >= 0) return;
        pausedAt = System.currentTimeMillis();
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
        long readyMs = System.currentTimeMillis() - slideStartMs;
        Log.d(TAG, "[ready] " + slideId + " in " + readyMs + "ms");
        if (telemetry != null) telemetry.logEvent("slide_ready", slideId,
                "{\"readyMs\":" + readyMs + "}");
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
     * Called when a renderer encounters an unrecoverable error.
     * Retries the same slide up to MAX_SLIDE_RETRIES times before advancing.
     * Goes DEGRADED only after MAX_FAILURES consecutive failures.
     */
    public void onRendererError(String slideId, String error) {
        Log.w(TAG, "[error] " + slideId + ": " + error + " (slideRetry=" + slideRetryCount + ")");
        slideRetryCount++;
        consecutiveFailures++;
        if (telemetry != null) telemetry.logEvent("slide_failed", slideId,
                "{\"error\":" + JSONObject.quote(error)
                + ",\"slideRetry\":" + slideRetryCount + "}");

        if (consecutiveFailures >= MAX_FAILURES) {
            degraded(slideId);
            return;
        }

        if (advanceRunnable != null) handler.removeCallbacks(advanceRunnable);

        if (slideRetryCount < MAX_SLIDE_RETRIES) {
            Log.d(TAG, "[error] retrying slide " + slideId + " in 500ms (attempt " + slideRetryCount + ")");
            final int myGen = generation;
            handler.postDelayed(() -> {
                if (generation != myGen || !running) return;
                showCurrent();
            }, 500);
        } else {
            Log.w(TAG, "[error] max retries for " + slideId + " -- skipping");
            slideRetryCount = 0;
            final int myGen = generation;
            handler.postDelayed(() -> {
                if (generation != myGen || !running) return;
                advance();
            }, 300);
        }
    }

    private void showCurrent() {
        if (!running || slides.isEmpty()) return;
        if (currentIndex >= slides.size()) currentIndex = 0;

        final SlidePlan slide = slides.get(currentIndex);

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

        slideRetryCount = 0;
        slideStartMs = System.currentTimeMillis();
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

        switch (eff) {
            case VIDEO:
                delegate.schedulerDeactivateWebView();
                delegate.schedulerPlayVideo(dispatch);
                break;
            case IMAGE:
                delegate.schedulerDeactivateWebView();
                delegate.schedulerShowImage(dispatch);
                break;
            default:
                delegate.schedulerActivateWebView(slide);
                break;
        }

        if (telemetry != null) telemetry.logEvent("slide_shown", slide.slideId,
                "{\"type\":\"" + eff + "\",\"index\":" + currentIndex + "}");

        schedulePreload(eff);

        // Compute slide duration: wall-clock resume for boot-restore, full otherwise.
        final long dur;
        if (firstSlideRemainingMs > 0) {
            dur = firstSlideRemainingMs;
            firstSlideRemainingMs = -1L;
            Log.d(TAG, "[showCurrent] wall-clock resume: advance in " + dur
                    + "ms (slide " + currentIndex + ")");
        } else {
            dur = Math.max(1_000L, slide.durationMs);
        }

        // Web slides: advance timer starts immediately (WebView has no first-frame callback).
        // Native slides: enter PREPARING_CURRENT and wait for onRendererReady() so the full
        // configured duration is preserved even on slow-decode devices.
        // A safety timeout starts the clock after RENDERER_READY_TIMEOUT_MS if no signal.
        if (eff == SlideType.WEBVIEW_DESIGN || eff == SlideType.WEBVIEW_KIOSK
                || eff == SlideType.WEBVIEW_URL) {
            toState(State.PLAYING, slide.slideId);
            startAdvanceTimer(generation, dur);
        } else {
            toState(State.PREPARING_CURRENT, slide.slideId);
            pendingAdvanceDurationMs = dur;
            rendererReadyTimeoutGen = generation;
            final int myGen = generation;
            final String mySlideId = slide.slideId;
            if (rendererReadyTimeout != null) handler.removeCallbacks(rendererReadyTimeout);
            rendererReadyTimeout = () -> {
                if (generation != myGen) return;
                Log.w(TAG, "[renderer_ready_timeout] no first-frame for slide " + mySlideId
                        + " after " + RENDERER_READY_TIMEOUT_MS + "ms — starting timer anyway");
                if (telemetry != null) telemetry.logEvent("renderer_timeout", mySlideId,
                        "{\"pendingMs\":" + pendingAdvanceDurationMs + "}");
                rendererReadyTimeout = null; rendererReadyTimeoutGen = -1;
                toState(State.PLAYING, mySlideId);
                startAdvanceTimer(myGen, pendingAdvanceDurationMs);
            };
            handler.postDelayed(rendererReadyTimeout, RENDERER_READY_TIMEOUT_MS);
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
            slideRetryCount = 0;
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
        if (!running || n.size() != slides.size()) return false;
        for (int i = 0; i < slides.size(); i++) {
            if (slides.get(i).contentId != n.get(i).contentId) return false;
            if (slides.get(i).type != n.get(i).type) return false;
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
                s.type       = "VIDEO".equals(t) ? SlideType.VIDEO
                             : "IMAGE".equals(t) ? SlideType.IMAGE
                             : "WEBVIEW_DESIGN".equals(t) ? SlideType.WEBVIEW_DESIGN
                             : "WEBVIEW_KIOSK".equals(t) ? SlideType.WEBVIEW_KIOSK
                             : SlideType.WEBVIEW_URL;
                s.url        = o.optString("url", "");
                s.durationMs = (long)(o.optDouble("duration", 10) * 1000);
                s.contentId  = o.optInt("contentId", 0);
                s.objectFit  = o.optString("objectFit", "contain");
                s.loop       = o.optBoolean("loop", true);
                s.volume     = (float) o.optDouble("volume", 0.0);
                s.scaleType  = o.optString("scaleType", "contain");
                s.fallbackUrl= o.optString("fallbackUrl", "");
                result.add(s);
            }
        } catch (Exception e) { Log.e(TAG, "parseSlides: " + e.getMessage()); }
        return result;
    }

    private List<SlidePlan> entitiesToPlans(List<PlaylistDatabase.SlideEntity> ents) {
        List<SlidePlan> plans = new ArrayList<>();
        for (PlaylistDatabase.SlideEntity e : ents) {
            try {
                JSONObject cfg = new JSONObject(e.configJson);
                SlidePlan s = new SlidePlan();
                s.slideId    = e.slideId;
                try { s.type = SlideType.valueOf(e.type); } catch (Exception ex) { s.type = SlideType.WEBVIEW_URL; }
                s.url        = cfg.optString("url", "");
                s.durationMs = e.durationMs;
                s.contentId  = cfg.optInt("contentId", 0);
                s.objectFit  = cfg.optString("objectFit", "contain");
                s.loop       = cfg.optBoolean("loop", true);
                s.volume     = (float) cfg.optDouble("volume", 0.0);
                s.scaleType  = cfg.optString("scaleType", "contain");
                plans.add(s);
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


}
