package com.digipal.offline;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;

/** Optional candidate-only shell store. Does not move or erase existing customer storage. */
public final class OfflineSnapshotStore {
    private static final long MAX_TOTAL = 64L * 1024 * 1024;
    private static final int MAX_FILES = 512;
    private final File root;
    private String bootVersion = "";
    private String bootHash = "";
    private String bootOrigin = "";
    private boolean executedAcknowledged = false;

    public OfflineSnapshotStore(Context context) {
        root = new File(context.getFilesDir(), "offline_interface_v1");
        if (!root.exists()) root.mkdirs();
    }

    public synchronized String beginBoot(String server) {
        JSONObject selected = read("current.json");
        if (!usable(selected, server)) selected = read("last_good.json");
        if (!usable(selected, server)) {
            bootVersion = ""; bootHash = ""; bootOrigin = "";
            deleteRecord("executed.json");
            return null;
        }
        return execute(selected);
    }

    public synchronized String beginUnfailedBoot(String server, Set<String> failed) {
        JSONObject selected = read("current.json");
        if (selected == null || failed.contains(selected.optString("buildId")) || !usable(selected, server))
            selected = read("last_good.json");
        if (selected == null || failed.contains(selected.optString("buildId")) || !usable(selected, server)) return null;
        return execute(selected);
    }

    private String execute(JSONObject selected) {
        deleteRecord("network_session.json");
        executedAcknowledged = false;
        bootVersion = selected.optString("buildId");
        bootHash = selected.optString("websiteBuildVersion");
        bootOrigin = selected.optString("origin");
        writeRecord("executed.json", selected);
        return bootOrigin + "/player?platform=android_tv";
    }

    public synchronized String rollback(String server) {
        JSONObject previous = read("last_good.json");
        if (!usable(previous, server) || previous.optString("buildId").equals(bootVersion)) return null;
        // Keep the last-good pointer independent of any downloaded current pointer.
        return execute(previous);
    }

    public synchronized boolean acknowledgeExecuted(String actualBuildId) {
        if (!bootVersion.equals(actualBuildId) || bootVersion.isEmpty()) return false;
        JSONObject executed = read("executed.json");
        if (executed != null && executed.optString("buildId").equals(bootVersion)
                && usable(executed, bootOrigin)) {
            writeRecord("last_good.json", executed);
            executedAcknowledged = true;
            return true;
        }
        return false;
    }

    public synchronized String getBootVersion() { return bootVersion; }
    public synchronized String getBootHash() { return executedAcknowledged ? bootHash : ""; }
    public synchronized boolean hasExecutedSnapshot() { return !bootVersion.isEmpty(); }

    /** Keep all snapshots; only pause interception for this network attempt. */
    public synchronized void suspendInterception() {
        try { writeRecord("network_session.json", new JSONObject().put("paused", true)); }
        catch (Exception ignored) {}
        executedAcknowledged = false;
    }

    /** A bad download is never promoted, and refreshing never changes the executed pointer. */
    public JSONObject prepare(String server) throws Exception {
        String origin = origin(server);
        JSONObject manifest = new JSONObject(new String(download(origin + "/offline-player-manifest.json", 1024 * 1024), "UTF-8"));
        validateManifest(manifest);
        manifest.put("origin", origin);
        File stage = new File(root, ".partial-" + UUID.randomUUID());
        if (!stage.mkdirs()) throw new IOException("stage-unavailable");
        try {
            JSONArray resources = manifest.getJSONArray("resources");
            for (int i = 0; i < resources.length(); i++) {
                JSONObject resource = resources.getJSONObject(i);
                String resourcePath = resource.getString("path");
                long expected = resource.getLong("size");
                byte[] bytes = download(origin + resourcePath, (int) expected);
                if (bytes.length != expected || !sha(bytes).equals(resource.getString("sha256"))) {
                    throw new IOException("resource-integrity");
                }
                File target = safeFile(stage, resourcePath);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("directory-unavailable");
                try (FileOutputStream output = new FileOutputStream(target)) {
                    output.write(bytes);
                    output.getFD().sync();
                }
            }
            writeAtomic(new File(stage, "snapshot.json"), manifest);
            synchronized (this) {
                File destination = snapshot(manifest.getString("buildId"));
                if (destination.exists()) {
                    if (!usable(manifest, origin)) {
                        File quarantine = new File(root, ".invalid-" + UUID.randomUUID());
                        if (!destination.renameTo(quarantine)) throw new IOException("replace-unavailable");
                        if (!stage.renameTo(destination)) {
                            quarantine.renameTo(destination);
                            throw new IOException("activation-unavailable");
                        }
                        removeTree(quarantine);
                    } // An existing verified immutable snapshot can be reused.
                } else if (!stage.renameTo(destination)) throw new IOException("activation-unavailable");
                if (!usable(manifest, origin)) throw new IOException("verification-failed");
                writeRecord("current.json", manifest);
                prune();
            }
            return manifest;
        } finally { removeTree(stage); }
    }

