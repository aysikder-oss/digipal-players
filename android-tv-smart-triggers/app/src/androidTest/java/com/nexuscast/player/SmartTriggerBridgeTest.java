package com.nexuscast.player;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SmartTriggerBridgeTest {
    private LocalPlayerServer backend;
    @Before public void isolatedTestInstallation() throws Exception {
        backend = new LocalPlayerServer();
        android.content.Context context = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().getTargetContext();
        context.getSharedPreferences("DigipalPrefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("auto_relaunch", false)
                .putString("server_url", backend.origin()).apply();
        android.app.UiAutomation automation = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().getUiAutomation();
        automation.grantRuntimePermission(context.getPackageName(), "android.permission.ACCESS_COARSE_LOCATION");
        automation.grantRuntimePermission(context.getPackageName(), "android.permission.ACCESS_FINE_LOCATION");
    }
    @After public void closeLocalBackend() throws Exception {
        if (backend != null) backend.close();
    }
    @Test public void realWebViewAuthenticatedBridgeAndAdapterAreCallable() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("webView");
                    field.setAccessible(true);
                    WebView view = (WebView) field.get(activity);
                    assertNotNull(view);
                    view.loadUrl(backend.origin() + "/tv/TESTST");
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
                            + "Android.reportAppMounted();"
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

    @Test public void packagedGestureRuntimeInitializesBothVariantsInRealSTWebView() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                try {
                    WebView view = webView(activity);
                    view.loadUrl(backend.origin() + "/tv/TESTST");
                    view.postDelayed(loaded::countDown, 2000);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            assertTrue(loaded.await(15, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                try {
                    webView(activity).evaluateJavascript(
                            "window.__stRuntimeResult='pending';"
                            + "(async function(){try{"
                            + "for(const variant of ['wasm','wasm_nosimd']){"
                            + "const base='https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/wasm/vision_'+variant+'_internal';"
                            + "await new Promise((resolve,reject)=>{const s=document.createElement('script');"
                            + "s.src=base+'.js';s.onload=resolve;s.onerror=()=>reject(new Error('loader failed '+variant));"
                            + "document.head.appendChild(s)});"
                            + "const r=await fetch(base+'.wasm');"
                            + "if(!r.ok||r.headers.get('X-Digipal-Gesture-Runtime')!=='0.10.32')"
                            + "throw new Error('not packaged runtime: '+r.status);"
                            + "const module=await ModuleFactory({wasmBinary:new Uint8Array(await r.arrayBuffer())});"
                            + "if(!module.calledRun||typeof module._malloc!=='function')throw new Error('runtime not ready');"
                            + "}window.__stRuntimeResult='passed';"
                            + "}catch(e){window.__stRuntimeResult='failed: '+String(e)}})();", null);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            String value = "";
            long deadline = System.currentTimeMillis() + 90000;
            while (System.currentTimeMillis() < deadline) {
                CountDownLatch checked = new CountDownLatch(1);
                final String[] result = new String[1];
                scenario.onActivity(activity -> {
                    try {
                        webView(activity).evaluateJavascript("window.__stRuntimeResult",
                                response -> { result[0] = response; checked.countDown(); });
                    } catch (Exception e) { throw new AssertionError(e); }
                });
                assertTrue(checked.await(5, TimeUnit.SECONDS));
                value = result[0];
                if ("\"passed\"".equals(value) || (value != null && value.contains("failed:"))) break;
                Thread.sleep(500);
            }
            assertEquals("Real ST WebView runtime initialization: " + value, "\"passed\"", value);
        }
    }

    @Test public void nativeImageVideoImageKeepsTriggerTimersAndLearnResponsive() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final java.io.File[] image = new java.io.File[1], video = new java.io.File[1];
            scenario.onActivity(activity -> {
                try {
                    image[0] = new java.io.File(activity.getCacheDir(), "st-test-image.png");
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(android.graphics.Color.GREEN);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(image[0])) {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    }
                    bitmap.recycle();
                    video[0] = new java.io.File(activity.getCacheDir(), "st-test-video.mp4");
                    try (java.io.InputStream in = androidx.test.platform.app.InstrumentationRegistry
                            .getInstrumentation().getContext().getAssets().open("smoke.mp4");
                         java.io.FileOutputStream out = new java.io.FileOutputStream(video[0])) {
                        byte[] buffer = new byte[4096];
                        for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
                    }
                    WebView view = webView(activity);
                    view.loadUrl(backend.origin() + "/tv/TESTST");
                } catch (Exception e) { throw new AssertionError(e); }
            });
            Thread.sleep(1500);
            String init = evaluate(scenario, "window.__digipalBridgeToken='trigger-test-token';"
                    + "Android.reportAppMounted();"
                    + "window.testTicks=0;window.testCaptures=0;window.testReady=false;window.testKeyDowns=0;"
                    + "window.__digipalNativeImageReady_st_test=function(){window.testReady=true;Android.setWebViewDormant(true)};"
                    + "setInterval(function(){window.testTicks++},100);"
                    + "window.addEventListener('hw:signalCaptured',function(){window.testCaptures++});"
                    + "window.addEventListener('keydown',function(e){window.testLastKey={code:e.code,key:e.key,keyCode:e.keyCode};"
                    + "if(e.code==='KeyB'||e.key==='b'||e.key==='B'||e.keyCode===66)window.testKeyDowns++});"
                    + "Android.showNativeImage(" + org.json.JSONObject.quote(android.net.Uri.fromFile(image[0]).toString())
                    + ",0,0,128,128,'contain','st_test');true", "trigger-test-token");
            assertEquals("true", init);
            Thread.sleep(2000);
            assertEquals("true", evaluate(scenario, "window.testReady && window.testTicks>3", null));
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_B);
            Thread.sleep(300);
            assertEquals("Android virtual keys can lack a physical KeyboardEvent.code: "
                    + evaluate(scenario, "JSON.stringify(window.testLastKey)", null),
                    "1", evaluate(scenario, "window.testKeyDowns", null));
            evaluate(scenario, "window.smartTriggers.startLearnMode({});true", null);
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_A);
            Thread.sleep(300);
            assertEquals("true", evaluate(scenario, "window.testCaptures===1", null));
            evaluate(scenario, "Android.playNativeVideo(" + org.json.JSONObject.quote(android.net.Uri.fromFile(video[0]).toString())
                    + ",0,0,128,128,'contain',true,0,'st_video');true", null);
            Thread.sleep(2000);
            scenario.onActivity(activity -> {
                try {
                    Field playerField = MainActivity.class.getDeclaredField("exoPlayer");
                    playerField.setAccessible(true);
                    androidx.media3.exoplayer.ExoPlayer player = (androidx.media3.exoplayer.ExoPlayer) playerField.get(activity);
                    assertNotNull("Native video player must exist", player);
                    assertEquals(androidx.media3.common.Player.STATE_READY, player.getPlaybackState());
                    assertTrue("Native video must advance", player.getCurrentPosition() > 0);
                    assertEquals("ST compositor must remain active", android.view.View.VISIBLE, webView(activity).getVisibility());
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            evaluate(scenario, "Android.setHasBroadcast(true);true", null);
            Thread.sleep(300);
            scenario.onActivity(activity -> {
                try { assertEquals(1f, webView(activity).getAlpha(), 0.01f); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            evaluate(scenario, "Android.setHasBroadcast(false);"
                    + "Android.setPlaylistRevisionId('trigger-test-token','999');"
                    + "Android.setNativePlaylist('trigger-test-token','[]');true", null);
            Thread.sleep(300);
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("exoPlayer");
                    field.setAccessible(true);
                    androidx.media3.exoplayer.ExoPlayer player = (androidx.media3.exoplayer.ExoPlayer) field.get(activity);
                    assertTrue("Trigger override must stop native video", player == null || !player.isPlaying());
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            evaluate(scenario, "window.testReady=false;"
                    + "Android.showNativeImage(" + org.json.JSONObject.quote(android.net.Uri.fromFile(image[0]).toString())
                    + ",0,0,128,128,'contain','st_test');true", null);
            Thread.sleep(1500);
            assertEquals("true", evaluate(scenario, "window.testReady && window.testTicks>30 && window.testCaptures===1", null));
        }
    }

    private static WebView webView(MainActivity activity) throws ReflectiveOperationException {
        Field field = MainActivity.class.getDeclaredField("webView");
        field.setAccessible(true);
        return (WebView) field.get(activity);
    }

    private static String evaluate(ActivityScenario<MainActivity> scenario, String script, String token) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        scenario.onActivity(activity -> {
            try {
                if (token != null) {
                    Field field = MainActivity.class.getDeclaredField("bridgeToken");
                    field.setAccessible(true);
                    field.set(activity, token);
                }
                webView(activity).evaluateJavascript(script, value -> { result[0] = value; done.countDown(); });
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        assertTrue("WebView JS must respond", done.await(10, TimeUnit.SECONDS));
        return result[0];
    }
}
