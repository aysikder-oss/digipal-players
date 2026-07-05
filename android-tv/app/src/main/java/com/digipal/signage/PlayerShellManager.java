package com.digipal.signage;

import android.content.Context;
import android.util.Log;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * PlayerShellManager — local versioned copy of the player shell (task local
 * player shell hardening). The Android TV app normally boots by calling
 * webView.loadUrl(serverUrl + "player?platform=android_tv") every single
 * time, which means a server or network outage at boot leaves the app with
 * nothing to show. This class downloads and caches a versioned snapshot of
 * that shell (index.html + same-origin script/link assets) under app-private
 * storage, serves it via WebViewAssetLoader (no raw file:// URLs — keeps
 * existing WebView security posture), and keeps a "last known good" pointer
 * that boot always prefers to fall back to over the network if the freshest
 * downloaded version fails its health check.
 *
 * Storage layout (under context.getFilesDir()):
 *   player_shell/<version>/index.html
 *   player_shell/<version>/<asset files...>
 *   player_shell/current.json     -> { "version": "...", "serverUrl": "..." }
 *   player_shell/last_good.json   -> { "version": "...", "serverUrl": "..." }
 *
 * This is purely additive: if no local shell has ever been downloaded yet
 * (e.g. first boot after install), getBootUrl() returns null and the caller
 * keeps loading straight from the server, exactly as before.
 */
public class PlayerShellManager {

    private static final String TAG = "PlayerShellManager";
    private static final String ROOT_DIR_NAME = "player_shell";
    private static final String CURRENT_JSON = "current.json";
    private static final String LAST_GOOD_JSON = "last_good.json";
    private static final String ASSET_LOADER_DOMAIN = "appassets.androidplatform.net";
    private static final String ASSET_LOADER_HTTP_PATH = "/player_shell/";
    private static final int HTTP_TIMEOUT_MS = 10_000;
    private static final int MAX_ASSETS = 40;

    /** Same-origin references worth caching locally: <script src>, <link href> (css/js only). */
    private static final Pattern ASSET_PATTERN = Pattern.compile(
            "(?:<script[^>]+src=|<link[^>]+href=)[\"']([^\"'>]+)[\"']", Pattern.CASE_INSENSITIVE);

    public interface DownloadCallback {
        void onSuccess(String version);
        void onFailure(String reason);
    }

    private final Context ctx;
    private final File rootDir;

    /** "local" or "remote" — updated by getBootUrl()/rollbackToLastGood() so callers
     *  (telemetry, renderer observability task) can report which shell is currently
     *  active without duplicating the boot-decision logic. */
    private volatile String lastBootSource = "remote";

