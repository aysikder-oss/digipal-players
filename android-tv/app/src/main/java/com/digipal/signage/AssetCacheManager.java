package com.digipal.signage;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import okhttp3.*;

/**
 * AssetCacheManager — OkHttp-based media downloader with SHA256 verification,
 * ETag/Last-Modified conditional re-fetch, Range-request resume, and atomic rename.
 *
 * Key rules:
 *   - Download to .tmp, verify SHA256, rename atomically → no partial files served
 *   - On re-sync, send If-None-Match / If-Modified-Since → skip unchanged assets
 *   - Send Range: bytes=N- header if .tmp partial file exists → resume interrupted download
 *   - Never delete assets pinned for rollback or used by ACTIVE revision
 */
public class AssetCacheManager {

    private static final String TAG = "AssetCacheManager";
    private static final int MAX_CONCURRENT = 3;
    private static final long MIN_FREE_BYTES = 100 * 1024 * 1024L; // 100 MB

    private final Context ctx;
    private final PlaylistRepository repo;
    private final OkHttpClient http;
    private volatile int configuredMax = MAX_CONCURRENT;
    private final Semaphore sem = new Semaphore(MAX_CONCURRENT, true);
    private final ExecutorService exec = Executors.newFixedThreadPool(MAX_CONCURRENT + 1);

    public AssetCacheManager(Context ctx, PlaylistRepository repo) {
        this.ctx = ctx;
        this.repo = repo;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    }

    /** Returns the local File for an assetId if it's READY, else null. */
    public File getLocalFile(String assetId) {
        PlaylistDatabase.AssetEntity e = repo.getAsset(assetId);
        if (e == null || e.localPath == null || e.localPath.isEmpty()) return null;
        if (!"READY".equals(e.downloadState) && !"PINNED_FOR_ROLLBACK".equals(e.downloadState)) return null;
        File f = new File(e.localPath);
        return f.exists() ? f : null;
    }

