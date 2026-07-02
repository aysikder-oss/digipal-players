package com.digipal.signage;

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
  import java.util.ArrayList;
  import java.util.HashSet;
  import java.util.Iterator;
  import java.util.List;
  import java.util.Set;
  import java.util.concurrent.ConcurrentHashMap;
  import java.util.concurrent.ExecutorService;
  import java.util.concurrent.Executors;

  public class MediaDownloadManager {

      private static final String MANIFEST_PREFS = "DigipalMediaManifest";
      private static final String KEY_MANIFEST = "manifest";
      private static final int BUFFER_SIZE = 8192;
      private static final long LOW_STORAGE_THRESHOLD = 100 * 1024 * 1024L;

      /** Java-side callback for per-download completion used by the atomic revision pipeline. */
      public interface DownloadCallback {
          void onSuccess(String objectPath, String localPath, long sizeBytes);
          void onFailure(String objectPath, String error);
      }

      private final Context context;
      private final SharedPreferences prefs;
      private final ExecutorService executor;
      private final Handler mainHandler;
      private final Set<String> activeDownloads;
      private final ConcurrentHashMap<String, List<DownloadCallback>> pendingCallbacks;
      private WebView webView;
      /** Last time updateLastUsed() flushed to SharedPreferences. Throttled to once per 60 s to avoid
       *  writing the full manifest JSON on every cache hit (Fix 2). */
      private long lastManifestWriteMs = 0L;

      public MediaDownloadManager(Context context) {
          this.context = context;
          this.prefs = context.getSharedPreferences(MANIFEST_PREFS, Context.MODE_PRIVATE);
          this.executor = Executors.newFixedThreadPool(4);
          this.mainHandler = new Handler(Looper.getMainLooper());
          this.activeDownloads = new HashSet<>();
          this.pendingCallbacks = new ConcurrentHashMap<>();
      }

      public void setWebView(WebView webView) {
          this.webView = webView;
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Public download API
      // ─────────────────────────────────────────────────────────────────────────

      /** Legacy entry point — no Java callback, notifies only via WebView JS. */
      public void downloadMedia(final String objectPath, final String signedUrl) {
          downloadMediaWithCallback(objectPath, signedUrl, null);
      }

      /**
       * Download an asset and notify {@code callback} on a background thread when done.
       * <ul>
       *   <li>Fast path: if the asset is already in the local manifest the callback fires
       *       immediately without queuing a new download.</li>
       *   <li>Dedup path: if the same {@code objectPath} is already downloading, the callback
       *       is queued and fired when the in-flight download completes.</li>
       *   <li>Normal path: starts a new download; on completion fires queued callbacks and
       *       the legacy WebView JS notification.</li>
       * </ul>
       */
      public void downloadMediaWithCallback(final String objectPath, final String signedUrl,
                                            final DownloadCallback callback) {
          // Fast path: already cached locally
          String existing = getLocalMediaPath(objectPath);
          if (!existing.isEmpty()) {
              if (callback != null) {
                  JSONObject entry = getManifest().optJSONObject(objectPath);
                  long size = entry != null ? entry.optLong("size", 0) : 0;
                  callback.onSuccess(objectPath, existing, size);
              }
              return;
          }

          // Queue callback; if another download is already in flight just wait.
          boolean startDownload;
          synchronized (pendingCallbacks) {
              List<DownloadCallback> waiters = pendingCallbacks.get(objectPath);
              if (waiters == null) {
                  waiters = new ArrayList<>();
                  pendingCallbacks.put(objectPath, waiters);
                  startDownload = true;
              } else {
                  startDownload = false;
              }
              if (callback != null) waiters.add(callback);
          }

          if (!startDownload) return; // another download will flush our callback

          synchronized (activeDownloads) { activeDownloads.add(objectPath); }

          executor.execute(() -> performDownload(objectPath, signedUrl));
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Core download worker
      // ─────────────────────────────────────────────────────────────────────────

      private void performDownload(String objectPath, String signedUrl) {
          final int MAX_ATTEMPTS = 3;
          String lastError = "Unknown error";
          for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
              if (attempt > 1) {
                  try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
              }
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
              if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

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
              // GCS signed URLs sometimes return -1 for Content-Length; in that case
              // assume up to 100 MB and check only that much headroom is available (Fix 3).
              long reserveBytes = contentLength > 0 ? contentLength : 100 * 1024 * 1024L;
              {
                  long freeSpace = mediaDir.getFreeSpace();
                  if (freeSpace - reserveBytes < LOW_STORAGE_THRESHOLD) {
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

              if (outputFile.exists()) outputFile.delete();
              if (!tempFile.renameTo(outputFile)) {
                  tempFile.delete();
                  notifyDownloadFailed(objectPath, "Failed to move file");
                  return;
              }

              addToManifest(objectPath, outputFile.getAbsolutePath(), totalRead);
              notifyDownloadComplete(objectPath, "file://" + outputFile.getAbsolutePath());

              } catch (Exception e) {
                  lastError = e.getMessage() != null ? e.getMessage() : "IOException";
                  // Retry on network errors; continue for loop to next attempt
                  continue;
              } finally {
                  // activeDownloads cleaned up only on final exit, not mid-retry
              }
              return; // success — exit retry loop
          } // end retry loop
          // All attempts exhausted
          notifyDownloadFailed(objectPath, lastError);
          synchronized (activeDownloads) { activeDownloads.remove(objectPath); }
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Existing public helpers (unchanged)
      // ─────────────────────────────────────────────────────────────────────────

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
              if (file.exists()) file.delete();
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
                      if (file.exists()) { file.delete(); count++; }
                  }
              }
          }

          prefs.edit().putString(KEY_MANIFEST, "{}").apply();

          File mediaDir = getMediaDir();
          if (mediaDir != null && mediaDir.exists()) {
              File[] remaining = mediaDir.listFiles();
              if (remaining != null) { for (File f : remaining) f.delete(); }
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
                  if (entry != null) { usedBytes += entry.optLong("size", 0); totalFiles++; }
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
                  info.put("usedBytes", 0); info.put("freeBytes", 0);
                  info.put("totalSpace", 0); info.put("totalFiles", 0);
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
              if (entry != null) knownPaths.add(entry.optString("localPath", ""));
          }

          File[] files = mediaDir.listFiles();
          if (files == null) return;

          for (File file : files) {
              if (file.getName().endsWith(".tmp")) { file.delete(); continue; }
              if (!knownPaths.contains(file.getAbsolutePath())) file.delete();
          }

          Iterator<String> manifestKeys = manifest.keys();
          Set<String> toRemove = new HashSet<>();
          while (manifestKeys.hasNext()) {
              String key = manifestKeys.next();
              JSONObject entry = manifest.optJSONObject(key);
              if (entry != null) {
                  String localPath = entry.optString("localPath", "");
                  if (!localPath.isEmpty() && !new File(localPath).exists()) toRemove.add(key);
              }
          }
          for (String key : toRemove) manifest.remove(key);
          if (!toRemove.isEmpty()) prefs.edit().putString(KEY_MANIFEST, manifest.toString()).apply();
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Private helpers
      // ─────────────────────────────────────────────────────────────────────────

      private File getMediaDir() {
          File dir = context.getExternalFilesDir("media");
          if (dir == null) dir = new File(context.getFilesDir(), "media");
          if (!dir.exists()) dir.mkdirs();
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
          long now = System.currentTimeMillis();
          // Throttle: only flush manifest to SharedPreferences once per 60 s to avoid
          // rewriting the entire JSON blob on every cache-hit (Fix 2).
          if (now - lastManifestWriteMs < 60_000L) return;
          JSONObject manifest = getManifest();
          JSONObject entry = manifest.optJSONObject(objectPath);
          if (entry != null) {
              try {
                  entry.put("lastUsed", now);
                  manifest.put(objectPath, entry);
                  prefs.edit().putString(KEY_MANIFEST, manifest.toString()).apply();
                  lastManifestWriteMs = now;
              } catch (JSONException ignored) {}
          }
      }

      private void notifyDownloadComplete(final String objectPath, final String localPath) {
          // Fire Java-side DownloadCallback waiters synchronously on download thread
          flushCallbacksSuccess(objectPath, localPath);
          // Fire legacy WebView JS notification
          if (webView == null) return;
          mainHandler.post(() -> {
              String js = "javascript:if(window.__onMediaDownloaded){window.__onMediaDownloaded('"
                      + escapeJs(objectPath) + "','" + escapeJs(localPath) + "');}";
              webView.evaluateJavascript(js, null);
          });
      }

      private void notifyDownloadFailed(final String objectPath, final String error) {
          // Fire Java-side DownloadCallback waiters synchronously on download thread
          flushCallbacksFailure(objectPath, error != null ? error : "Unknown error");
          // Fire legacy WebView JS notification
          if (webView == null) return;
          mainHandler.post(() -> {
              String js = "javascript:if(window.__onMediaDownloadFailed){window.__onMediaDownloadFailed('"
                      + escapeJs(objectPath) + "','" + escapeJs(error != null ? error : "Unknown error") + "');}";
              webView.evaluateJavascript(js, null);
          });
      }

      private void flushCallbacksSuccess(String objectPath, String localPath) {
          List<DownloadCallback> waiters;
          synchronized (pendingCallbacks) { waiters = pendingCallbacks.remove(objectPath); }
          if (waiters == null) return;
          JSONObject manifest = getManifest();
          JSONObject entry = manifest.optJSONObject(objectPath);
          long size = entry != null ? entry.optLong("size", 0) : 0;
          for (DownloadCallback cb : waiters) {
              try { cb.onSuccess(objectPath, localPath, size); } catch (Exception ignored) {}
          }
      }

      private void flushCallbacksFailure(String objectPath, String error) {
          List<DownloadCallback> waiters;
          synchronized (pendingCallbacks) { waiters = pendingCallbacks.remove(objectPath); }
          if (waiters == null) return;
          for (DownloadCallback cb : waiters) {
              try { cb.onFailure(objectPath, error); } catch (Exception ignored) {}
          }
      }

      /** Release executor resources. Call from MainActivity.onDestroy(). */
      public void shutdown() {
          try { executor.shutdownNow(); } catch (Throwable ignored) {}
      }

      private String escapeJs(String s) {
          if (s == null) return "";
          return s.replace("\\", "\\\\")
                  .replace("'", "\\'")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
      }
  }
  