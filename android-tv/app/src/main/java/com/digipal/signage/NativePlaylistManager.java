package com.digipal.signage;

    import android.os.Handler;
    import android.os.Looper;
    import org.json.JSONArray;
    import org.json.JSONObject;
    import java.util.ArrayList;
    import java.util.List;

    /**
     * NativePlaylistManager drives video and image slides entirely in native Android
     * (ExoPlayer / Glide) without WebView involvement. Activated when the dashboard
     * remotePlayerSettings flag {@code nativeContentLoop} is true. Slide data is
     * supplied by the WebView via the {@code setNativePlaylist} JavaScript bridge
     * method so the manager never makes HTTP calls of its own. WebView-delegated
     * slides (widgets, Design Studio canvases, web URLs) wake the WebView for their
     * duration and then return control to the native loop.
     */
    public class NativePlaylistManager {

        public interface Delegate {
            void evaluateJs(String js);
        }

        private enum SlideType { VIDEO, IMAGE, WEBVIEW_DELEGATED }

        private static class NativeSlide {
            SlideType type = SlideType.WEBVIEW_DELEGATED;
            String url = "";
            int durationSec = 10;
            int contentId = 0;
            String objectFit = "contain";
            boolean loop = true;
            float volume = 0f;
            String scaleType = "contain";
        }

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Delegate delegate;
        private volatile List<NativeSlide> slides = new ArrayList<>();
        private int currentIndex = 0;
        private boolean running = false;
        private Runnable advanceRunnable;

        public NativePlaylistManager(Delegate delegate) {
            this.delegate = delegate;
        }

        /**
         * Replace the slide list. If the contentIds and types haven't changed
         * (same playlist, just refreshed URLs/settings), updates in-place without
         * restarting from slide 0 — prevents the "jumps back to beginning" issue
         * caused by WebView reconnects re-calling setNativePlaylist.
         */
        public synchronized void setPlaylist(String json) {
            List<NativeSlide> newSlides = parseSlides(json);
            if (isSameStructure(newSlides)) {
                slides = newSlides;
                return;
            }
            stop();
            slides = newSlides;
            currentIndex = 0;
            if (!slides.isEmpty()) {
                running = true;
                showCurrentSlide();
            }
        }

        /** True when new slide list has same contentIds, types, durations, and playback settings
         *  in the same order. URL changes are safe to apply in-place (Fix 10). */
        private boolean isSameStructure(List<NativeSlide> newSlides) {
            if (newSlides.size() != slides.size()) return false;
            for (int i = 0; i < slides.size(); i++) {
                NativeSlide old = slides.get(i);
                NativeSlide nw  = newSlides.get(i);
                // Fix 10: compare all playback-relevant fields, not just contentId + type.
                // URL changes are safe to apply in-place; any other change restarts the loop.
                if (old.contentId   != nw.contentId)   return false;
                if (old.type        != nw.type)         return false;
                if (old.durationSec != nw.durationSec)  return false;
                if (old.loop        != nw.loop)         return false;
                if (Math.abs(old.volume - nw.volume) > 0.001f) return false;
                if (!old.objectFit.equals(nw.objectFit))   return false;
                if (!old.scaleType.equals(nw.scaleType))   return false;
            }
            return true;
        }

        public synchronized void stop() {
            running = false;
            if (advanceRunnable != null) {
                handler.removeCallbacks(advanceRunnable);
                advanceRunnable = null;
            }
        }

        private synchronized void showCurrentSlide() {
            if (!running || slides.isEmpty()) return;
            if (currentIndex >= slides.size()) currentIndex = 0;
            final NativeSlide slide = slides.get(currentIndex);
            final int capturedIndex = currentIndex;
            final SlideType capturedType = slide.type;
            android.util.Log.d("NativePlaylist",
                    "[loop] slide " + capturedIndex + "/" + slides.size()
                    + " type=" + slide.type + " dur=" + slide.durationSec + "s");

            // Treat empty-URL VIDEO/IMAGE as WEBVIEW_DELEGATED so the screen
            // never goes black when a URL fails to resolve.
            final SlideType effectiveType =
                    (slide.url == null || slide.url.isEmpty())
                    && (slide.type == SlideType.VIDEO || slide.type == SlideType.IMAGE)
                    ? SlideType.WEBVIEW_DELEGATED : slide.type;

            switch (effectiveType) {
                case VIDEO:
                    delegate.evaluateJs(
                            "try{window.Android.setWebViewDormant(true);}catch(e){}" +
                            "try{window.Android.playNativeVideo(" +
                            JSONObject.quote(slide.url) +
                            ",0,0,window.innerWidth,window.innerHeight," +
                            JSONObject.quote(slide.objectFit) + "," +
                            slide.loop + "," + slide.volume + ");}catch(e){}");
                    break;
                case IMAGE:
                    // Fix 4: do NOT hide WebView before Glide confirms first draw —
                    // NativeTvImageOverlay.onReady calls setWebViewDormant(true) after
                    // the Glide callback fires, preventing black screens on slow decodes.
                    // Fix 1: pass contentId as 7th arg so APK fires content-scoped callbacks
                    // (__digipalNativeImageReady_<id> / __digipalNativeImageError_<id>).
                    delegate.evaluateJs(
                            "try{window.Android.showNativeImage(" +
                            JSONObject.quote(slide.url) +
                            ",0,0,window.innerWidth,window.innerHeight," +
                            JSONObject.quote(slide.scaleType) + "," +
                            JSONObject.quote(String.valueOf(slide.contentId)) + ");}catch(e){}");
                    break;
                default:
                    delegate.evaluateJs(
                            "try{window.Android.setWebViewDormant(false);}catch(e){}");
                    break;
            }

            final long durationMs = Math.max(1_000L, slide.durationSec * 1000L);
            final int nextIndex = (capturedIndex + 1) % slides.size();
            advanceRunnable = () -> {
                if (!running) return;
                // Only explicitly stop the outgoing native overlay when the NEXT
                // slide is WEBVIEW_DELEGATED. For native→native transitions the
                // incoming playNativeVideo/showNativeImage replaces the overlay
                // directly, avoiding a flash frame between stop and start.
                final boolean nextIsNative = nextIndex < slides.size()
                        && (slides.get(nextIndex).type == SlideType.VIDEO
                            || slides.get(nextIndex).type == SlideType.IMAGE)
                        && slides.get(nextIndex).url != null
                        && !slides.get(nextIndex).url.isEmpty();
                if (!nextIsNative) {
                    if (capturedType == SlideType.VIDEO) {
                        delegate.evaluateJs("try{window.Android.stopNativeVideo();}catch(e){}");
                    } else if (capturedType == SlideType.IMAGE) {
                        delegate.evaluateJs("try{window.Android.hideNativeImage();}catch(e){}");
                    }
                }
                currentIndex = nextIndex;
                showCurrentSlide();
            };
            handler.postDelayed(advanceRunnable, durationMs);
        }

        private List<NativeSlide> parseSlides(String json) {
            final List<NativeSlide> result = new ArrayList<>();
            try {
                final JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    final JSONObject obj = arr.getJSONObject(i);
                    final NativeSlide s = new NativeSlide();
                    final String type = obj.optString("type", "WEBVIEW_DELEGATED");
                    s.type = "VIDEO".equals(type) ? SlideType.VIDEO
                           : "IMAGE".equals(type) ? SlideType.IMAGE
                           : SlideType.WEBVIEW_DELEGATED;
                    s.url         = obj.optString("url", "");
                    s.durationSec = obj.optInt("duration", 10);
                    s.contentId   = obj.optInt("contentId", 0);
                    s.objectFit   = obj.optString("objectFit", "contain");
                    s.loop        = obj.optBoolean("loop", true);
                    s.volume      = (float) obj.optDouble("volume", 0.0);
                    s.scaleType   = obj.optString("scaleType", "contain");
                    result.add(s);
                }
                android.util.Log.i("NativePlaylist", "[loop] Parsed " + result.size() + " slides");
            } catch (Exception e) {
                android.util.Log.e("NativePlaylist", "[loop] Parse failed: " + e.getMessage());
            }
            return result;
        }
    }
  