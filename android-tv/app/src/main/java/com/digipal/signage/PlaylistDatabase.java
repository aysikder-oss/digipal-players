package com.digipal.signage;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.*;
import java.util.List;

/**
 * PlaylistDatabase — Room DB for native playlist scheduling.
 * Separate from CacheDatabase (JSON blob store for WebView boot).
 * Entities: playlist_revisions, slides, assets, playback_events, player_errors.
 */
public class PlaylistDatabase {

    @Entity(tableName = "playlist_revisions")
    public static class PlaylistRevisionEntity {
        @PrimaryKey(autoGenerate = true) public long id;
        @ColumnInfo(name = "playlist_id")    public String playlistId = "";
        @ColumnInfo(name = "revision_id")    public String revisionId = "";
        @ColumnInfo(name = "json")           public String json = "";
        @ColumnInfo(name = "status")         public String status = "DOWNLOADING";
        @ColumnInfo(name = "created_at")     public long createdAt;
        @ColumnInfo(name = "activated_at")   public long activatedAt;
        @ColumnInfo(name = "last_played_at") public long lastPlayedAt;
    }

    @Entity(tableName = "slides")
    public static class SlideEntity {
        @PrimaryKey(autoGenerate = true) public long id;
        @ColumnInfo(name = "revision_id")       public long revisionId;
        @ColumnInfo(name = "slide_id")          public String slideId = "";
        @ColumnInfo(name = "type")              public String type = "IMAGE";
        @ColumnInfo(name = "asset_id")          public String assetId = "";
        @ColumnInfo(name = "duration_ms")       public long durationMs = 10000;
        @ColumnInfo(name = "order_index")       public int orderIndex;
        @ColumnInfo(name = "renderer_hint")     public String rendererHint = "";
        @ColumnInfo(name = "fallback_asset_id") public String fallbackAssetId = "";
        @ColumnInfo(name = "config_json")       public String configJson = "{}";
    }

    @Entity(tableName = "assets")
    public static class AssetEntity {
        @PrimaryKey(autoGenerate = true) public long id;
        @ColumnInfo(name = "asset_id")          public String assetId = "";
        @ColumnInfo(name = "url")               public String url = "";
        @ColumnInfo(name = "mime_type")         public String mimeType = "";
        @ColumnInfo(name = "local_path")        public String localPath = "";
        @ColumnInfo(name = "size_bytes")        public long sizeBytes;
        @ColumnInfo(name = "sha256")            public String sha256 = "";
        @ColumnInfo(name = "etag")              public String etag = "";
        @ColumnInfo(name = "last_modified")     public String lastModified = "";
        @ColumnInfo(name = "download_state")    public String downloadState = "PENDING";
        @ColumnInfo(name = "last_used_at")      public long lastUsedAt;
        @ColumnInfo(name = "pinned_until")      public long pinnedUntil;
        @ColumnInfo(name = "error")             public String error = "";
        @ColumnInfo(name = "prerendered_pages") public String prerenderedPages = "";
    }

    @Entity(tableName = "playback_events")
    public static class PlaybackEventEntity {
        @PrimaryKey(autoGenerate = true) public long id;
        @ColumnInfo(name = "timestamp")     public long timestamp;
        @ColumnInfo(name = "revision_id")   public String revisionId = "";
        @ColumnInfo(name = "slide_id")      public String slideId = "";
        @ColumnInfo(name = "asset_id")      public String assetId = "";
        @ColumnInfo(name = "event_type")    public String eventType = "";
        @ColumnInfo(name = "renderer_type") public String rendererType = "";
        @ColumnInfo(name = "duration_ms")   public long durationMs;
        @ColumnInfo(name = "details_json")  public String detailsJson = "{}";
        @ColumnInfo(name = "synced")        public boolean synced = false;
    }

    @Entity(tableName = "player_errors")
    public static class PlayerErrorEntity {
        @PrimaryKey(autoGenerate = true) public long id;
        @ColumnInfo(name = "timestamp")    public long timestamp;
        @ColumnInfo(name = "severity")     public String severity = "ERROR";
        @ColumnInfo(name = "component")    public String component = "";
        @ColumnInfo(name = "code")         public String code = "";
        @ColumnInfo(name = "message")      public String message = "";
        @ColumnInfo(name = "details_json") public String detailsJson = "{}";
        @ColumnInfo(name = "synced")       public boolean synced = false;
    }

