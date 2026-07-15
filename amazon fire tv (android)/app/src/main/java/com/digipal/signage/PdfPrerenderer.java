package com.nexuscast.player;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import org.json.JSONArray;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * PdfPrerenderer — converts PDF pages to JPEG images using Android PdfRenderer API.
 * Called by AssetCacheManager after a PDF asset is downloaded.
 * Outputs are stored alongside the PDF and paths saved in Room AssetEntity.prerenderedPages.
 *
 * Fire TV benefit: PDF-in-WebView is very expensive on 2 GB devices.
 * Pre-rendered JPEGs play as native image slides via Glide — zero WebView cost.
 */
public class PdfPrerenderer {

    private static final String TAG = "PdfPrerenderer";
    private static final int MAX_WIDTH  = 1920;
    private static final int MAX_HEIGHT = 1080;
    private static final int JPEG_QUALITY = 88;
    private static final int MAX_PAGES = 25;

    public interface Callback {
        void onPagesReady(String assetId, List<String> pageLocalPaths);
        void onFailed(String assetId, String error);
    }

    private final Context ctx;
    private final PlaylistRepository repo;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    public PdfPrerenderer(Context ctx, PlaylistRepository repo) {
        this.ctx = ctx; this.repo = repo;
    }

    /** Pre-render all pages of pdfLocalPath and persist paths to Room. */
    private List<File> mediaRoots() {
        List<File> roots = new ArrayList<>();
        File ext = ctx.getExternalFilesDir("media");
        if (ext != null) roots.add(ext);
        roots.add(new File(ctx.getFilesDir(), "media"));
        return roots;
    }

    private String normalizeLocalPath(String path) {
        if (path == null) return "";
        if (path.startsWith("file://")) {
            try {
                return android.net.Uri.parse(path).getPath();
            } catch (Throwable ignored) {
                return path.substring(7);
            }
        }
        return path;
    }

    public void prerender(String assetId, String pdfLocalPath, Callback cb) {
        exec.execute(() -> {
            List<String> paths = new ArrayList<>();
            try {
                String normalized = normalizeLocalPath(pdfLocalPath);
                File pdf = null;
                for (File root : mediaRoots()) {
                    pdf = SafeFiles.existingFileInsideOrNull(root, normalized);
                    if (pdf != null) break;
                }
                if (pdf == null) { cb.onFailed(assetId, "pdf_not_found"); return; }

                File outDir = new File(ctx.getFilesDir(), "digipal_pdf_pages");
                if (!outDir.exists() && !outDir.mkdirs()) { cb.onFailed(assetId, "pdf_output_unavailable"); return; }

                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer renderer = new PdfRenderer(pfd)) {

                    int pageCount = Math.min(renderer.getPageCount(), MAX_PAGES);
                    Log.i(TAG, "[prerender] " + assetId + " pages=" + pageCount);

                    for (int i = 0; i < pageCount; i++) {
                        // Check memory pressure before each page
                        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                        ((android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
                        if (mi.lowMemory) {
                            Log.w(TAG, "[prerender] low memory — pausing at page " + i);
                            Thread.sleep(2000); // brief pause
                        }

                        try (PdfRenderer.Page page = renderer.openPage(i)) {
                            // Scale to fit MAX_WIDTH x MAX_HEIGHT maintaining aspect ratio
                            float aspect = (float) page.getWidth() / page.getHeight();
                            int w, h;
                            if (aspect >= (float) MAX_WIDTH / MAX_HEIGHT) {
                                w = MAX_WIDTH; h = Math.round(MAX_WIDTH / aspect);
                            } else {
                                h = MAX_HEIGHT; w = Math.round(MAX_HEIGHT * aspect);
                            }

                            String safeId = assetId.replaceAll("[^a-zA-Z0-9._-]", "_");
                            if (safeId.length() > 120) safeId = safeId.substring(safeId.length() - 120);
                            File outFile = SafeFiles.child(outDir, safeId + "_page" + i + ".jpg");

                            Bitmap bmp = null;
                            try {
                                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                                bmp.eraseColor(Color.WHITE);
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                                }
                            } finally {
                                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                            }
                            paths.add(outFile.getAbsolutePath());
                            Log.d(TAG, "[page " + i + "] -> " + outFile.getName());
                        }
                    }
                }

                // Save page paths to Room
                JSONArray arr = new JSONArray();
                for (String p : paths) arr.put(p);
                repo.getDb().assetDao().setPrerenderedPages(assetId, arr.toString());
                Log.i(TAG, "[done] " + assetId + " rendered " + paths.size() + " pages");
                cb.onPagesReady(assetId, paths);

            } catch (Exception e) {
                Log.e(TAG, "[error] " + assetId + ": " + e.getMessage());
                cb.onFailed(assetId, e.getMessage() != null ? e.getMessage() : "unknown");
            }
        });
    }

    public void shutdown() { exec.shutdown(); }
}