    /** Only a declared immutable shell resource, never API data or arbitrary filesystem paths. */
    public synchronized WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null || !"GET".equals(request.getMethod())) return null;
        if (read("network_session.json") != null) return null;
        try {
            Uri uri = request.getUrl();
            JSONObject executed = read("executed.json");
            if (executed == null || !origin(uri.toString()).equals(executed.getString("origin"))) return null;
            String route = uri.getPath();
            if ("/player".equals(route)) route = executed.getString("playerHtml");
            else if (route != null && route.startsWith("/tv/render/")) route = executed.getString("renderHtml");
            JSONObject resource = findResource(executed, route);
            if (resource == null) return null;
            File file = safeFile(snapshot(executed.getString("buildId")), route);
            if (!file.isFile() || file.length() != resource.getLong("size")) return null;
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            InputStream response = new FileInputStream(file);
            if (route.endsWith(".html")) {
                response.close();
                String html = new String(readBounded(file, (int) resource.getLong("size")), "UTF-8");
                String bootstrap = "<script>window.__digipalOfflineShellV1=true;window.__digipalOfflineExecutedBuildId="
                        + JSONObject.quote(executed.getString("buildId")) + ";</script>";
                JSONArray resources = executed.getJSONArray("resources");
                for (int i = 0; i < resources.length(); i++) {
                    String path = resources.getJSONObject(i).getString("path");
                    if (path.endsWith(".css")) bootstrap += "<link rel=\"stylesheet\" href=\"" + path + "\">";
                }
                response = new ByteArrayInputStream(html.replace("<head>", "<head>" + bootstrap).getBytes("UTF-8"));
            } else if (route.endsWith(".css")) {
                response.close();
                String css = new String(readBounded(file, (int) resource.getLong("size")), "UTF-8");
                // The global interface import is not an artwork font dependency.
                css = css.replaceAll("@import\\s*(?:url\\([^)]*fonts\\.googleapis\\.com/[^)]*\\)|['\"]https://fonts\\.googleapis\\.com/[^'\"]+['\"])\\s*;", "");
                response = new ByteArrayInputStream(css.getBytes("UTF-8"));
            }
            return new WebResourceResponse(mime(route), "UTF-8", 200, "OK", headers, response);
        } catch (Exception ignored) { return null; }
    }

    private boolean usable(JSONObject manifest, String server) {
        try {
            if (manifest == null || !origin(server).equals(manifest.getString("origin"))) return false;
            validateManifest(manifest);
            File directory = snapshot(manifest.getString("buildId"));
            JSONArray resources = manifest.getJSONArray("resources");
            for (int i = 0; i < resources.length(); i++) {
                JSONObject resource = resources.getJSONObject(i);
                File file = safeFile(directory, resource.getString("path"));
                if (!file.isFile() || file.length() != resource.getLong("size")
                        || !sha(readBounded(file, (int) resource.getLong("size"))).equals(resource.getString("sha256"))) return false;
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static void validateManifest(JSONObject manifest) throws Exception {
        if (manifest.getInt("version") != 1 || !manifest.getString("buildId").matches("[a-f0-9]{64}")
                || !"/player?platform=android_tv".equals(manifest.getString("bootPath"))
                || !"/offline-player.html".equals(manifest.getString("playerHtml"))
                || !"/render.html".equals(manifest.getString("renderHtml"))) throw new IOException("manifest-contract");
        JSONArray resources = manifest.getJSONArray("resources");
        if (resources.length() < 2 || resources.length() > MAX_FILES) throw new IOException("manifest-bound");
        long total = 0;
        Set<String> paths = new HashSet<>();
        StringBuilder canonical = new StringBuilder("[");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.getJSONObject(i);
            String p = resource.getString("path");
            long size = resource.getLong("size");
            if (!p.matches("/[A-Za-z0-9/_.,@-]+") || Arrays.asList(p.split("/")).contains("..")
                    || !paths.add(p) || size <= 0 || size > 16 * 1024 * 1024
                    || !resource.getString("sha256").matches("[a-f0-9]{64}")) throw new IOException("manifest-resource");
            total += size;
            if (total > MAX_TOTAL) throw new IOException("manifest-storage-bound");
            if (i > 0) canonical.append(",");
            canonical.append("{\"path\":\"").append(p).append("\",\"size\":").append(size)
                    .append(",\"sha256\":\"").append(resource.getString("sha256")).append("\"}");
        }
        canonical.append("]");
        if (!sha(canonical.toString().getBytes("UTF-8")).equals(manifest.getString("buildId"))) throw new IOException("manifest-identity");
        if (!paths.contains("/offline-player.html") || !paths.contains("/render.html")) throw new IOException("manifest-entry");
    }

    private static JSONObject findResource(JSONObject manifest, String wanted) throws Exception {
        JSONArray resources = manifest.getJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.getJSONObject(i);
            if (resource.getString("path").equals(wanted)) return resource;
        }
        return null;
    }

    private static String origin(String address) throws Exception {
        URL url = new URL(address);
        if (!("https".equals(url.getProtocol()) || "http".equals(url.getProtocol()))
                || url.getUserInfo() != null || url.getHost().isEmpty()) throw new IOException("origin-invalid");
        return url.getProtocol() + "://" + url.getAuthority();
    }

    private File snapshot(String buildId) throws IOException {
        if (!buildId.matches("[a-f0-9]{64}")) throw new IOException("snapshot-invalid");
        return new File(root, buildId);
    }
    private static File safeFile(File directory, String resource) throws IOException {
        if (resource == null || !resource.startsWith("/") || resource.contains("\\")
                || resource.contains("?") || resource.contains("#")) throw new IOException("path-invalid");
        File file = new File(directory, resource.substring(1));
        if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new IOException("path-escape");
        return file;
    }
    private static byte[] download(String address, int max) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("http-status");
            try (InputStream input = connection.getInputStream()) { return readBounded(input, max); }
        } finally { connection.disconnect(); }
    }
    private static byte[] readBounded(File file, int max) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return readBounded(input, max); }
    }
    private static byte[] readBounded(InputStream input, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > max) throw new IOException("byte-bound");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) { hex.append("0123456789abcdef".charAt((value & 255) >> 4)); hex.append("0123456789abcdef".charAt(value & 15)); }
        return hex.toString();
    }
    private JSONObject read(String name) {
        try { return new JSONObject(new String(readBounded(new File(root, name), 1024 * 1024), "UTF-8")); }
        catch (Exception ignored) { return null; }
    }
    private void writeRecord(String name, JSONObject value) {
        try { writeAtomic(new File(root, name), value); }
        catch (Exception error) { throw new IllegalStateException("snapshot-record-write", error); }
    }
    private static void writeAtomic(File file, JSONObject value) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try { output.write(value.toString().getBytes("UTF-8")); atomic.finishWrite(output); }
        catch (Exception error) { atomic.failWrite(output); throw error; }
    }
    private void deleteRecord(String name) { new AtomicFile(new File(root, name)).delete(); }
    private void prune() {
        Set<String> keep = new HashSet<>();
        for (String name : new String[]{"current.json", "last_good.json", "executed.json"}) {
            JSONObject record = read(name);
            if (record != null) keep.add(record.optString("buildId"));
        }
        File[] files = root.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory() && file.getName().matches("[a-f0-9]{64}") && !keep.contains(file.getName())) removeTree(file);
        }
    }
    private static void removeTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeTree(child);
        file.delete();
    }
    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