    @Dao public interface PlaylistRevisionDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insert(PlaylistRevisionEntity e);
        @Query("UPDATE playlist_revisions SET status=:s WHERE id=:id") void setStatus(long id, String s);
        @Query("UPDATE playlist_revisions SET status='SUPERSEDED' WHERE status='ACTIVE'") void supersedePrevious();
        @Query("UPDATE playlist_revisions SET activated_at=:ts,status='ACTIVE' WHERE id=:id") void activate(long id, long ts);
        @Query("SELECT * FROM playlist_revisions WHERE status='ACTIVE' ORDER BY activated_at DESC LIMIT 1") PlaylistRevisionEntity getActive();
        @Query("SELECT * FROM playlist_revisions WHERE status IN ('ACTIVE','SUPERSEDED') ORDER BY activated_at DESC LIMIT 2") List<PlaylistRevisionEntity> getLastTwo();
        @Query("DELETE FROM playlist_revisions WHERE status='SUPERSEDED' AND activated_at<:before") void pruneOld(long before);
    }

    @Dao public interface SlideDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE) void insertAll(List<SlideEntity> slides);
        @Query("SELECT * FROM slides WHERE revision_id=:revId ORDER BY order_index") List<SlideEntity> forRevision(long revId);
        @Query("DELETE FROM slides WHERE revision_id=:revId") void deleteForRevision(long revId);
    }

    @Dao public interface AssetDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insert(AssetEntity e);
        @Query("UPDATE assets SET download_state=:state,local_path=:path,sha256=:sha,etag=:etag,last_modified=:lm,size_bytes=:sz WHERE asset_id=:id")
        void markReady(String id, String state, String path, String sha, String etag, String lm, long sz);
        @Query("UPDATE assets SET download_state=:state,error=:err WHERE asset_id=:id") void markFailed(String id, String state, String err);
        @Query("UPDATE assets SET pinned_until=:until WHERE asset_id=:id") void setPinnedUntil(String id, long until);
        @Query("SELECT * FROM assets WHERE asset_id=:id ORDER BY id DESC LIMIT 1") AssetEntity findById(String id);
        @Query("UPDATE assets SET last_used_at=:ts WHERE asset_id=:id") void touch(String id, long ts);
        @Query("UPDATE assets SET prerendered_pages=:pages WHERE asset_id=:id") void setPrerenderedPages(String id, String pages);
        @Query("SELECT * FROM assets WHERE download_state NOT IN ('READY','PINNED_FOR_ROLLBACK') AND last_used_at<:before AND pinned_until<:now") List<AssetEntity> findPrunable(long before, long now);
    }

    @Dao public interface PlaybackEventDao {
        @Insert void insert(PlaybackEventEntity e);
        @Query("SELECT * FROM playback_events WHERE synced=0 ORDER BY timestamp LIMIT 100") List<PlaybackEventEntity> getUnsynced();
        @Query("UPDATE playback_events SET synced=1 WHERE id IN (:ids)") void markSynced(List<Long> ids);
        @Query("DELETE FROM playback_events WHERE synced=1 AND timestamp<:before") void pruneOld(long before);
    }

    @Dao public interface PlayerErrorDao {
        @Insert void insert(PlayerErrorEntity e);
        @Query("SELECT * FROM player_errors WHERE synced=0 ORDER BY timestamp LIMIT 50") List<PlayerErrorEntity> getUnsynced();
        @Query("UPDATE player_errors SET synced=1 WHERE id IN (:ids)") void markSynced(List<Long> ids);
    }

    @Database(entities={PlaylistRevisionEntity.class,SlideEntity.class,AssetEntity.class,PlaybackEventEntity.class,PlayerErrorEntity.class}, version=1, exportSchema=false)
    public abstract static class AppDatabase extends RoomDatabase {
        public abstract PlaylistRevisionDao revisionDao();
        public abstract SlideDao slideDao();
        public abstract AssetDao assetDao();
        public abstract PlaybackEventDao eventDao();
        public abstract PlayerErrorDao errorDao();
    }

    private static volatile AppDatabase INSTANCE;
    public static AppDatabase getInstance(android.content.Context ctx) {
        if (INSTANCE == null) {
            synchronized (PlaylistDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(ctx.getApplicationContext(), AppDatabase.class, "playlist_native.db")
                        .fallbackToDestructiveMigration().build();
                }
            }
        }
        return INSTANCE;
    }
}
