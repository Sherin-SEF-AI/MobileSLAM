package com.mappilot.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mappilot.core.database.dao.AssetDao
import com.mappilot.core.database.dao.EmbeddingDao
import com.mappilot.core.database.dao.EventDao
import com.mappilot.core.database.dao.GnssEpochSummaryDao
import com.mappilot.core.database.dao.GnssFixDao
import com.mappilot.core.database.dao.KeyframeDao
import com.mappilot.core.database.dao.LandmarkDao
import com.mappilot.core.database.dao.TripDao
import com.mappilot.core.database.dao.UploadJobDao
import com.mappilot.core.database.entity.AssetEntity
import com.mappilot.core.database.entity.EmbeddingEntity
import com.mappilot.core.database.entity.EventEntity
import com.mappilot.core.database.entity.GnssEpochSummaryEntity
import com.mappilot.core.database.entity.GnssFixEntity
import com.mappilot.core.database.entity.KeyframeEntity
import com.mappilot.core.database.entity.LandmarkEntity
import com.mappilot.core.database.entity.TripEntity
import com.mappilot.core.database.entity.UploadJobEntity

@Database(
    entities = [
        TripEntity::class,
        KeyframeEntity::class,
        GnssFixEntity::class,
        GnssEpochSummaryEntity::class,
        LandmarkEntity::class,
        AssetEntity::class,
        EmbeddingEntity::class,
        EventEntity::class,
        UploadJobEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MapPilotDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun assetDao(): AssetDao
    abstract fun gnssFixDao(): GnssFixDao
    abstract fun landmarkDao(): LandmarkDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun keyframeDao(): KeyframeDao
    abstract fun gnssEpochSummaryDao(): GnssEpochSummaryDao
    abstract fun eventDao(): EventDao
    abstract fun uploadJobDao(): UploadJobDao

    companion object {
        const val NAME = "mappilot.db"

        /** v1 -> v2: semantic anchoring columns on assets (nullable, no backfill needed). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assets ADD COLUMN semanticLabel TEXT")
                db.execSQL("ALTER TABLE assets ADD COLUMN positionStdM REAL")
            }
        }

        /**
         * True once R*Tree virtual tables were created successfully. When false
         * (platform SQLite built without SQLITE_ENABLE_RTREE), spatial queries use
         * indexed lat/lon bbox scans instead — same results, no acceleration.
         */
        @Volatile var rtreeAvailable: Boolean = false
            private set

        /**
         * Creates R*Tree virtual tables and the triggers that keep them in sync
         * with the base tables. R*Tree stores (id, minLon, maxLon, minLat, maxLat);
         * for point features min == max. Failure (no rtree module) is caught and
         * recorded rather than crashing.
         */
        val SPATIAL_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                rtreeAvailable = try {
                    SPATIAL_DDL.forEach(db::execSQL)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        /** Idempotent DDL; also runnable against a plain SQLite for verification. */
        val SPATIAL_DDL: List<String> = listOf(
            // --- assets ---
            "CREATE VIRTUAL TABLE IF NOT EXISTS asset_rtree USING rtree(id, minLon, maxLon, minLat, maxLat)",
            """CREATE TRIGGER IF NOT EXISTS asset_ai AFTER INSERT ON assets BEGIN
                 INSERT INTO asset_rtree(id, minLon, maxLon, minLat, maxLat)
                 VALUES (new.id, new.lon, new.lon, new.lat, new.lat);
               END""",
            """CREATE TRIGGER IF NOT EXISTS asset_ad AFTER DELETE ON assets BEGIN
                 DELETE FROM asset_rtree WHERE id = old.id;
               END""",
            """CREATE TRIGGER IF NOT EXISTS asset_au AFTER UPDATE ON assets BEGIN
                 UPDATE asset_rtree SET minLon=new.lon, maxLon=new.lon, minLat=new.lat, maxLat=new.lat
                 WHERE id = old.id;
               END""",
            // --- gnss fixes ---
            "CREATE VIRTUAL TABLE IF NOT EXISTS gnss_fix_rtree USING rtree(id, minLon, maxLon, minLat, maxLat)",
            """CREATE TRIGGER IF NOT EXISTS gnss_fix_ai AFTER INSERT ON gnss_fixes BEGIN
                 INSERT INTO gnss_fix_rtree(id, minLon, maxLon, minLat, maxLat)
                 VALUES (new.id, new.lon, new.lon, new.lat, new.lat);
               END""",
            """CREATE TRIGGER IF NOT EXISTS gnss_fix_ad AFTER DELETE ON gnss_fixes BEGIN
                 DELETE FROM gnss_fix_rtree WHERE id = old.id;
               END""",
            // --- landmarks (georeferenced ones) ---
            "CREATE VIRTUAL TABLE IF NOT EXISTS landmark_rtree USING rtree(id, minLon, maxLon, minLat, maxLat)",
            """CREATE TRIGGER IF NOT EXISTS landmark_ai AFTER INSERT ON landmarks WHEN new.lat IS NOT NULL BEGIN
                 INSERT INTO landmark_rtree(id, minLon, maxLon, minLat, maxLat)
                 VALUES (new.id, new.lon, new.lon, new.lat, new.lat);
               END""",
            """CREATE TRIGGER IF NOT EXISTS landmark_ad AFTER DELETE ON landmarks BEGIN
                 DELETE FROM landmark_rtree WHERE id = old.id;
               END""",
        )
    }
}