    /**
     * Download an asset from url and save as assetId.
     * Callback is invoked on completion (success or failure).
     */
    public void downloadAsync(String assetId, String url, String expectedSha256, DownloadCallback cb) {
        exec.execute(() -> {
            try {
                sem.acquire();
                try {
                    download(assetId, url, expectedSha256, cb);
                } finally {
                    sem.release();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public interface DownloadCallback {
        void onSuccess(String assetId, String localPath);
        void onFailure(String assetId, String error);
    }

    public interface OnAssetReadyListener {
        /** Called on a background thread after a successful download + atomic rename. */
        void onAssetReady(String url, String localPath);
    }
    private volatile OnAssetReadyListener onAssetReadyListener;
    public void setOnAssetReadyListener(OnAssetReadyListener l) { this.onAssetReadyListener = l; }

    private void download(String assetId, String url, String expectedSha256, DownloadCallback cb) {
        File mediaDir = getMediaDir();
        if (mediaDir == null) { cb.onFailure(assetId, "storage_unavailable"); return; }
        if (freeBytes() < MIN_FREE_BYTES) { cb.onFailure(assetId, "storage_full"); return; }

        String safeId = assetId.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeId.length() > 180) safeId = safeId.substring(safeId.length() - 180);
        File finalFile = new File(mediaDir, safeId);
        File tmpFile   = new File(mediaDir, safeId + ".tmp");

        // Check ETag / Last-Modified for conditional fetch
        PlaylistDatabase.AssetEntity existing = repo.getAsset(assetId);
        Request.Builder reqBuilder = new Request.Builder().url(url);
        if (existing != null && !existing.etag.isEmpty()) {
            reqBuilder.header("If-None-Match", existing.etag);
        } else if (existing != null && !existing.lastModified.isEmpty()) {
            reqBuilder.header("If-Modified-Since", existing.lastModified);
        }
        // Range request: resume if .tmp exists
        long resumeFrom = tmpFile.exists() ? tmpFile.length() : 0;
        if (resumeFrom > 0) reqBuilder.header("Range", "bytes=" + resumeFrom + "-");

        try {
            Response resp = http.newCall(reqBuilder.build()).execute();
            if (resp.code() == 304) {
                // Not modified — existing file is still valid
                Log.d(TAG, "[skip] 304 Not Modified: " + assetId);
                resp.close();
                repo.markAssetReady(assetId, finalFile.getAbsolutePath(),
                        existing.sha256, existing.etag, existing.lastModified, finalFile.length());
                cb.onSuccess(assetId, finalFile.getAbsolutePath());
                return;
            }
            if (!resp.isSuccessful() && resp.code() != 206) {
                resp.close();
                cb.onFailure(assetId, "http_" + resp.code());
                return;
            }

            String etag   = resp.header("ETag", "");
            String lm     = resp.header("Last-Modified", "");
            boolean resume = resp.code() == 206;

            // Write to .tmp (append if resuming)
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // If resuming, hash existing bytes first
            if (resume && tmpFile.exists()) {
                try (FileInputStream fis = new FileInputStream(tmpFile)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = fis.read(buf)) != -1) digest.update(buf, 0, n);
                }
            } else {
                tmpFile.delete(); // fresh start
            }

            try (ResponseBody body = resp.body();
                 FileOutputStream fos = new FileOutputStream(tmpFile, resume)) {
                if (body == null) { cb.onFailure(assetId, "empty_body"); return; }
                byte[] buf = new byte[8192]; int n;
                while ((n = body.byteStream().read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    digest.update(buf, 0, n);
                }
            }

            // Verify SHA256 if provided
            String actualSha = bytesToHex(digest.digest());
            if (expectedSha256 != null && !expectedSha256.isEmpty()
                    && !expectedSha256.equalsIgnoreCase(actualSha)) {
                tmpFile.delete();
                cb.onFailure(assetId, "sha256_mismatch expected=" + expectedSha256 + " got=" + actualSha);
                return;
            }

            // Atomic rename
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.delete();
                cb.onFailure(assetId, "rename_failed");
                return;
            }

            repo.markAssetReady(assetId, finalFile.getAbsolutePath(), actualSha, etag, lm, finalFile.length());
            Log.i(TAG, "[done] " + assetId + " sha=" + actualSha.substring(0, 8) + "…");
            cb.onSuccess(assetId, finalFile.getAbsolutePath());
            if (onAssetReadyListener != null) onAssetReadyListener.onAssetReady(url, finalFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "[error] " + assetId + ": " + e.getMessage());
            repo.markAssetFailed(assetId, e.getMessage() != null ? e.getMessage() : "unknown");
            cb.onFailure(assetId, e.getMessage() != null ? e.getMessage() : "unknown");
        }
    }

    /** Remove assets that are neither pinned nor used by an ACTIVE/SUPERSEDED revision. */
    public void cleanupOldAssets() {
        exec.execute(() -> {
            long now = System.currentTimeMillis();
            long weekAgo = now - 7L * 86400 * 1000;
            java.util.List<PlaylistDatabase.AssetEntity> prunable =
                repo.getDb().assetDao().findPrunable(weekAgo, now);
            for (PlaylistDatabase.AssetEntity a : prunable) {
                if (a.localPath != null && !a.localPath.isEmpty()) {
                    new File(a.localPath).delete();
                }
            }
            Log.d(TAG, "[cleanup] Pruned " + prunable.size() + " stale assets");
        });
    }

    private File getMediaDir() {
        File dir = new File(ctx.getFilesDir(), "digipal_media");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }

    private long freeBytes() {
        try { return new StatFs(ctx.getFilesDir().getPath()).getAvailableBytes(); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Adjust download concurrency at runtime.
     * Call with 1 during PLAYING state to avoid I/O contention with ExoPlayer;
     * call with MAX_CONCURRENT when IDLE or ERROR_RECOVERY.
     */
    public synchronized void setMaxConcurrency(int max) {
        if (max < 1) max = 1;
        if (max > MAX_CONCURRENT) max = MAX_CONCURRENT;
        if (max == configuredMax) return;
        if (max > configuredMax) {
            sem.release(max - configuredMax);
        } else {
            sem.drainPermits();
            sem.release(max);
        }
        configuredMax = max;
        Log.d(TAG, "[concurrency] Download concurrency set to " + max);
    }

    public void shutdown() { exec.shutdown(); http.dispatcher().executorService().shutdown(); }
}
