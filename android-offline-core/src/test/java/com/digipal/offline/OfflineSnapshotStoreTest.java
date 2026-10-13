package com.digipal.offline;

import android.app.Application;
import android.content.ContextWrapper;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Filesystem/contract tests, not device startup or renderer evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OfflineSnapshotStoreTest {
    private static final String SERVER = "https://player.example";
    private File directory, root;
    private OfflineSnapshotStore store;

    @Before public void setup() throws Exception {
        directory = File.createTempFile("offline-interface", ".test");
        assertTrue(directory.delete());
        assertTrue(directory.mkdir());
        store = new OfflineSnapshotStore(new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return directory; }
        });
        root = new File(directory, "offline_interface_v1");
    }
    @After public void cleanup() { remove(directory); }

    @Test public void acknowledgementPromotesExecutedNotNewlyDownloadedCurrent() throws Exception {
        JSONObject a = snapshot("A"), b = snapshot("B");
        writeRecord("current.json", a);
        assertEquals(SERVER + "/player?platform=android_tv", store.beginBoot(SERVER));
        assertEquals("", store.getBootHash());
        writeRecord("current.json", b);
        assertTrue(store.acknowledgeExecuted(a.getString("buildId")));
        assertEquals(a.getString("buildId"), readRecord("last_good.json").getString("buildId"));
        assertEquals("abcdef012345", store.getBootHash());
    }
    @Test public void staleDocumentIdentityCannotAcknowledgeSelectedVersion() throws Exception {
        JSONObject a = snapshot("A"), b = snapshot("B");
        writeRecord("current.json", b);
        store.beginBoot(SERVER);
        assertFalse(store.acknowledgeExecuted(a.getString("buildId")));
        assertFalse(new File(root, "last_good.json").exists());
        assertEquals("", store.getBootHash());
    }
    @Test public void missingCurrentDependencyFallsBackWithoutDestroyingSnapshots() throws Exception {
        JSONObject a = snapshot("A"), b = snapshot("B");
        writeRecord("last_good.json", a);
        writeRecord("current.json", b);
        assertTrue(new File(root, b.getString("buildId") + "/assets/player.js").delete());
        assertNotNull(store.beginBoot(SERVER));
        assertEquals(a.getString("buildId"), store.getBootVersion());
        assertEquals(b.getString("buildId"), readRecord("current.json").getString("buildId"));
    }
    @Test public void networkSuspensionRetainsBytesAndNextBootRestoresInterception() throws Exception {
        JSONObject a = snapshot("A");
        writeRecord("current.json", a);
        store.beginBoot(SERVER);
        WebResourceResponse response = store.intercept(request());
        assertNotNull(response);
        String html = read(response.getData());
        assertTrue(html.contains("window.__digipalOfflineExecutedBuildId=\"" + a.getString("buildId") + "\""));
        store.suspendInterception();
        assertNull(store.intercept(request()));
        assertTrue(new File(root, a.getString("buildId") + "/assets/player.js").exists());
        store.beginBoot(SERVER);
        assertNotNull(store.intercept(request()));
    }
    @Test public void knownFailedInterfacesAreNotRepeatedAndOtherOriginsAreNotRestored() throws Exception {
        JSONObject a = snapshot("A");
        writeRecord("current.json", a);
        writeRecord("last_good.json", a);
        store.beginBoot(SERVER);
        assertNull(store.beginUnfailedBoot(SERVER, Collections.singleton(a.getString("buildId"))));
        assertTrue(new File(root, "executed.json").exists());
        assertNull(store.beginBoot("https://another.example"));
        assertTrue(new File(root, "last_good.json").exists());
    }

    private JSONObject snapshot(String value) throws Exception {
        String[] paths = {"/assets/player.js", "/offline-player.html", "/render.html"};
        byte[][] bytes = {
            ("window.example='" + value + "';").getBytes(StandardCharsets.UTF_8),
            ("<html><head></head><body>" + value + "</body></html>").getBytes(StandardCharsets.UTF_8),
            "<html><head></head><body>render</body></html>".getBytes(StandardCharsets.UTF_8)
        };
        JSONArray resources = new JSONArray();
        StringBuilder canonical = new StringBuilder("[");
        for (int i = 0; i < paths.length; i++) {
            String digest = sha(bytes[i]);
            resources.put(new JSONObject().put("path", paths[i]).put("size", bytes[i].length).put("sha256", digest));
            if (i > 0) canonical.append(",");
            // Match the Node producer's ordered JSON, not Android/JVM object
            // serializers' key order or optional slash escaping.
            canonical.append("{\"path\":\"").append(paths[i]).append("\",\"size\":").append(bytes[i].length)
                    .append(",\"sha256\":\"").append(digest).append("\"}");
        }
        String id = sha(canonical.append("]").toString().getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < paths.length; i++) write(new File(root, id + paths[i]), bytes[i]);
        return new JSONObject().put("version", 1).put("buildId", id).put("websiteBuildVersion", "abcdef012345")
                .put("origin", SERVER).put("bootPath", "/player?platform=android_tv")
                .put("playerHtml", "/offline-player.html").put("renderHtml", "/render.html").put("resources", resources);
    }
    private void writeRecord(String name, JSONObject value) throws Exception {
        write(new File(root, name), value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject readRecord(String name) throws Exception {
        return new JSONObject(read(new FileInputStream(new File(root, name))));
    }
    private static void write(File file, byte[] bytes) throws Exception {
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static String read(InputStream in) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = source.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }
    private static void remove(File file) {
        File[] files = file.listFiles();
        if (files != null) for (File child : files) remove(child);
        file.delete();
    }
    private static WebResourceRequest request() {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse(SERVER + "/player?platform=android_tv"); }
            public boolean isForMainFrame() { return true; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return "GET"; }
            public Map<String,String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }
}
