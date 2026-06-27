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
   *
   * <p>Android TV APK v1.0.9+.</p>
   */
  public class NativePlaylistManager {

      /** Provided by MainActivity; allows JS evaluation from any thread. */
      public interface Delegate {
          /** Queue JavaScript for execution in the WebView (thread-safe). */
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
      private List<NativeSlide> slides = new ArrayList<>();
      private int currentIndex = 0;
      private boolean running = false;
      private Runnable advanceRunnable;

      public NativePlaylistManager(Delegate delegate) {
          this.delegate = delegate;
      }

      /** Replace the slide list and restart the loop from slide 0. */
      public synchronized void setPlaylist(String json) {
          stop();
          slides = parseSlides(json);
          currentIndex = 0;
          if (!slides.isEmpty()) {
              running = true;
              showCurrentSlide();
          }
      }

      /** Stop the loop and cancel any pending advance timer. */
      public synchronized void stop() {
          running = false;
          if (advanceRunnable != null) {
              handler.removeCallbacks(advanceRunnable);
              advanceRunnable = null;
          }
      }

      private void showCurrentSlide() {
          if (!running || slides.isEmpty()) return;
          if (currentIndex >= slides.size()) currentIndex = 0;
          final NativeSlide slide = slides.get(currentIndex);
          final int capturedIndex = currentIndex;
          final SlideType capturedType = slide.type;
          android.util.Log.d("NativePlaylist",
                  "[loop] slide " + capturedIndex + "/" + slides.size()
                  + " type=" + slide.type + " dur=" + slide.durationSec + "s");

          switch (slide.type) {
              case VIDEO:
                  // Raise dormancy flag then hand ExoPlayer the URL via JS bridge
                  delegate.evaluateJs(
                          "try{window.Android.setWebViewDormant(true);}catch(e){}" +
                          "try{window.Android.playNativeVideo(" +
                          JSONObject.quote(slide.url) +
                          ",0,0,window.innerWidth,window.innerHeight," +
                          JSONObject.quote(slide.objectFit) + "," +
                          slide.loop + "," + slide.volume + ");}catch(e){}");
                  break;
              case IMAGE:
                  // Raise dormancy flag then hand Glide the URL via JS bridge
                  delegate.evaluateJs(
                          "try{window.Android.setWebViewDormant(true);}catch(e){}" +
                          "try{window.Android.showNativeImage(" +
                          JSONObject.quote(slide.url) +
                          ",0,0,window.innerWidth,window.innerHeight," +
                          JSONObject.quote(slide.scaleType) + ");}catch(e){}");
                  break;
              case WEBVIEW_DELEGATED:
                  // Wake WebView for JS-rendered content
                  delegate.evaluateJs(
                          "try{window.Android.setWebViewDormant(false);}catch(e){}");
                  break;
          }

          final long durationMs = Math.max(1_000L, slide.durationSec * 1000L);
          final int nextIndex = (capturedIndex + 1) % slides.size();
          advanceRunnable = () -> {
              if (!running) return;
              // Hide the outgoing native overlay before showing the next slide
              if (capturedType == SlideType.VIDEO) {
                  delegate.evaluateJs("try{window.Android.stopNativeVideo();}catch(e){}");
              } else if (capturedType == SlideType.IMAGE) {
                  delegate.evaluateJs("try{window.Android.hideNativeImage();}catch(e){}");
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
  