package com.digipal.signage;

  import android.content.Context;
  import android.os.Handler;
  import android.os.Looper;
  import android.util.Log;
  import android.webkit.WebView;
  import org.json.JSONArray;
  import org.json.JSONObject;
  import java.io.File;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.concurrent.ConcurrentHashMap;
  import java.util.concurrent.atomic.AtomicInteger;

  /**
   * PlaylistRepository — Atomic playlist revision activation pipeline.
   *
   * Pipeline states: DISCOVERED → DOWNLOADING → VERIFIED → READY → ACTIVE
   *   1. startRevisionPipeline() stores the incoming manifest as DISCOVERED.
   *   2. Media assets (VIDEO / IMAGE slides) are queued to MediaDownloadManager.
   *   3. When all assets are downloaded, files are verified (exists + size > 0).
   *   4. Verified manifest URLs are rewritten to file:// local paths → local_manifest.
   *   5. Revision is set READY and OnRevisionReady.onReady() is fired.
   *   6. Caller invokes promoteRevisionToActive() — atomic Room transaction:
   *      current ACTIVE → ROLLED_BACK, new revision → ACTIVE.
   *   7. After ROLLBACK_GRACE_MS a cleanup pass deletes ROLLED_BACK rows + local files.
   *
   * Failure path: any download or verification failure sets status=FAILED and fires
   * window.__digipalNativeMetrics(type='revisionFailed') into the WebView.
   */
  public class PlaylistRepository {

      private static final String TAG = "PlaylistRepo";
      private static final long ROLLBACK_GRACE_MS = 10L * 60 * 1000; // 10 min

      /** Callback fired when a revision completes the full download + verify pipeline. */
      public interface OnRevisionReady {
          void onReady(long revisionDbId, String localManifestJson);
          void onFailed(long revisionDbId, String reason);
      }

            /** Task #1891 fix: fired when a WEBVIEW_PDF asset finishes native page prerendering
         *  so PlaylistScheduler can reload the active revision and expand the slide without
         *  waiting for the next full playlist refresh or a reboot. */
        public interface PdfPrerenderReadyListener {
            void onPdfPrerenderReady(String assetId);
        }

        private final PlaylistDatabase.AppDatabase db;
      private final Handler mainHandler = new Handler(Looper.getMainLooper());
      private PdfPrerenderer pdfPrerenderer;
        private PdfPrerenderReadyListener pdfPrerenderReadyListener;

        /** Task #1891: wired from MainActivity after both collaborators are constructed. */
        public void setPdfPrerenderer(PdfPrerenderer p) { this.pdfPrerenderer = p; }

        /** Wired from PlaylistScheduler's constructor so it can reload the active
         *  revision from Room the moment a PDF's pages become ready. */
        public void setPdfPrerenderReadyListener(PdfPrerenderReadyListener l) { this.pdfPrerenderReadyListener = l; }

      // Per-revision pipeline state (keyed by Room row id)
      private final ConcurrentHashMap<Long, AtomicInteger> pendingDownloads  = new ConcurrentHashMap<>();
      private final ConcurrentHashMap<Long, AtomicInteger> failedDownloads   = new ConcurrentHashMap<>();
      private final ConcurrentHashMap<Long, ConcurrentHashMap<String, String>> assetLocalPaths = new ConcurrentHashMap<>();
      private final ConcurrentHashMap<Long, String>              revisionJsonCache = new ConcurrentHashMap<>();
      private final ConcurrentHashMap<Long, OnRevisionReady>     revisionCallbacks = new ConcurrentHashMap<>();

      // Optional collaborators set after construction
      private MediaDownloadManager mediaDownloadManager;
      private WebView webView;

      public PlaylistRepository(Context ctx) {
          this.db = PlaylistDatabase.getInstance(ctx);
      }

      public void setMediaDownloadManager(MediaDownloadManager mdm) { this.mediaDownloadManager = mdm; }
      public void setWebView(WebView wv) { this.webView = wv; }

      // ─────────────────────────────────────────────────────────────────────────
      // Public pipeline entry point
      // ─────────────────────────────────────────────────────────────────────────

      /**
       * Start the atomic activation pipeline for a new playlist JSON.
       * Returns immediately after the DISCOVERED insert; the OnRevisionReady
       * callback fires asynchronously once all downloads complete (or fails fast
       * when MediaDownloadManager is absent and no assets need downloading).
       */
      public long startRevisionPipeline(String playlistId, String json, OnRevisionReady callback) {
          // 1. Persist as DISCOVERED
          PlaylistDatabase.PlaylistRevisionEntity e = new PlaylistDatabase.PlaylistRevisionEntity();
          e.playlistId = playlistId;
          e.revisionId = String.valueOf(System.currentTimeMillis());
          e.json = json;
          e.status = "DISCOVERED";
          e.createdAt = System.currentTimeMillis();
          long revId = db.revisionDao().insert(e);

          // Persist slides for scheduler boot-restore (Room-indexed, not the rewritten manifest)
          saveSlidesFromJson(revId, json);

          // 2. Extract downloadable assets (VIDEO / IMAGE slides with http URLs)
          List<AssetDescriptor> assets = extractMediaAssets(json);

          if (assets.isEmpty() || mediaDownloadManager == null) {
              // No media to download — immediately READY
              db.revisionDao().setLocalManifest(revId, json);
              db.revisionDao().setStatus(revId, "READY");
              Log.i(TAG, "[pipeline] revId=" + revId + " no assets — immediately READY");
              callback.onReady(revId, json);
              return revId;
          }

          // 3. Register tracking state, set DOWNLOADING
          db.revisionDao().setStatus(revId, "DOWNLOADING");
          pendingDownloads.put(revId, new AtomicInteger(assets.size()));
          failedDownloads.put(revId, new AtomicInteger(0));
          assetLocalPaths.put(revId, new ConcurrentHashMap<>());
          revisionJsonCache.put(revId, json);
          revisionCallbacks.put(revId, callback);
          Log.i(TAG, "[pipeline] revId=" + revId + " DOWNLOADING " + assets.size() + " assets");

          // 4. Enqueue downloads
          for (AssetDescriptor asset : assets) {
              final String opKey = asset.objectPath;
              mediaDownloadManager.downloadMediaWithCallback(asset.objectPath, asset.signedUrl,
                  new MediaDownloadManager.DownloadCallback() {
                      @Override public void onSuccess(String objectPath, String localPath, long sz) {
                          handleAssetDownloaded(revId, opKey, localPath);
                      }
                      @Override public void onFailure(String objectPath, String error) {
                          handleAssetFailed(revId, opKey, error);
                      }
                  });
          }
          return revId;
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Internal pipeline callbacks (called from download threads)
      // ─────────────────────────────────────────────────────────────────────────

      private void handleAssetDownloaded(long revId, String objectPath, String localPath) {
            ConcurrentHashMap<String, String> paths = assetLocalPaths.get(revId);
            if (paths == null) return; // revision was cancelled / already finalised
            paths.put(objectPath, localPath);

            // Task #1891: kick off native PDF-to-JPEG prerendering as soon as a PDF asset
            // finishes downloading. Runs on PdfPrerenderer's own executor and writes page
            // paths to Room asynchronously -- does NOT block pipeline finalization, so the
            // very first activation of a brand-new PDF still falls back to the WEBVIEW_PDF
            // viewer; subsequent activations pick up the prerendered pages via Room.
            if (objectPath.endsWith("_pdf") && pdfPrerenderer != null) {
                PlaylistDatabase.AssetEntity existing = db.assetDao().findById(objectPath);
                if (existing == null || existing.prerenderedPages == null || existing.prerenderedPages.isEmpty()) {
                    pdfPrerenderer.prerender(objectPath, localPath, new PdfPrerenderer.Callback() {
                        @Override public void onPagesReady(String assetId, List<String> pageLocalPaths) {
                            Log.i(TAG, "[pdf-prerender] " + assetId + " ready, " + pageLocalPaths.size() + " pages");
                              if (pdfPrerenderReadyListener != null) {
                                  pdfPrerenderReadyListener.onPdfPrerenderReady(assetId);
                              }
                        }
                        @Override public void onFailed(String assetId, String error) {
                            Log.w(TAG, "[pdf-prerender] " + assetId + " failed: " + error + " -- keeping WEBVIEW_PDF fallback");
                        }
                    });
                }
            }

            AtomicInteger pending = pendingDownloads.get(revId);
            if (pending == null) return;
            int remaining = pending.decrementAndGet();
            Log.d(TAG, "[pipeline] revId=" + revId + " asset OK key=" + objectPath + " remaining=" + remaining);
            if (remaining <= 0) finalizePipeline(revId);
        }

      private void handleAssetFailed(long revId, String objectPath, String error) {
          AtomicInteger failed = failedDownloads.get(revId);
          if (failed != null) failed.incrementAndGet();

          AtomicInteger pending = pendingDownloads.get(revId);
          if (pending == null) return;
          int remaining = pending.decrementAndGet();
          Log.w(TAG, "[pipeline] revId=" + revId + " asset FAILED key=" + objectPath
                  + " err=" + error + " remaining=" + remaining);
          if (remaining <= 0) finalizePipeline(revId);
      }

      private void finalizePipeline(long revId) {
          // Drain tracking state atomically
          String origJson        = revisionJsonCache.remove(revId);
          ConcurrentHashMap<String, String> paths = assetLocalPaths.remove(revId);
          OnRevisionReady cb     = revisionCallbacks.remove(revId);
          AtomicInteger failed   = failedDownloads.remove(revId);
          pendingDownloads.remove(revId);

          if (origJson == null || paths == null || cb == null) return;
          int failCount = failed != null ? failed.get() : 0;

          // 5. Verify every downloaded file exists and is non-empty
          List<String> verifyErrors = new ArrayList<>();
          for (java.util.Map.Entry<String, String> entry : paths.entrySet()) {
              String localPath = entry.getValue();
              if (localPath == null || localPath.isEmpty()) {
                  verifyErrors.add(entry.getKey() + ": empty path");
                  continue;
              }
              String filePath = localPath.startsWith("file://") ? localPath.substring(7) : localPath;
              File f = new File(filePath);
              if (!f.exists())     verifyErrors.add(entry.getKey() + ": file missing");
              else if (f.length() == 0) verifyErrors.add(entry.getKey() + ": zero bytes");
          }

          if (!verifyErrors.isEmpty()) {
              String reason = "verify: " + verifyErrors.get(0)
                      + (verifyErrors.size() > 1 ? " (+" + (verifyErrors.size()-1) + " more)" : "");
              Log.e(TAG, "[pipeline] revId=" + revId + " FAILED " + reason);
              markRevisionFailed(revId, reason);
              cb.onFailed(revId, reason);
              return;
          }

          // 6. Rewrite manifest URLs → file:// local paths
          String localManifest = rewriteManifestUrls(origJson, paths);
          db.revisionDao().setLocalManifest(revId, localManifest);
          db.revisionDao().setStatus(revId, "READY");
          Log.i(TAG, "[pipeline] revId=" + revId + " READY failCount=" + failCount);

          // 7. Fire callback
          cb.onReady(revId, localManifest);
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Atomic activation (Room transaction)
      // ─────────────────────────────────────────────────────────────────────────

      /**
       * Atomically promote a READY revision to ACTIVE, rolling back the current
       * ACTIVE revision for a grace-period before cleanup.
       * Safe to call from any thread.
       */
      public void promoteRevisionToActive(long revisionDbId) {
          db.runInTransaction(() -> {
              db.revisionDao().markActiveAsRolledBack();
              db.revisionDao().activate(revisionDbId, System.currentTimeMillis());
          });
          // Prune legacy SUPERSEDED rows older than 7 days
          db.revisionDao().pruneOld(System.currentTimeMillis() - 7L * 86400 * 1000);
          Log.i(TAG, "[pipeline] promoted revisionDbId=" + revisionDbId + " → ACTIVE");

          // Schedule ROLLED_BACK cleanup after grace period
          mainHandler.postDelayed(this::cleanupRolledBackRevisions, ROLLBACK_GRACE_MS);
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Failure path
      // ─────────────────────────────────────────────────────────────────────────

      private void markRevisionFailed(long revId, String reason) {
          db.revisionDao().setStatus(revId, "FAILED");
          fireMetricsEvent("revisionFailed",
                "{\"revisionDbId\":" + revId + ",\"reason\":\"" + escapeJson(reason) + "\"}");
      }

      private void fireMetricsEvent(String type, String detailJson) {
          if (webView == null) return;
          final String js = "javascript:(function(){try{"
                  + "window.dispatchEvent(new CustomEvent('__digipalNativeMetrics',"
                  + "{detail:{type:'" + escapeJs(type) + "',data:" + detailJson + "}}));"
                  + "}catch(e){}})();";
          mainHandler.post(() -> webView.evaluateJavascript(js, null));
      }

      // ─────────────────────────────────────────────────────────────────────────
      // ROLLED_BACK cleanup (runs after grace period)
      // ─────────────────────────────────────────────────────────────────────────

      /** Delete ROLLED_BACK revisions and their local media files after the grace period. */
      public void cleanupRolledBackRevisions() {
          List<PlaylistDatabase.PlaylistRevisionEntity> old =
                  db.revisionDao().getRolledBackBefore(System.currentTimeMillis() - ROLLBACK_GRACE_MS);
          for (PlaylistDatabase.PlaylistRevisionEntity rev : old) {
              db.slideDao().deleteForRevision(rev.id);
              if (rev.localManifest != null && !rev.localManifest.isEmpty()) {
                  deleteLocalMediaFiles(rev.localManifest);
              }
              db.revisionDao().deleteById(rev.id);
              Log.d(TAG, "[cleanup] deleted ROLLED_BACK revId=" + rev.id);
          }
          if (!old.isEmpty()) {
              Log.i(TAG, "[cleanup] removed " + old.size() + " ROLLED_BACK revision(s)");
          }
      }

      private void deleteLocalMediaFiles(String localManifestJson) {
          try {
              JSONArray arr = new JSONArray(localManifestJson);
              for (int i = 0; i < arr.length(); i++) {
                  JSONObject obj = arr.optJSONObject(i);
                  if (obj == null) continue;
                  String url = obj.optString("url", "");
                  if (url.startsWith("file://")) {
                      File f = new File(url.substring(7));
                      if (f.exists()) { f.delete(); }
                  }
              }
          } catch (Exception ignored) {}
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Helpers
      // ─────────────────────────────────────────────────────────────────────────

      /** Extract VIDEO/IMAGE slides with http asset URLs from a playlist JSON array. */
      private List<AssetDescriptor> extractMediaAssets(String json) {
          List<AssetDescriptor> result = new ArrayList<>();
          try {
              JSONArray arr = new JSONArray(json);
              for (int i = 0; i < arr.length(); i++) {
                  JSONObject obj = arr.getJSONObject(i);
                  String type = obj.optString("type", "");
                  boolean isPdf = "WEBVIEW_PDF".equals(type);
                  if (!"VIDEO".equals(type) && !"IMAGE".equals(type) && !isPdf) continue;
                  String url = obj.optString("url", "");
                  if (url.isEmpty() || !url.startsWith("http")) continue;
                  // Stable key: contentId + type (survives signed-URL rotation).
                  // PDFs use a fixed "_pdf" suffix (task #1891) so PlaylistScheduler's
                  // expandPdfIfPrerendered() can compute the same key independently.
                  String contentId = obj.optString("contentId", String.valueOf(i));
                  String suffix = isPdf ? "pdf" : type.toLowerCase();
                  String objectPath = "native_asset_" + contentId + "_" + suffix;
                  result.add(new AssetDescriptor(objectPath, url));
              }
          } catch (Exception ex) {
              Log.e(TAG, "extractMediaAssets: " + ex.getMessage());
          }
          return result;
      }

      /**
       * Rewrite url fields in playlist JSON slides to their local file:// equivalents.
       * Adds a boolean isLocal=true flag so the scheduler knows the URL is already cached.
       */
      private String rewriteManifestUrls(String json, ConcurrentHashMap<String, String> paths) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String type = obj.optString("type", "");
                    boolean isPdf = "WEBVIEW_PDF".equals(type);
                    if (!"VIDEO".equals(type) && !"IMAGE".equals(type) && !isPdf) continue;
                    String contentId = obj.optString("contentId", String.valueOf(i));
                    // PDFs are downloaded under a fixed "_pdf" suffix key (task #1891, mirrors
                    // extractMediaAssets()) so the isolated-WebView PDF fallback still opens the
                    // locally-cached file (task P4) instead of a possibly-expired/offline-
                    // unreachable remote signed URL when native prerendering hasn't completed yet.
                    String objectPath = "native_asset_" + contentId + "_" + (isPdf ? "pdf" : type.toLowerCase());
                    String localPath = paths.get(objectPath);
                    if (localPath != null && !localPath.isEmpty()) {
                        obj.put("url", localPath);
                        obj.put("localUrl", localPath);
                        obj.put("isLocal", true);
                    }
                }
                return arr.toString();
            } catch (Exception ex) {
                Log.e(TAG, "rewriteManifestUrls: " + ex.getMessage());
                return json;
            }
        }

      private static String escapeJson(String s) {
          if (s == null) return "";
          return s.replace("\\", "\\\\").replace("\"", "\\\"")
                  .replace("\n", "\\n").replace("\r", "\\r");
      }

      private static String escapeJs(String s) {
          if (s == null) return "";
          return s.replace("'", "\\'").replace("\n", "\\n");
      }

      private static class AssetDescriptor {
          final String objectPath;
          final String signedUrl;
          AssetDescriptor(String op, String su) { objectPath = op; signedUrl = su; }
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Existing public API (preserved for backward compat)
      // ─────────────────────────────────────────────────────────────────────────

      /** Legacy save — status=READY immediately, no asset pipeline. */
      public long saveRevision(String playlistId, String revisionId, String json) {
          PlaylistDatabase.PlaylistRevisionEntity e = new PlaylistDatabase.PlaylistRevisionEntity();
          e.playlistId = playlistId;
          e.revisionId = revisionId;
          e.json = json;
          e.status = "READY";
          e.createdAt = System.currentTimeMillis();
          long id = db.revisionDao().insert(e);
          Log.d(TAG, "saveRevision id=" + id + " rev=" + revisionId);
          return id;
      }

      /** Legacy activation — delegates to promoteRevisionToActive() for atomicity. */
      public void activateRevision(long revisionId) {
          promoteRevisionToActive(revisionId);
      }

      public PlaylistDatabase.PlaylistRevisionEntity getActive() {
          return db.revisionDao().getActive();
      }

      public PlaylistDatabase.PlaylistRevisionEntity getLastKnownGood() {
          List<PlaylistDatabase.PlaylistRevisionEntity> list = db.revisionDao().getLastTwo();
          return list.isEmpty() ? null : list.get(0);
      }

      public void clearActiveRevision() {
          db.revisionDao().markActiveAsRolledBack();
          Log.i(TAG, "clearActiveRevision: active revision cleared");
      }

      public void saveSlides(long revisionId, List<PlaylistDatabase.SlideEntity> slides) {
          db.slideDao().deleteForRevision(revisionId);
          db.slideDao().insertAll(slides);
      }

      public List<PlaylistDatabase.SlideEntity> getSlidesForRevision(long revisionId) {
          return db.slideDao().forRevision(revisionId);
      }

      private static String normalizeSlideType(String rawType) {
            if (rawType == null || rawType.trim().isEmpty()) return "WEBVIEW_URL";
            String t = rawType.trim();
            if ("image_url".equalsIgnoreCase(t) || "IMAGE".equalsIgnoreCase(t)) return "IMAGE";
            if ("video".equalsIgnoreCase(t) || "VIDEO".equalsIgnoreCase(t)) return "VIDEO";
            try {
                PlaylistScheduler.SlideType.valueOf(t);
                return t;
            } catch (IllegalArgumentException ignored) {
                return "WEBVIEW_URL";
            }
        }

        public void saveSlidesFromJson(long revisionId, String json) {
            try {
                JSONArray arr = new JSONArray(json);
                List<PlaylistDatabase.SlideEntity> entities = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    PlaylistDatabase.SlideEntity s = new PlaylistDatabase.SlideEntity();
                    s.revisionId = revisionId;
                    s.slideId = obj.optString("slideId", String.valueOf(obj.optInt("contentId", i)));
                    s.type = normalizeSlideType(obj.optString("type", "WEBVIEW_URL"));
                    s.durationMs = (long)(obj.optDouble("duration", 10) * 1000);
                    s.orderIndex = i;
                    s.configJson = obj.toString();
                    entities.add(s);
                }
                saveSlides(revisionId, entities);
                Log.d(TAG, "Saved " + entities.size() + " slides for revisionId=" + revisionId);
            } catch (Exception ex) {
                Log.e(TAG, "saveSlidesFromJson error: " + ex.getMessage());
            }
        }

      public PlaylistDatabase.AssetEntity getAsset(String assetId) {
          return db.assetDao().findById(assetId);
      }

      public void markAssetReady(String assetId, String localPath, String sha256, String etag, String lastMod, long size) {
          db.assetDao().markReady(assetId, "READY", localPath, sha256, etag, lastMod, size);
      }

      public void markAssetFailed(String assetId, String error) {
          db.assetDao().markFailed(assetId, "FAILED", error);
      }

      public void pinAssetForRollback(String assetId, long untilMs) {
          db.assetDao().setPinnedUntil(assetId, untilMs);
      }

      public PlaylistDatabase.AppDatabase getDb() { return db; }
  }
  