package com.nexuscast.player;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PlaylistRepository — CRUD + DOWNLOADING→READY→ACTIVE lifecycle for playlist revisions.
 * All DB operations are synchronous (call on background thread).
 */
public class PlaylistRepository {

    private static final String TAG = "PlaylistRepo";
    private final PlaylistDatabase.AppDatabase db;

    public PlaylistRepository(Context ctx) {
        this.db = PlaylistDatabase.getInstance(ctx);
    }

    /** Insert or replace a playlist revision (status=READY if assets available). */
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

    /** Activate a revision — supersedes any current ACTIVE. */
    public void activateRevision(long revisionId) {
        db.revisionDao().supersedePrevious();
        db.revisionDao().activate(revisionId, System.currentTimeMillis());
        Log.i(TAG, "activated revisionId=" + revisionId);
        // Prune revisions older than 7 days
        db.revisionDao().pruneOld(System.currentTimeMillis() - 7L * 86400 * 1000);
    }

    /** Get the currently ACTIVE revision (null if none). */
    public PlaylistDatabase.PlaylistRevisionEntity getActive() {
        return db.revisionDao().getActive();
    }

    /** Get the last known good revision for rollback. */
    public PlaylistDatabase.PlaylistRevisionEntity getLastKnownGood() {
        List<PlaylistDatabase.PlaylistRevisionEntity> list = db.revisionDao().getLastTwo();
        return list.isEmpty() ? null : list.get(0);
    }

    /** Save slides for a revision (replaces existing). */
    public void saveSlides(long revisionId, List<PlaylistDatabase.SlideEntity> slides) {
        db.slideDao().deleteForRevision(revisionId);
        db.slideDao().insertAll(slides);
    }

    /** Get slides for a revision in playback order. */
    public List<PlaylistDatabase.SlideEntity> getSlidesForRevision(long revisionId) {
        return db.slideDao().forRevision(revisionId);
    }

    /** Persist slides parsed from playlist JSON for a given revision. */
    public void saveSlidesFromJson(long revisionId, String json) {
        try {
            JSONArray arr = new JSONArray(json);
            List<PlaylistDatabase.SlideEntity> entities = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                PlaylistDatabase.SlideEntity s = new PlaylistDatabase.SlideEntity();
                s.revisionId = revisionId;
                s.slideId = obj.optString("slideId", String.valueOf(obj.optInt("contentId", i)));
                String type = obj.optString("type", "WEBVIEW_URL");
                s.type = "VIDEO".equals(type) ? "VIDEO"
                       : "IMAGE".equals(type) ? "IMAGE"
                       : "WEBVIEW_DESIGN".equals(type) ? "WEBVIEW_DESIGN"
                       : "WEBVIEW_KIOSK".equals(type) ? "WEBVIEW_KIOSK"
                       : "WEBVIEW_URL";
                s.durationMs = (long)(obj.optDouble("duration", 10) * 1000);
                s.orderIndex = i;
                s.configJson = obj.toString();
                entities.add(s);
            }
            saveSlides(revisionId, entities);
            Log.d(TAG, "Saved " + entities.size() + " slides for revisionId=" + revisionId);
        } catch (Exception e) {
            Log.e(TAG, "saveSlidesFromJson error: " + e.getMessage());
        }
    }

    /** Get asset by assetId. */
    public PlaylistDatabase.AssetEntity getAsset(String assetId) {
        return db.assetDao().findById(assetId);
    }

    /** Mark an asset as ready (downloaded + verified). */
    public void markAssetReady(String assetId, String localPath, String sha256, String etag, String lastMod, long size) {
        db.assetDao().markReady(assetId, "READY", localPath, sha256, etag, lastMod, size);
    }

    /** Mark an asset as failed. */
    public void markAssetFailed(String assetId, String error) {
        db.assetDao().markFailed(assetId, "FAILED", error);
    }

    /** Pin asset for rollback (keep on disk even after new revision activates). */
    public void pinAssetForRollback(String assetId, long untilMs) {
        db.assetDao().setPinnedUntil(assetId, untilMs);
    }

    public PlaylistDatabase.AppDatabase getDb() { return db; }
}
