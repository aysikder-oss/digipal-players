package com.digipal.signage;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
 *   - Manages WebView dormant state to reclaim 50–100 MB on Fire TV 4K Plus
 *   - Implements soft recovery: extend current slide on renderer failure
 *   - Reports all state transitions to TelemetryManager
 *
 * State machine:
 *   IDLE → BOOTING → RESTORING_LAST_GOOD → PREPARING_CURRENT
 *   → PLAYING → PREPARING_NEXT → TRANSITIONING → PLAYING (loop)
 *   Any state → DEGRADED_PLAYBACK → RECOVERING_RENDERER → PLAYING
 */
public class PlaylistScheduler {

    private static final String TAG = "PlaylistScheduler";
    private static final int MAX_FAILURES = 3;
    private static final long FALLBACK_EXTEND_MS = 5_000L;

    // ── State machine ─────────────────────────────────────────────────────────
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

    /** Implemented by MainActivity — all callbacks on main thread. */
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

    // ── Fields ────────────────────────────────────────────────────────────────
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

    // ── Constructor ───────────────────────────────────────────────────────────
    public PlaylistScheduler(Delegate delegate, PlaylistRepository repository, TelemetryManager telemetry) {
        this.delegate = delegate;
        this.repository = repository;
        this.telemetry = telemetry;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called from MainActivity.onCreate() — restores last playlist from Room if available. */
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
                        currentIndex = 0;
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
     * Parses JSON, persists to Room (READY status), activates revision, starts playing.
     * If the slide structure is unchanged (same contentIds + types), only refreshes
     * URLs in-place without restarting from slide 0.
     */
    public synchronized void setPlaylist(String json) {
        List<SlidePlan> newSlides = parseSlides(json);
        if (newSlides.isEmpty()) return;

        if (isSameStructure(newSlides)) {
            slides = newSlides;
            Log.d(TAG, "[setPlaylist] URL refresh — keeping index=" + currentIndex);
            return;
        }

        stop();
        slides = newSlides;
        currentIndex = 0;
        consecutiveFailures = 0;

        // Persist revision to Room (background)
        final String finalJson = json;
        dbExec.execute(() -> {
            long revId = repository.saveRevision("default", String.valueOf(System.currentTimeMillis()), finalJson);
            repository.activateRevision(revId);
            repository.saveSlidesFromJson(revId, finalJson);
            activeRevisionId = revId;
        });

        running = true;
        showCurrent();
    }

    /** Stop playback and cancel pending timer. */
    public synchronized void stop() {
        running = false;
        if (advanceRunnable != null) {
            handler.removeCallbacks(advanceRunnable);
            advanceRunnable = null;
        }
        toState(State.IDLE, "");
    }

    /** Called when a video/image renderer signals it's ready (first frame). */
    public void onRendererReady(String slideId) {
        long readyMs = System.currentTimeMillis() - slideStartMs;
        Log.d(TAG, "[ready] " + slideId + " in " + readyMs + "ms");
        if (telemetry != null) telemetry.logEvent("slide_ready", slideId,
                "{\"readyMs\":" + readyMs + "}");
    }

    /** Called when a renderer encounters an unrecoverable error. */
    public void onRendererError(String slideId, String error) {
        Log.w(TAG, "[error] " + slideId + ": " + error);
        consecutiveFailures++;
        if (telemetry != null) telemetry.logEvent("slide_failed", slideId,
                "{\"error\":" + JSONObject.quote(error) + "}");
        if (consecutiveFailures >= MAX_FAILURES) {
            degraded(slideId);
        } else {
            // Soft recovery: advance to next slide quickly
            if (advanceRunnable != null) handler.removeCallbacks(advanceRunnable);
            handler.postDelayed(this::advance, 300);
        }
    }

    // ── Private state machine ─────────────────────────────────────────────────

    private void showCurrent() {
        if (!running || slides.isEmpty()) return;
        if (currentIndex >= slides.size()) currentIndex = 0;

        final SlidePlan slide = slides.get(currentIndex);
        slideStartMs = System.currentTimeMillis();
        toState(State.PREPARING_CURRENT, slide.slideId);

        // Effective type: empty URL video/image falls back to WebView
        SlideType eff = slide.type;
        if ((eff == SlideType.VIDEO || eff == SlideType.IMAGE)
                && (slide.url == null || slide.url.isEmpty())) {
            eff = SlideType.WEBVIEW_URL;
        }

        switch (eff) {
            case VIDEO:
                delegate.schedulerDeactivateWebView();
                delegate.schedulerPlayVideo(slide);
                break;
            case IMAGE:
                delegate.schedulerDeactivateWebView();
                delegate.schedulerShowImage(slide);
                break;
            default:
                delegate.schedulerActivateWebView(slide);
                break;
        }

        toState(State.PLAYING, slide.slideId);
        if (telemetry != null) telemetry.logEvent("slide_shown", slide.slideId,
                "{\"type\":\"" + eff + "\",\"index\":" + currentIndex + "}");

        // Preload next
        schedulePreload(eff);

        // Schedule advance
        final long dur = Math.max(1_000L, slide.durationMs);
        advanceRunnable = this::advance;
        handler.postDelayed(advanceRunnable, dur);
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

        consecutiveFailures = 0;
        toState(State.TRANSITIONING, next != null ? next.slideId : "");
        currentIndex = nextIdx;
        showCurrent();
    }

    private void degraded(String slideId) {
        toState(State.DEGRADED_PLAYBACK, slideId);
        if (telemetry != null) telemetry.logEvent("fallback_used", slideId, "{}");
        Log.w(TAG, "[degraded] " + consecutiveFailures + " failures — extending current slide " + FALLBACK_EXTEND_MS + "ms");
        if (advanceRunnable != null) handler.removeCallbacks(advanceRunnable);
        advanceRunnable = () -> {
            consecutiveFailures = 0;
            toState(State.RECOVERING_RENDERER, slideId);
            advance();
        };
        handler.postDelayed(advanceRunnable, FALLBACK_EXTEND_MS);
    }

    private void toState(State s, String slideId) {
        if (state != s) {
            Log.d(TAG, "[state] " + state + " → " + s + " slide=" + slideId);
            state = s;
            delegate.schedulerOnStateChanged(s, slideId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
}