    public PlayerShellManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.rootDir = new File(this.ctx.getFilesDir(), ROOT_DIR_NAME);
        if (!rootDir.exists()) rootDir.mkdirs();
    }

    /** "local" if the app most recently booted (or rolled back) into a locally cached
     *  shell snapshot, "remote" if it loaded serverUrl directly over the network. */
    public String getLastBootSource() { return lastBootSource; }

    /** Returns the WebViewAssetLoader wired to serve /player_shell/** from local storage. */
    public WebViewAssetLoader buildAssetLoader() {
        return new WebViewAssetLoader.Builder()
                .setDomain(ASSET_LOADER_DOMAIN)
                .addPathHandler(ASSET_LOADER_HTTP_PATH, new WebViewAssetLoader.InternalStoragePathHandler(ctx, rootDir))
                .build();
    }

    /**
     * Returns the local https://appassets.androidplatform.net/... URL to boot from if a
     * healthy local shell exists for this exact serverUrl, or null if the caller should
     * fall back to loading serverUrl directly over the network (first boot, or the only
     * cached version belongs to a different configured server).
     */
    public String getBootUrl(String serverUrl) {
        JsonRecord current = readRecord(CURRENT_JSON);
        if (current == null) { lastBootSource = "remote"; return null; }
        if (!serverUrl.equals(current.serverUrl)) { lastBootSource = "remote"; return null; }
        File indexHtml = new File(new File(rootDir, current.version), "index.html");
        if (!indexHtml.exists() || indexHtml.length() == 0) { lastBootSource = "remote"; return null; }
        lastBootSource = "local";
        return "https://" + ASSET_LOADER_DOMAIN + ASSET_LOADER_HTTP_PATH + current.version + "/index.html";
    }

    /** Call once the shell has actually rendered successfully (e.g. first WS connect / heartbeat). */
    public void markCurrentAsGood() {
        JsonRecord current = readRecord(CURRENT_JSON);
        if (current == null) return;
        writeRecord(LAST_GOOD_JSON, current);
        Log.i(TAG, "[markCurrentAsGood] version=" + current.version + " promoted to last_good");
    }

    /**
     * Call when the currently active shell failed to actually mount an app (blank screen,
     * e.g. a missing/broken JS chunk) even though the page itself finished loading with no
     * HTTP/network error. Clears "current" (but preserves "last_good") so the NEXT boot does
     * not keep loading the same broken local snapshot forever — it falls straight back to the
     * network until a fresh, validated shell is downloaded again.
     */
    public void invalidateCurrent() {
        File f = new File(rootDir, CURRENT_JSON);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "[invalidateCurrent] failed to delete " + CURRENT_JSON);
        } else {
            Log.w(TAG, "[invalidateCurrent] cleared current shell pointer after failed mount");
        }
    }

    /** If the currently active shell turns out to be broken at runtime, roll back and report which URL to reload. */
    public String rollbackToLastGood(String serverUrl) {
        JsonRecord lastGood = readRecord(LAST_GOOD_JSON);
        JsonRecord current = readRecord(CURRENT_JSON);
        if (lastGood == null || (current != null && lastGood.version.equals(current.version))) {
            Log.w(TAG, "[rollback] no distinct last_good version to roll back to");
            return null;
        }
        File indexHtml = new File(new File(rootDir, lastGood.version), "index.html");
        if (!indexHtml.exists() || indexHtml.length() == 0) {
            Log.w(TAG, "[rollback] last_good version " + lastGood.version + " missing on disk");
            return null;
        }
        writeRecord(CURRENT_JSON, lastGood);
        Log.w(TAG, "[rollback] restored current -> last_good version=" + lastGood.version);
        lastBootSource = "local";
        return "https://" + ASSET_LOADER_DOMAIN + ASSET_LOADER_HTTP_PATH + lastGood.version + "/index.html";
    }

    /**
     * Downloads a fresh copy of the shell from serverUrl in a background thread, health-checks
     * it, and only promotes it to "current" if the check passes. Never touches last_good on
     * failure. Safe to call opportunistically (e.g. once per boot, or on a timer) — failures are
     * silent from the caller's perspective beyond the callback, since the app keeps running on
     * whatever shell (local or server) it already booted with.
     */
    public void downloadShellAsync(String serverUrl, DownloadCallback callback) {
        new Thread(() -> {
            try {
                String version = String.valueOf(System.currentTimeMillis());
                File versionDir = new File(rootDir, version);
                if (!versionDir.mkdirs() && !versionDir.exists()) {
                    throw new IllegalStateException("could not create version dir");
                }

                String playerUrl = normalize(serverUrl) + "player?platform=android_tv";
                String html = httpGetText(playerUrl);
                if (html == null || html.length() < 200) {
                    throw new IllegalStateException("shell HTML too small/empty");
                }
                writeFile(new File(versionDir, "index.html"), html.getBytes("UTF-8"));

                List<String> assetPaths = extractAssetPaths(html);
                List<String> sameOriginAssets = new ArrayList<>();
                List<String> failedAssets = new ArrayList<>();
                int downloaded = 0;
                for (String assetPath : assetPaths) {
                    if (assetPath.startsWith("http://") || assetPath.startsWith("https://")) {
                        // Skip cross-origin assets (fonts CDN, analytics, etc.) — only same-origin
                        // shell code needs to be cached locally for offline boot.
                        continue;
                    }
                    sameOriginAssets.add(assetPath);
                    if (downloaded >= MAX_ASSETS) {
                        failedAssets.add(assetPath);
                        continue;
                    }
                    try {
                        String assetUrl = normalize(serverUrl) + stripLeadingSlash(assetPath);
                        byte[] bytes = httpGetBytes(assetUrl);
                        if (bytes == null || bytes.length == 0) {
                            failedAssets.add(assetPath);
                            continue;
                        }
                        File dest = SafeFiles.child(versionDir, sanitizeRelativePath(assetPath));
                        if (dest == null) {
                            failedAssets.add(assetPath);
                            continue;
                        }
                        File parent = dest.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        writeFile(dest, bytes);
                        downloaded++;
                    } catch (Exception assetEx) {
                        Log.w(TAG, "[download] asset failed: " + assetPath + " — " + assetEx.getMessage());
                        failedAssets.add(assetPath);
                    }
                }

                // Every same-origin script/link asset referenced by the shell HTML must download
                // successfully. A partial download (network blip mid-fetch, an asset list that
                // exceeds MAX_ASSETS, etc.) used to still pass the old "does index.html contain
                // <script>" check and get silently promoted to "current" — the next boot would
                // then load a shell missing its own JS/CSS with no network fallback
                // (WebViewAssetLoader 404s local misses instead of retrying over the network),
                // producing a permanently blank screen. Treat any missing same-origin asset as a
                // failed download so a broken shell is never promoted.
                if (!failedAssets.isEmpty()) {
                    Log.w(TAG, "[download] " + failedAssets.size() + "/" + sameOriginAssets.size()
                            + " same-origin assets failed to download — discarding version " + version);
                    deleteRecursive(versionDir);
                    if (callback != null) callback.onFailure("asset_download_incomplete");
                    return;
                }

                if (!healthCheck(versionDir)) {
                    deleteRecursive(versionDir);
                    if (callback != null) callback.onFailure("health_check_failed");
                    return;
                }

                JsonRecord record = new JsonRecord(version, serverUrl);
                writeRecord(CURRENT_JSON, record);
                pruneOldVersions(version);
                Log.i(TAG, "[download] new shell version=" + version + " assets=" + downloaded + " promoted to current");
                if (callback != null) callback.onSuccess(version);
            } catch (Exception e) {
                Log.e(TAG, "[download] shell download failed", e);
                if (callback != null) callback.onFailure(String.valueOf(e.getMessage()));
            }
        }, "PlayerShellDownload").start();
    }

    private boolean healthCheck(File versionDir) {
        File indexHtml = new File(versionDir, "index.html");
        if (!indexHtml.exists() || indexHtml.length() < 200) return false;
        // Basic sanity: the shell must at least reference a script tag — an empty error page or
        // a captive-portal redirect page would fail this check and be discarded before ever
        // being promoted to "current". (Asset completeness is already enforced by the caller via
        // the failedAssets check above, so this only guards the top-level document shape.)
        try {
            String head = readFileHead(indexHtml, 4096);
            return head.toLowerCase(Locale.ROOT).contains("<script");
        } catch (Exception e) {
            return false;
        }
    }

    private void pruneOldVersions(String keepVersion) {
        JsonRecord lastGood = readRecord(LAST_GOOD_JSON);
        File[] children = rootDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if (name.equals(keepVersion)) continue;
            if (lastGood != null && name.equals(lastGood.version)) continue;
            deleteRecursive(child);
        }
    }

    // ---- small local helpers (no external JSON/HTTP lib needed for this scope) ----

    private static class JsonRecord {
        final String version;
        final String serverUrl;
        JsonRecord(String version, String serverUrl) { this.version = version; this.serverUrl = serverUrl; }
    }

    private JsonRecord readRecord(String fileName) {
        try {
            File f = SafeFiles.child(rootDir, fileName);
            if (f == null || !f.exists()) return null;
            JSONObject json = new JSONObject(readFileHead(f, 8192));
            String version = json.optString("version", "");
            String serverUrl = json.optString("serverUrl", "");
            if (version.isEmpty() || serverUrl.isEmpty()) return null;
            return new JsonRecord(version, serverUrl);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeRecord(String fileName, JsonRecord record) {
        try {
            File dest = SafeFiles.child(rootDir, fileName);
            if (dest == null) throw new IllegalStateException("invalid record path");
            JSONObject json = new JSONObject();
            json.put("version", record.version);
            json.put("serverUrl", record.serverUrl);
            writeFile(dest, json.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "[writeRecord] failed for " + fileName, e);
        }
    }

    private static List<String> extractAssetPaths(String html) {
        List<String> paths = new ArrayList<>();
        Matcher m = ASSET_PATTERN.matcher(html);
        while (m.find()) {
            String path = m.group(1);
            if (path != null && !path.isEmpty() && !paths.contains(path)) paths.add(path);
        }
        return paths;
    }

    private static String normalize(String serverUrl) {
        return serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String sanitizeRelativePath(String path) {
        String p = stripLeadingSlash(path == null ? "" : path);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty() || p.contains("..") || p.startsWith(".")) return "asset";
        return p;
    }

    private static String httpGetText(String urlStr) throws Exception {
        byte[] bytes = httpGetBytes(urlStr);
        return bytes == null ? null : new String(bytes, "UTF-8");
    }

    private static byte[] httpGetBytes(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void writeFile(File file, byte[] bytes) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        }
    }

    private static String readFileHead(File file, int maxBytes) throws Exception {
        byte[] buf = new byte[(int) Math.min(maxBytes, file.length())];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int read = fis.read(buf);
            return new String(buf, 0, Math.max(read, 0), "UTF-8");
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        file.delete();
    }
}
