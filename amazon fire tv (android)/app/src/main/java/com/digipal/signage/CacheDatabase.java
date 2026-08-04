package com.digipal.signage;

    import android.content.Context;
    import androidx.annotation.NonNull;
    import androidx.room.ColumnInfo;
    import androidx.room.Dao;
    import androidx.room.Database;
    import androidx.room.Entity;
    import androidx.room.Insert;
    import androidx.room.OnConflictStrategy;
    import androidx.room.PrimaryKey;
    import androidx.room.Query;
    import androidx.room.Room;
    import androidx.room.RoomDatabase;
    import java.util.List;

    /**
     * Room-based offline playlist cache.
     * Stores the last successful server JSON blobs so the player can inject them into the
     * React WebView immediately on boot — no blank screen while waiting for a network response.
     * OptiSigns-inspired lightweight JSON store (CacheObject entity mirrors their schema).
     *
     * NOTE: the @Database-annotated class must stay top-level. Nesting it inside another
     * class produces a binary name like Outer$AppDatabase, and Room/R8 can fail to locate
     * the generated *_Impl class for that nested name in minified release builds. Entity/DAO
     * types can still be nested since they aren't affected by this.
     */
    @Database(entities = {CacheDatabase.CacheObject.class}, version = 1, exportSchema = true)
    public abstract class CacheDatabase extends RoomDatabase {

        // ── Entity ────────────────────────────────────────────────────────────────
        @Entity(tableName = "cache_objects")
        public static class CacheObject {
            @PrimaryKey
            @NonNull
            @ColumnInfo(name = "cache_key")
            public String key = "";

            @ColumnInfo(name = "json")
            public String json;

            @ColumnInfo(name = "updated_at")
            public long updatedAt;
        }

        // ── DAO ───────────────────────────────────────────────────────────────────
        @Dao
        public interface CacheDao {
            @Query("SELECT * FROM cache_objects WHERE cache_key = :key LIMIT 1")
            CacheObject findByKey(String key);

            @Query("SELECT * FROM cache_objects")
            List<CacheObject> findAll();

            @Insert(onConflict = OnConflictStrategy.REPLACE)
            void upsert(CacheObject obj);
        }

        public abstract CacheDao cacheDao();

        private static volatile CacheDatabase INSTANCE;

        public static CacheDatabase getInstance(Context context) {
            if (INSTANCE == null) {
                synchronized (CacheDatabase.class) {
                    if (INSTANCE == null) {
                        INSTANCE = Room.databaseBuilder(
                                context.getApplicationContext(),
                                CacheDatabase.class,
                                "digipal_cache.db"
                        ).build();
                    }
                }
            }
            return INSTANCE;
        }
    }
    