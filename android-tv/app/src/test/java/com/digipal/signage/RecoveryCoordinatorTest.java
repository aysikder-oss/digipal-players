package com.digipal.signage;

  import static org.junit.Assert.assertEquals;

  import java.util.ArrayList;
  import java.util.List;

  import org.junit.Before;
  import org.junit.Test;
  import org.junit.runner.RunWith;
  import org.robolectric.RobolectricTestRunner;
  import org.robolectric.RuntimeEnvironment;

  @RunWith(RobolectricTestRunner.class)
  public class RecoveryCoordinatorTest {

      private final List<String> events = new ArrayList<>();
      private RecoveryCoordinator coordinator;

      private RecoveryCoordinator.EscalationDelegate delegate() {
          return new RecoveryCoordinator.EscalationDelegate() {
              @Override public void onSlideRetry(String slideId, String reason) { events.add("SLIDE_RETRY"); }
              @Override public void onSlideSkip(String slideId, String reason) { events.add("SLIDE_SKIP"); }
              @Override public void onRendererRebuild(String reason) { events.add("RENDERER_REBUILD"); }
              @Override public void onWebViewRebuild(String reason) { events.add("WEBVIEW_REBUILD"); }
              @Override public void onPlaylistRollback(String reason) { events.add("PLAYLIST_ROLLBACK"); }
              @Override public void onSoftRestart(String reason) { events.add("SOFT_RESTART"); }
              @Override public void onHardRestart(String reason) { events.add("HARD_RESTART"); }
          };
      }

      @Before
      public void setUp() {
          events.clear();
          coordinator = new RecoveryCoordinator(RuntimeEnvironment.getApplication(), delegate(), null);
      }

      @Test
      public void slideRetryFiresBelowSkipThreshold() {
          coordinator.reportSlideFailure("slide-1", "asset-1", "decode error", "image", null);
          coordinator.reportSlideFailure("slide-1", "asset-1", "decode error", "image", null);
          assertEquals(2, events.size());
          assertEquals("SLIDE_RETRY", events.get(0));
          assertEquals("SLIDE_RETRY", events.get(1));
      }

      @Test
      public void slideSkipFiresAtThresholdWhenGlobalFailuresLow() {
          coordinator.reportSlideFailure("slide-1", "asset-1", "decode error", "image", null);
          coordinator.reportSlideFailure("slide-1", "asset-1", "decode error", "image", null);
          coordinator.reportSlideFailure("slide-1", "asset-1", "decode error", "image", null);
          assertEquals("SLIDE_SKIP", events.get(2));
      }

      @Test
      public void globalEscalationTakesPriorityOverSlideSkip() {
          for (int i = 0; i < 4; i++) {
              coordinator.reportSlideFailure("slide-" + i, "asset-" + i, "decode error", "image", null);
          }
          coordinator.reportSlideFailure("slide-4", "asset-4", "decode error", "image", null);
          assertEquals("RENDERER_REBUILD", events.get(4));
      }

      @Test
      public void independentSlideFailuresDoNotShareCounters() {
          coordinator.reportSlideFailure("slide-a", "asset-a", "decode error", "image", null);
          coordinator.reportSlideFailure("slide-b", "asset-b", "decode error", "image", null);
          assertEquals("SLIDE_RETRY", events.get(0));
          assertEquals("SLIDE_RETRY", events.get(1));
      }

      @Test
      public void webViewCrashEscalatesToHardRestartAfterThreeCrashesInWindow() {
          coordinator.reportWebViewCrash("renderer crash", null);
          coordinator.reportWebViewCrash("renderer crash", null);
          coordinator.reportWebViewCrash("renderer crash", null);
          assertEquals("WEBVIEW_REBUILD", events.get(0));
          assertEquals("WEBVIEW_REBUILD", events.get(1));
          assertEquals("HARD_RESTART", events.get(2));
      }

      @Test
      public void watchdogTriggerAlwaysRoutesToSoftRestart() {
          coordinator.reportWatchdogTrigger("watchdog fired", null);
          assertEquals(1, events.size());
          assertEquals("SOFT_RESTART", events.get(0));
      }
  }
  