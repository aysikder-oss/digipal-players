package com.nexuscast.player;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.core.app.ApplicationProvider;
import androidx.webkit.ServiceWorkerClientCompat;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GestureRuntimeServiceWorkerTest {
    private static final String ORIGIN = "https://www.digipalsignage.com";
    private static final String ROOT = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/wasm/";
    private static final class Recorder implements GestureRuntimeServiceWorker.Transport {
        ServiceWorkerClientCompat client;
        boolean supported = true;
        public boolean supported() { return supported; }
        public void setClient(ServiceWorkerClientCompat client) { this.client = client; }
    }
    private WebResourceRequest request(String url, String origin) {
        return request(url, origin, null);
    }
    private WebResourceRequest request(String url, String origin, String range) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse(url); }
            public boolean isForMainFrame() { return false; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return "GET"; }
            public Map<String, String> getRequestHeaders() {
                Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Referer", origin);
                if (range != null) headers.put("Range", range);
                return headers;
            }
        };
    }
    private GestureRuntimeServiceWorker handler(Recorder recorder, AtomicBoolean trusted) {
        GestureRuntimeAssets runtime = new GestureRuntimeAssets(
                ApplicationProvider.<android.content.Context>getApplicationContext().getAssets());
        return new GestureRuntimeServiceWorker(runtime, trusted::get, ORIGIN::equals, recorder);
    }

    @Test public void candidateMediaUsesCorrectedStreamThroughInstalledServiceWorkerClient() throws Exception {
        Recorder recorder = new Recorder();
        GestureRuntimeServiceWorker worker = handler(recorder, new AtomicBoolean(true));
        java.io.File file = java.io.File.createTempFile("candidate-media", ".mp4");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(new byte[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15});
        }
        worker.setAdditionalInterceptor(req -> {
            if (!"appassets.androidplatform.net".equals(req.getUrl().getHost())) return null;
            try {
                return MainActivity.createOfflineCandidateMediaResponse(req, file,
                        "video/mp4", ORIGIN, "service-worker");
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        });
        try {
            assertTrue(worker.install());
            WebResourceResponse response = recorder.client.shouldInterceptRequest(
                    request("https://appassets.androidplatform.net/media/test", ORIGIN, "bytes=8-11"));
            assertEquals(206, response.getStatusCode());
            assertFalse(response.getResponseHeaders().containsKey("Content-Length"));
            try (java.io.InputStream in = response.getData()) {
                assertEquals(16, in.available());
                assertEquals(8, in.skip(8));
                assertEquals(8, in.read());
                assertEquals(9, in.read());
                assertEquals(10, in.read());
                assertEquals(11, in.read());
                assertEquals(-1, in.read());
            }
        } finally { worker.close(); file.delete(); }
    }

    @Test public void candidateInterfaceComposesWithoutDiscardingGestureInterception() {
        Recorder recorder = new Recorder();
        AtomicBoolean trusted = new AtomicBoolean(true);
        GestureRuntimeServiceWorker worker = handler(recorder, trusted);
        WebResourceResponse local = new WebResourceResponse("text/html", "UTF-8",
                new java.io.ByteArrayInputStream("interface".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        worker.setAdditionalInterceptor(req -> req.getUrl().getPath().equals("/player") ? local : null);
        try {
            assertTrue(worker.install());
            assertSame(local, recorder.client.shouldInterceptRequest(request(ORIGIN + "/player", ORIGIN)));
            assertNotNull(recorder.client.shouldInterceptRequest(request(ROOT + "vision_wasm_internal.js", ORIGIN)));
            assertNull(recorder.client.shouldInterceptRequest(request("https://unrelated.example/file.js", ORIGIN)));
        } finally { worker.close(); }
    }

    @Test public void workerRequestsUseTheSamePackagedRuntimeAndSecurityChecks() throws Exception {
        Recorder recorder = new Recorder();
        AtomicBoolean trusted = new AtomicBoolean(true);
        try (GestureRuntimeServiceWorker handler = handler(recorder, trusted)) {
            assertTrue(handler.install());
            for (String variant : new String[]{"wasm", "wasm_nosimd"}) {
                for (String extension : new String[]{"js", "wasm"}) {
                    WebResourceResponse response = recorder.client.shouldInterceptRequest(
                            request(ROOT + "vision_" + variant + "_internal." + extension, ORIGIN));
                    assertEquals(200, response.getStatusCode());
                    assertEquals("0.10.32", response.getResponseHeaders().get("X-Digipal-Gesture-Runtime"));
                    response.getData().close();
                }
            }
            assertNull(recorder.client.shouldInterceptRequest(request(ORIGIN + "/api/status", ORIGIN)));
            assertEquals(403, recorder.client.shouldInterceptRequest(
                    request(ROOT + "vision_wasm_internal.js", "https://evil.test")).getStatusCode());
            trusted.set(false);
            assertEquals(403, recorder.client.shouldInterceptRequest(
                    request(ROOT + "vision_wasm_internal.js", ORIGIN)).getStatusCode());
        }
        assertNull(recorder.client);
    }

    @Test public void retiringActivityCannotRemoveReplacementClient() {
        Recorder recorder = new Recorder();
        GestureRuntimeServiceWorker first = handler(recorder, new AtomicBoolean(true));
        GestureRuntimeServiceWorker second = handler(recorder, new AtomicBoolean(true));
        try {
            first.install();
            second.install();
            ServiceWorkerClientCompat replacement = recorder.client;
            first.close();
            assertSame(replacement, recorder.client);
        } finally {
            first.close();
            second.close();
        }
        assertNull(recorder.client);
    }

    @Test public void unsupportedEnginesDoNotAccessTheController() {
        Recorder recorder = new Recorder();
        recorder.supported = false;
        try (GestureRuntimeServiceWorker handler = handler(recorder, new AtomicBoolean(true))) {
            assertFalse(handler.install());
            assertNull(recorder.client);
        }
    }
}
