package com.nexuscast.player;

import android.app.Application;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.core.app.ApplicationProvider;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GestureRuntimeAssetsTest {
    private static final String ROOT = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@";
    private static final String TRUSTED = "https://www.digipalsignage.com";
    private static final String[] NAMES = {
        "vision_wasm_internal.js", "vision_wasm_internal.wasm",
        "vision_wasm_nosimd_internal.js", "vision_wasm_nosimd_internal.wasm"
    };
    private final GestureRuntimeAssets.OriginPolicy policy = TRUSTED::equals;

    private WebResourceRequest request(String url, String method, boolean main, Map<String, String> headers) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse(url); }
            public boolean isForMainFrame() { return main; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return method; }
            public Map<String, String> getRequestHeaders() { return headers; }
        };
    }
    private GestureRuntimeAssets runtime() {
        return new GestureRuntimeAssets(ApplicationProvider.<Application>getApplicationContext().getAssets());
    }
    private WebResourceRequest request(String url) {
        return request(url, "GET", false, Collections.singletonMap("Referer", TRUSTED));
    }

    @Test public void bothAliasesAndBothVariantsServePackagedResourcesWithCorrectTypes() throws Exception {
        for (String alias : new String[]{"latest", "0.10.32"}) {
            for (String name : NAMES) {
                WebResourceResponse response = runtime().intercept(request(ROOT + alias + "/wasm/" + name), true, policy);
                assertNotNull(response);
                assertEquals(200, response.getStatusCode());
                assertEquals("0.10.32", response.getResponseHeaders().get("X-Digipal-Gesture-Runtime"));
                assertEquals(name.endsWith(".wasm") ? "application/wasm" : "application/javascript", response.getMimeType());
                assertEquals(name.endsWith(".wasm") ? null : "UTF-8", response.getEncoding());
                try (java.io.InputStream in = response.getData()) {
                    assertTrue(in.read() >= 0);
                }
            }
        }
    }

    @Test public void unrelatedAndLookalikeUrlsStayOnExistingRouting() {
        String valid = ROOT + "latest/wasm/" + NAMES[0];
        String[] unrelated = {
            "https://www.digipalsignage.com/models/gesture_recognizer.task",
            "https://appassets.androidplatform.net/media/objects/video.mp4",
            "https://appassets.androidplatform.net/assets/index.js",
            ROOT + "0.10.33/wasm/" + NAMES[0],
            ROOT + "latest/vision_bundle.mjs",
            valid + "?x=1", valid + "#fragment", valid + "/extra",
            valid.replace("cdn.jsdelivr.net", "cdn.jsdelivr.net.evil.test"),
            valid.replace("cdn.jsdelivr.net", "user@cdn.jsdelivr.net"),
            valid.replace("cdn.jsdelivr.net", "cdn.jsdelivr.net:443"),
            valid.replace("https:", "http:"),
            valid.replace("vision_wasm", "%76ision_wasm")
        };
        for (String url : unrelated) assertNull(url, runtime().intercept(request(url), true, policy));
        assertNull(runtime().intercept(null, true, policy));
    }

    @Test public void pinCannotBeRequestedByAnUntrustedDocumentOrIframe() {
        String url = ROOT + "latest/wasm/" + NAMES[0];
        assertEquals(403, runtime().intercept(request(url), false, policy).getStatusCode());
        assertEquals(403, runtime().intercept(request(url, "GET", false,
                Collections.singletonMap("Referer", "https://evil.test")), true, policy).getStatusCode());
        assertEquals(403, runtime().intercept(request(url, "GET", false,
                Collections.emptyMap()), true, policy).getStatusCode());
        assertEquals(403, runtime().intercept(request(url, "GET", true,
                Collections.singletonMap("Referer", TRUSTED)), true, policy).getStatusCode());
        Map<String, String> mixed = new HashMap<>();
        mixed.put("Origin", "https://evil.test");
        mixed.put("Referer", TRUSTED);
        assertEquals(403, runtime().intercept(request(url, "GET", false, mixed), true, policy).getStatusCode());
        assertEquals(200, runtime().intercept(request(url, "GET", false,
                Collections.singletonMap("origin", TRUSTED)), true, policy).getStatusCode());
        assertEquals(405, runtime().intercept(request(url, "POST", false,
                Collections.singletonMap("Referer", TRUSTED)), true, policy).getStatusCode());
    }

    @Test public void missingPackagedFileFailsExplicitlyInsteadOfFetchingLatest() {
        GestureRuntimeAssets missing = new GestureRuntimeAssets(
                (GestureRuntimeAssets.AssetSource) path -> { throw new java.io.IOException("missing test asset"); });
        WebResourceResponse response = missing.intercept(request(ROOT + "latest/wasm/" + NAMES[0]), true, policy);
        assertEquals(503, response.getStatusCode());
        assertNotNull(response.getData());
    }
}
