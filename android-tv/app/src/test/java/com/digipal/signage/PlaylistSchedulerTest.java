package com.digipal.signage;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Robolectric unit tests for PlaylistScheduler state machine.
 *
 * These tests use the package-private PlaylistScheduler(Delegate) constructor and
 * startPlayingForTest(List<SlidePlan>) to bypass the Room pipeline entirely.
 * No disk I/O, no Room DB, no network — pure state-machine coverage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PlaylistSchedulerTest {

    // ── Fake delegate that records every call ─────────────────────────────────

    static class RecordingDelegate implements PlaylistScheduler.Delegate {
        final List<String> calls = new ArrayList<>();

        private void rec(String s) { calls.add(s); }

        @Override public void schedulerPlayVideo(PlaylistScheduler.SlidePlan s)     { rec("playVideo:"  + s.slideId); }
        @Override public void schedulerShowImage(PlaylistScheduler.SlidePlan s)     { rec("showImage:"  + s.slideId); }
        @Override public void schedulerPreloadVideo(PlaylistScheduler.SlidePlan s)  { rec("preloadVideo:" + s.slideId); }
        @Override public void schedulerPreloadImage(PlaylistScheduler.SlidePlan s)  { rec("preloadImage:" + s.slideId); }
        @Override public void schedulerDeactivateWebView()                          { rec("deactivateWV"); }
        @Override public void schedulerDeactivateWebViewForIsolatedRenderer()       { rec("deactivateWV_iso"); }
        @Override public void schedulerActivateIsolatedRenderer(PlaylistScheduler.SlidePlan s) { rec("activateIso:" + s.slideId); }
        @Override public void schedulerDeactivateIsolatedRenderer()                 { rec("deactivateIso"); }
        @Override public void schedulerStopVideo()                                  { rec("stopVideo"); }
        @Override public void schedulerHideImage()                                  { rec("hideImage"); }
        @Override public void schedulerOnStateChanged(PlaylistScheduler.State st, String id) { rec("state:" + st + ":" + id); }

        boolean hasCalled(String prefix) {
            for (String c : calls) if (c.startsWith(prefix)) return true;
            return false;
        }

        int indexOf(String prefix) {
            for (int i = 0; i < calls.size(); i++) if (calls.get(i).startsWith(prefix)) return i;
            return -1;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PlaylistScheduler.SlidePlan slide(String id, PlaylistScheduler.SlideType type,
                                                     String url, long durMs) {
        PlaylistScheduler.SlidePlan s = new PlaylistScheduler.SlidePlan();
        s.slideId    = id;
        s.type       = type;
        s.url        = url;
        s.durationMs = durMs;
        s.contentId  = id.hashCode();
        s.objectFit  = "contain";
        s.scaleType  = "contain";
        s.fallbackUrl = "";
        s.renderMode  = "";
        s.mediaFingerprint = url;
        return s;
    }

    private static void drainMain(long advanceMs) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(advanceMs));
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * T1: setPlaylist("[]") on a fresh scheduler must fire stopVideo + hideImage
     * synchronously and transition state to IDLE.
     */
    @Test
    public void emptyPlaylist_stopsImmediately() {
        RecordingDelegate d = new RecordingDelegate();
        PlaylistScheduler sched = new PlaylistScheduler(d);

        sched.setPlaylist("[]");

        assertTrue("stopVideo not called", d.hasCalled("stopVideo"));
        assertTrue("hideImage not called", d.hasCalled("hideImage"));
        assertEquals(PlaylistScheduler.State.IDLE, sched.getState());
    }

    /**
     * T2: Three-second restart regression — setPlaylist("[]") while PLAYING must
     * call stopVideo before a new playVideo can ever fire. The bug was that
     * setNativePlaylist('[]') + setNativePlaylist(newContent) arrived in rapid
     * succession and an in-flight dbExec callback promoted a stale revision,
     * causing ExoPlayer to restart ~3 s later.
     *
     * At the scheduler level: stop() is synchronous; the first call logged must
     * be stopVideo (not playVideo of a new revision).
     */
    @Test
    public void setEmptyPlaylist_stopFiresBeforeAnyPlayVideo() {
        RecordingDelegate d = new RecordingDelegate();
        PlaylistScheduler sched = new PlaylistScheduler(d);

        // Simulate: scheduler was playing a video slide
        List<PlaylistScheduler.SlidePlan> initial = Collections.singletonList(
                slide("vid1", PlaylistScheduler.SlideType.VIDEO, "http://example.com/a.mp4", 10_000));
        sched.startPlayingForTest(initial);
        drainMain(0); // flush any main-thread posts from showCurrent

        int playCallsBefore = 0;
        for (String c : d.calls) if (c.startsWith("playVideo")) playCallsBefore++;
        d.calls.clear();

        // Now stop
        sched.setPlaylist("[]");

        // First call must be stopVideo — no spurious playVideo from a stale pipeline
        assertFalse("calls list should not be empty after setPlaylist([])", d.calls.isEmpty());
        assertTrue("first call after stop must be stopVideo, got: " + d.calls,
                d.calls.get(0).startsWith("stopVideo"));
        assertEquals(PlaylistScheduler.State.IDLE, sched.getState());
    }

    /**
     * T3: After onRendererReady fires for a VIDEO slide the scheduler must be PLAYING,
     * not stuck in PREPARING_CURRENT.
     */
    @Test
    public void onRendererReady_transitionsToPlaying() {
        RecordingDelegate d = new RecordingDelegate();
        PlaylistScheduler sched = new PlaylistScheduler(d);
        sched.startPlayingForTest(Collections.singletonList(
                slide("vid1", PlaylistScheduler.SlideType.VIDEO, "http://example.com/v.mp4", 8_000)));
        drainMain(0);

        assertEquals(PlaylistScheduler.State.PREPARING_CURRENT, sched.getState());
        sched.onRendererReady("vid1");

        assertEquals(PlaylistScheduler.State.PLAYING, sched.getState());
    }

    /**
     * T4: Two-slide playlist VIDEO→IMAGE: advance() must call schedulerStopVideo
     * before calling schedulerShowImage (Video→Image cross-type cleanup regression).
     */
    @Test
    public void advance_videoToImage_stopsVideoBeforeShowingImage() {
        RecordingDelegate d = new RecordingDelegate();
        PlaylistScheduler sched = new PlaylistScheduler(d);

        List<PlaylistScheduler.SlidePlan> slides = new ArrayList<>();
        slides.add(slide("vid1", PlaylistScheduler.SlideType.VIDEO, "http://x.com/v.mp4", 5_000));
        slides.add(slide("img1", PlaylistScheduler.SlideType.IMAGE, "http://x.com/i.jpg", 5_000));

        sched.startPlayingForTest(slides);
        drainMain(0);

        // Signal VIDEO first-frame → enters PLAYING, arms advance timer for 5 000 ms
        sched.onRendererReady("vid1");
        assertEquals(PlaylistScheduler.State.PLAYING, sched.getState());

        d.calls.clear(); // reset: interested only in what happens on advance

        // Advance past the slide duration to fire advance()
        drainMain(5_100);

        int stopIdx      = d.indexOf("stopVideo");
        int showImageIdx = d.indexOf("showImage");
        assertTrue("stopVideo must be called on VIDEO→IMAGE transition", stopIdx >= 0);
        assertTrue("showImage must be called after stopVideo", showImageIdx > stopIdx);
    }

    /**
     * T5: Two-slide playlist IMAGE→VIDEO: advance() must call schedulerHideImage
     * before calling schedulerPlayVideo (Image→Video cross-type cleanup regression).
     */
    @Test
    public void advance_imageToVideo_hidesImageBeforePlayingVideo() {
        RecordingDelegate d = new RecordingDelegate();
        PlaylistScheduler sched = new PlaylistScheduler(d);

        List<PlaylistScheduler.SlidePlan> slides = new ArrayList<>();
        slides.add(slide("img1", PlaylistScheduler.SlideType.IMAGE, "http://x.com/i.jpg", 5_000));
        slides.add(slide("vid1", PlaylistScheduler.SlideType.VIDEO, "http://x.com/v.mp4", 5_000));

        sched.startPlayingForTest(slides);
        drainMain(0);

        sched.onRendererReady("img1");
        assertEquals(PlaylistScheduler.State.PLAYING, sched.getState());

        d.calls.clear();

        drainMain(5_100);

        int hideIdx  = d.indexOf("hideImage");
        int playIdx  = d.indexOf("playVideo");
        assertTrue("hideImage must be called on IMAGE→VIDEO transition", hideIdx >= 0);
        assertTrue("playVideo must be called after hideImage", playIdx > hideIdx);
    }

    /**
     * T6: onRendererError() with retries — the scheduler must call showCurrent again
     * (playVideo again for a VIDEO slide) within 600 ms on the first error, and stay
     * on the same slide (getSlideCount() unchanged, currentIndex still 0).
     */
    @Test
    public void onRendererError_retriesSameSlide() throws InterruptedException {
        RecordingDelegate d = new RecordingDelegate();
        PlaylistScheduler sched = new PlaylistScheduler(d);
        sched.startPlayingForTest(Collections.singletonList(
                slide("vid1", PlaylistScheduler.SlideType.VIDEO, "http://x.com/v.mp4", 10_000)));
        drainMain(0);

        int playsBefore = 0;
        for (String c : d.calls) if (c.startsWith("playVideo")) playsBefore++;

        sched.onRendererError("vid1", "test_error");
        drainMain(600); // retry fires after 500 ms

        int playsAfter = 0;
        for (String c : d.calls) if (c.startsWith("playVideo")) playsAfter++;

        assertTrue("Expected a retry playVideo call", playsAfter > playsBefore);
        assertEquals("currentIndex must stay 0 during retry", 0, sched.getCurrentIndex());
    }

    /**
     * T7: Stale-callback guard — onRendererReady() for a superseded slideId must not
     * transition the scheduler to PLAYING.
     */
    @Test
    public void onRendererReady_staleSlideId_ignored() {
        RecordingDelegate d = new RecordingDelegate();
        PlaylistScheduler sched = new PlaylistScheduler(d);

        List<PlaylistScheduler.SlidePlan> slides = new ArrayList<>();
        slides.add(slide("s1", PlaylistScheduler.SlideType.VIDEO, "http://x.com/1.mp4", 5_000));
        slides.add(slide("s2", PlaylistScheduler.SlideType.VIDEO, "http://x.com/2.mp4", 5_000));

        sched.startPlayingForTest(slides);
        drainMain(0);

        // Advance past s1 without signalling ready → renderer-ready safety timeout fires,
        // which also moves us to PLAYING and starts the timer. After the timer, s2 starts.
        drainMain(9_200); // RENDERER_READY_TIMEOUT_NATIVE_MS=3000 + 5000 dur + buffer

        // Now scheduler is on s2 (or wrapped back to s1 if only 2 slides). Either way,
        // a late onRendererReady for "s1" must be ignored.
        PlaylistScheduler.State stateBefore = sched.getState();
        sched.onRendererReady("s1"); // stale ID
        assertEquals("Stale onRendererReady must not change state", stateBefore, sched.getState());
    }

    /**
     * T8: stop() then startPlayingForTest() — verifies the scheduler restarts cleanly
     * without double-advance or leftover timer from the previous generation.
     */
    @Test
    public void stopThenRestart_cleansUpOldGeneration() {
        RecordingDelegate d = new RecordingDelegate();
        PlaylistScheduler sched = new PlaylistScheduler(d);

        List<PlaylistScheduler.SlidePlan> slides = Collections.singletonList(
                slide("v1", PlaylistScheduler.SlideType.VIDEO, "http://x.com/v.mp4", 3_000));

        sched.startPlayingForTest(slides);
        sched.onRendererReady("v1");
        drainMain(0);

        sched.setPlaylist("[]"); // stop
        assertEquals(PlaylistScheduler.State.IDLE, sched.getState());

        d.calls.clear();

        // Restart with a new video
        sched.startPlayingForTest(Collections.singletonList(
                slide("v2", PlaylistScheduler.SlideType.VIDEO, "http://x.com/v2.mp4", 3_000)));
        drainMain(0);

        assertTrue("Expected playVideo:v2 after restart", d.hasCalled("playVideo:v2"));
        assertFalse("Stale v1 must not replay after restart", d.hasCalled("playVideo:v1"));
    }
}
