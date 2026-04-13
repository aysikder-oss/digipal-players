package com.nexuscast.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaDownloadManager {

    private static final String MANIFEST_PREFS = "DigipalMediaManifest";
    private static final String KEY_MANIFEST = "manifest";
    private static final int BUFFER_SIZE = 8192;
    private static final long LOW_STORAGE_THRESHOLD = 100 * 1024 * 1024L;

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Set<String> activeDownloads;
    private WebView webView;

    public MediaDownloadManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(MANIFEST_PREFS, Context.MODE_PRIVATE);
        this.executor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.activeDownloads = new HashSet<>();
    }

    public void setWebView(WebView webView) {
        this.webView = webView;
    }

    public void downloadMedia(final String objectPath, final String signedUrl) {
        synchronized (activeDownloads) {
            if (activeDownloads.contains(objectPath)) {
                return;
            }
            activeDownloads.add(objectPath);
        }

        executor.execute(() -> {
            try {
                File mediaDir = getMediaDir();
                if (mediaDir == null) {
                    notifyDownloadFailed(objectPath, "Storage not available");
                    return;
                }

                String sanitizedName = objectPath.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (sanitizedName.length() > 200) {
                    sanitizedName = sanitizedName.substring(sanitizedName.length() - 200);
                }
                File outputFile = new File(mediaDir, sanitizedName);

                File parentDir = outputFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                URL url = new URL(signedUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    conn.disconnect();
                    notifyDownloadFailed(objectPath, "HTTP " + responseCode);
                    return;
                }

                long contentLength = conn.getContentLength();

                if (contentLength > 0) {
                    long freeSpace = mediaDir.getFreeSpace();
                    if (freeSpace - contentLength < LOW_STORAGE_THRESHOLD) {
                        conn.disconnect();
                        notifyDownloadFailed(objectPath, "Insufficient storage");
                        return;
                    }
                }

                File tempFile = new File(mediaDir, sanitizedName + ".tmp");
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(tempFile);
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalRead = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }

                out.flush();
                out.close();
                in.close();
                conn.disconnect();

                if (outputFile.exists()) {
                    outputFile.delete();
                }
                if (!tempFile.renameTo(outputFile)) {
                    tempFile.delete();
                    notifyDownloadFailed(objectPath, "Failed to move file");
                    return;
                }

                addToManifest(objectPath, outputFile.getAbsolutePath(), totalRead);
                notifyDownloadComplete(objectPath, "file://" + outputFile.getAbsolutePath());

            } catch (Exception e) {
                notifyDownloadFailed(objectPath, e.getMessage());
            } finally {
                synchronized (activeDownloads) {
                    activeDownloads.remove(objectPath);
                }
            }
        });
    }

    public String getLocalMediaPath(String objectPath) {
        JSONObject manifest = getManifest();
        JSONObject entry = manifest.optJSONObject(objectPath);
        if (entry == null) return "";

        String localPath = entry.optString("localPath", "");
        if (localPath.isEmpty()) return "";

        File file = new File(localPath);
        if (!file.exists()) {
            removeFromManifest(objectPath);
            return "";
        }

        updateLastUsed(objectPath);
        return "file://" + localPath;
    }

    public boolean deleteMedia(String objectPath) {
        JSONObject manifest = getManifest();
        JSONObject entry = manifest.optJSONObject(objectPath);
        if (entry == null) return false;

        String localPath = entry.optString("localPath", "");
        if (!localPath.isEmpty()) {
            File file = new File(localPath);
            if (file.exists()) {
                file.delete();
            }
        }
        removeFromManifest(objectPath);
        return true;
    }

    public int deleteAllMedia() {
        JSONObject manifest = getManifest();
        int count = 0;
        Iterator<String> keys = manifest.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = manifest.optJSONObject(key);
            if (entry != null) {
                String localPath = entry.optString("localPath", "");
                if (!localPath.isEmpty()) {
                    File file = new File(localPath);
                    if (file.exists()) {
                        file.delete();
                        count++;
                    }
                }
            }
        }

        prefs.edit().putString(KEY_MANIFEST, "{}").apply();

        File mediaDir = getMediaDir();
        if (mediaDir != null && mediaDir.exists()) {
            File[] remaining = mediaDir.listFiles();
            if (remaining != null) {
                for (File f : remaining) {
                    f.delete();
                }
            }
        }

        return count;
    }

    public String getStorageInfo() {
        JSONObject info = new JSONObject();
        try {
            JSONObject manifest = getManifest();
            long usedBytes = 0;
            int totalFiles = 0;

            Iterator<String> keys = manifest.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entry = manifest.optJSONObject(key);
                if (entry != null) {
                    usedBytes += entry.optLong("size", 0);
                    totalFiles++;
                }
            }

            File mediaDir = getMediaDir();
            long freeBytes = mediaDir != null ? mediaDir.getFreeSpace() : 0;
            long totalSpace = mediaDir != null ? mediaDir.getTotalSpace() : 0;

            info.put("usedBytes", usedBytes);
            info.put("freeBytes", freeBytes);
            info.put("totalSpace", totalSpace);
            info.put("totalFiles", totalFiles);
        } catch (JSONException e) {
            try {
                info.put("usedBytes", 0);
                info.put("freeBytes", 0);
                info.put("totalSpace", 0);
                info.put("totalFiles", 0);
            } catch (JSONException ignored) {}
        }
        return info.toString();
    }

    public void cleanupOrphans() {
        File mediaDir = getMediaDir();
        if (mediaDir == null || !mediaDir.exists()) return;

        JSONObject manifest = getManifest();
        Set<String> knownPaths = new HashSet<>();
        Iterator<String> keys = manifest.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = manifest.optJSONObject(key);
            if (entry != null) {
                knownPaths.add(entry.optString("localPath", ""));
            }
        }

        File[] files = mediaDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.getName().endsWith(".tmp")) {
                file.delete();
                continue;
            }
            if (!knownPaths.contains(file.getAbsolutePath())) {
                file.delete();
            }
        }

        Iterator<String> manifestKeys = manifest.keys();
        Set<String> toRemove = new HashSet<>();
        while (manifestKeys.hasNext()) {
            String key = manifestKeys.next();
            JSONObject entry = manifest.optJSONObject(key);
            if (entry != null) {
                String localPath = entry.optString("localPath", "");
                if (!localPath.isEmpty() && !new File(localPath).exists()) {
                    toRemove.add(key);
                }
            }
        }
        for (String key : toRemove) {
            manifest.remove(key);
        }
        if (!toRemove.isEmpty()) {
            prefs.edit().putString(KEY_MANIFEST, manifest.toString()).apply();
        }
    }

    private File getMediaDir() {
        File dir = context.getExternalFilesDir("media");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "media");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private JSONObject getManifest() {
        try {
            String raw = prefs.getString(KEY_MANIFEST, "{}");
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void addToManifest(String objectPath, String localPath, long size) {
        JSONObject manifest = getManifest();
        try {
            JSONObject entry = new JSONObject();
            entry.put("localPath", localPath);
            entry.put("size", size);
            entry.put("downloadedAt", System.currentTimeMillis());
            entry.put("lastUsed", System.currentTimeMillis());
            manifest.put(objectPath, entry);
            prefs.edit().putString(KEY_MANIFEST, manifest.toString()).apply();
        } catch (JSONException ignored) {}
    }

    private void removeFromManifest(String objectPath) {
        JSONObject manifest = getManifest();
        manifest.remove(objectPath);
        prefs.edit().putString(KEY_MANIFEST, manifest.toString()).apply();
    }

    private void updateLastUsed(String objectPath) {
        JSONObject manifest = getManifest();
        JSONObject entry = manifest.optJSONObject(objectPath);
        if (entry != null) {
            try {
                entry.put("lastUsed", System.currentTimeMillis());
                manifest.put(objectPath, entry);
                prefs.edit().putString(KEY_MANIFEST, manifest.toString()).apply();
            } catch (JSONException ignored) {}
        }
    }

    private void notifyDownloadComplete(final String objectPath, final String localPath) {
        if (webView == null) return;
        mainHandler.post(() -> {
            String js = "javascript:if(window.__onMediaDownloaded){window.__onMediaDownloaded('"
                    + escapeJs(objectPath) + "','" + escapeJs(localPath) + "');}";
            webView.evaluateJavascript(js, null);
        });
    }

    private void notifyDownloadFailed(final String objectPath, final String error) {
        if (webView == null) return;
        mainHandler.post(() -> {
            String js = "javascript:if(window.__onMediaDownloadFailed){window.__onMediaDownloadFailed('"
                    + escapeJs(objectPath) + "','" + escapeJs(error != null ? error : "Unknown error") + "');}";
            webView.evaluateJavascript(js, null);
        });
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
