package com.digipal.signage;

  import android.content.Context;
  import android.content.SharedPreferences;
  import android.os.Handler;
  import android.os.Looper;
  import android.webkit.WebView;

  import java.util.Locale;

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
          this.executor = Executors.newFixedThreadPool(2); // Fix 11: 2 threads avoid bandwidth saturation during ExoPlayer streaming
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
          // Task P6: activeDownloads must be released on EVERY exit path, not just the
          // final-retry-exhausted path below -- the early returns for missing storage dir,
          // non-200 responses, insufficient disk space, and file-move failure all used to
          // return directly from this method without going through the removal at the end,
          // permanently leaking objectPath in this in-memory Set for the rest of the process
          // lifetime (a 24/7 kiosk player never restarts, so this leaked without bound).
          try {

          final int MAX_ATTEMPTS = 3;
          String lastError = "Unknown error";
          for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
              if (attempt > 1) {
                  // Exponential backoff with jitter: 2s, 4s, 8s + random 0-1s, capped at 30s (Fix 1).
                  long backoffMs = Math.min(30_000L, (long)(Math.pow(2, attempt - 1) * 2_000L));
                  long jitterMs  = (long)(Math.random() * 1_000L);
                  android.util.Log.d("MediaDownload", "[performDownload] retry attempt=" + attempt
                          + " backoff=" + backoffMs + "ms jitter=" + jitterMs + "ms obj=" + objectPath);
                  try { Thread.sleep(backoffMs + jitterMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
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
              File outputFile = SafeFiles.child(mediaDir, sanitizedName);

                File parentDir = outputFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

                URL url = new URL(signedUrl);
                String scheme = url.getProtocol();
                if (!"https".equalsIgnoreCase(scheme)
                        && !("http".equalsIgnoreCase(scheme) && UrlPolicy.isPrivateHost(url.getHost()))) {
                    notifyDownloadFailed(objectPath, "Blocked non-trusted media URL");
                    return;
                }
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

              File tempFile = SafeFiles.child(mediaDir, sanitizedName + ".tmp");
              long totalRead = 0;
              // Task P6: try-with-resources guarantees in/out are closed even if read()/write()
              // throws mid-transfer (e.g. connection reset) -- the previous plain close() calls
              // right after the loop were never reached on that path, leaking the socket's
              // InputStream and an open FileOutputStream/file descriptor on every failed transfer.
              try (InputStream in = conn.getInputStream();
                   FileOutputStream out = new FileOutputStream(tempFile)) {
                  byte[] buffer = new byte[BUFFER_SIZE];
                  int bytesRead;
                  while ((bytesRead = in.read(buffer)) != -1) {
                      out.write(buffer, 0, bytesRead);
                      totalRead += bytesRead;
                  }
                  out.flush();
              } finally {
                  conn.disconnect();
              }

              if (outputFile.exists()) outputFile.delete();
              if (!tempFile.renameTo(outputFile)) {
                  tempFile.delete();
                  notifyDownloadFailed(objectPath, "Failed to move file");
                  return;
              }

              // Fix 3: integrity check — verify file is non-empty and size approximates Content-Length.
              if (totalRead == 0 || !outputFile.exists() || outputFile.length() == 0) {
                  outputFile.delete();
                  lastError = "Zero-byte file after download";
                  android.util.Log.w("MediaDownload", "[integrity] zero-byte file: " + objectPath);
                  continue; // retry
              }
              if (contentLength > 0) {
                  double ratio = (double) outputFile.length() / contentLength;
                  if (ratio < 0.95 || ratio > 1.05) {
                      outputFile.delete();
                      lastError = "File size mismatch: got " + outputFile.length() + " expected " + contentLength;
                      android.util.Log.w("MediaDownload", "[integrity] size mismatch: " + lastError + " obj=" + objectPath);
                      continue; // retry
                  }
              }
              addToManifest(objectPath, outputFile.getAbsolutePath(), totalRead);
              notifyDownloadComplete(objectPath, "file://" + outputFile.getAbsolutePath());

              } catch (Exception e) {
                  lastError = e.getMessage() != null ? e.getMessage() : "IOException";
                  // Retry on network errors; continue for loop to next attempt
                  continue;
              }
              return; // success — exit retry loop
          } // end retry loop
          // All attempts exhausted
          notifyDownloadFailed(objectPath, lastError);
      
          } finally {
              synchronized (activeDownloads) { activeDownloads.remove(objectPath); }
          }
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

          File file = SafeFiles.existingFileInsideOrNull(getMediaDir(), localPath);
            if (file == null) {
                removeFromManifest(objectPath);
                return "";
            }

            updateLastUsed(objectPath);
              return "file://" + file.getAbsolutePath();
          }

        /** Unified Design Studio Renderer stabilization (Step 3): returns a web-servable
         *  URL for locally-cached media, safe to embed directly in an isolated-renderer
         *  WebView's <img>/<video> src. Raw file:// URLs are unreliable from an https://
         *  origin WebView even with universal-file-access flags enabled (can be blocked by
         *  OEM WebView builds), so this returns a same-origin-safe virtual URL under
         *  https://appassets.androidplatform.net/media/<encoded objectPath> instead, which
         *  IsolatedWebRenderer's shouldInterceptRequest() resolves back to the cached file
         *  via resolveLocalMediaFile() below. Returns "" if objectPath is not cached yet --
         *  callers (window.DigipalMedia.getLocalMediaWebUrl in the JS bridge) should fall
         *  back to the original signed URL and/or trigger downloadMedia() in that case. */
        public String getLocalMediaWebUrl(String objectPath) {
            JSONObject manifest = getManifest();
            JSONObject entry = manifest.optJSONObject(objectPath);
            if (entry == null) return "";

            String localPath = entry.optString("localPath", "");
            if (localPath.isEmpty()) return "";

            File file = SafeFiles.existingFileInsideOrNull(getMediaDir(), localPath);
              if (file == null) {
                  removeFromManifest(objectPath);
                  return "";
              }

              updateLastUsed(objectPath);
            try {
                String encoded = java.net.URLEncoder.encode(objectPath, "UTF-8");
                return "https://appassets.androidplatform.net/media/" + encoded;
            } catch (Exception e) {
                return "";
            }
        }

        /** Resolves a virtual https://appassets.androidplatform.net/media/<encoded
         *  objectPath> request (see getLocalMediaWebUrl() above) back to the locally
         *  cached File for that objectPath. Only ever serves a path that is present in the
         *  manifest and still exists on disk -- never serves an arbitrary filesystem path
         *  derived directly from the request. Returns null on any mismatch. */
        public File resolveLocalMediaFile(String virtualPath) {
            if (virtualPath == null || !virtualPath.startsWith("/media/")) return null;

            String objectPath;
            try {
                objectPath = java.net.URLDecoder.decode(virtualPath.substring("/media/".length()), "UTF-8");
            } catch (Exception e) {
                return null;
            }

            JSONObject manifest = getManifest();
            JSONObject entry = manifest.optJSONObject(objectPath);
            if (entry == null) return null;

            String localPath = entry.optString("localPath", "");
            if (localPath.isEmpty()) return null;

            File file = SafeFiles.existingFileInsideOrNull(getMediaDir(), localPath);
            if (file == null) return null;
            return file;
        }

        /** Best-effort MIME type for a cached media file, used when serving it through
         *  resolveLocalMediaFile()'s virtual URL from IsolatedWebRenderer. */
        public static String guessMimeType(File file) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".png")) return "image/png";
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
            if (name.endsWith(".gif")) return "image/gif";
            if (name.endsWith(".webp")) return "image/webp";
            if (name.endsWith(".svg")) return "image/svg+xml";
            if (name.endsWith(".mp4")) return "video/mp4";
            if (name.endsWith(".webm")) return "video/webm";
            if (name.endsWith(".mov")) return "video/quicktime";
            if (name.endsWith(".pdf")) return "application/pdf";
            if (name.endsWith(".mp3")) return "audio/mpeg";
            if (name.endsWith(".wav")) return "audio/wav";
            if (name.endsWith(".ogg")) return "audio/ogg";
            return "application/octet-stream";
        }

        public boolean deleteMedia(String objectPath) {
          JSONObject manifest = getManifest();
          JSONObject entry = manifest.optJSONObject(objectPath);
          if (entry == null) return false;

          String localPath = entry.optString("localPath", "");
          if (!localPath.isEmpty()) {
              SafeFiles.deleteFileInside(getMediaDir(), localPath);
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
                  if (!localPath.isEmpty() && SafeFiles.deleteFileInside(getMediaDir(), localPath)) count++;
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
                  if (!localPath.isEmpty() && SafeFiles.existingFileInsideOrNull(mediaDir, localPath) == null) toRemove.add(key);
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
              String js = "if(window.__onMediaDownloaded){window.__onMediaDownloaded("
                      + JSONObject.quote(objectPath) + "," + JSONObject.quote(localPath) + ");}";
              webView.evaluateJavascript(js, null);
          });
      }

      private void notifyDownloadFailed(final String objectPath, final String error) {
          // Fire Java-side DownloadCallback waiters synchronously on download thread
          flushCallbacksFailure(objectPath, error != null ? error : "Unknown error");
          // Fire legacy WebView JS notification
          if (webView == null) return;
          mainHandler.post(() -> {
              String safeError = error != null ? error : "Unknown error";
              String js = "if(window.__onMediaDownloadFailed){window.__onMediaDownloadFailed("
                      + JSONObject.quote(objectPath) + "," + JSONObject.quote(safeError) + ");}";
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
  