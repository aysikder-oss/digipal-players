package com.nexuscast.player;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SmartTriggerBridgeTest {
    @Test public void realWebViewAuthenticatedBridgeAndAdapterAreCallable() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("webView");
                    field.setAccessible(true);
                    WebView view = (WebView) field.get(activity);
                    assertNotNull(view);
                    view.loadDataWithBaseURL("https://www.digipalsignage.com/tv/TESTST",
                            "<html><body>ST bridge test</body></html>", "text/html", "UTF-8", null);
                    view.postDelayed(() -> loaded.countDown(), 2000);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            assertTrue(loaded.await(15, TimeUnit.SECONDS));
            CountDownLatch called = new CountDownLatch(1);
            final String[] result = new String[1];
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("webView");
                    field.setAccessible(true);
                    WebView view = (WebView) field.get(activity);
                    // Inject a known test token into both sides, then drive actual
                    // JS -> @JavascriptInterface calls, not recorder-only mocks.
                    Field token = MainActivity.class.getDeclaredField("bridgeToken");
                    token.setAccessible(true);
                    token.set(activity, "test-page-token");
                    view.evaluateJavascript("window.__digipalBridgeToken='test-page-token';"
                            + "(function(){try{"
                            + "Android.reportPairingCode('TESTST');"
                            + "Android.setPlaylistRevisionId('test-page-token','123');"
                            + "Android.setNativePlaylist('test-page-token','[]');"
                            + "Android.setSmartTriggerConfig('test-page-token','[]');"
                            + "Android.downloadMedia('wrong-token','/objects/test','https://invalid.example');"
                            + "return JSON.stringify({adapter:!!window.smartTriggers,"
                            + "paired:Android.getSmartTriggerPairingCode('test-page-token')==='TESTST',"
                            + "invalid:Android.getSmartTriggerPairingCode('wrong-token')==='',"
                            + "cache:!Android.getLocalMediaPath('test-page-token','/objects/missing'),"
                            + "devices:Android.getConnectedDevices('test-page-token')==='[]'});"
                            + "}catch(e){return String(e)}})()", value -> { result[0] = value; called.countDown(); });
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            assertTrue(called.await(15, TimeUnit.SECONDS));
            assertNotNull(result[0]);
            assertFalse(result[0], result[0].contains("false"));
            assertTrue(result[0], result[0].contains("paired"));
        }
    }
}
