package com.mappilot.core.database.di

import android.content.Context
import androidx.room.Room
import com.mappilot.core.database.MapPilotDatabase
import com.mappilot.core.database.dao.AssetDao
import com.mappilot.core.database.dao.EmbeddingDao
import com.mappilot.core.database.dao.EventDao
import com.mappilot.core.database.dao.GnssFixDao
import com.mappilot.core.database.dao.LandmarkDao
import com.mappilot.core.database.dao.TripDao
import com.mappilot.core.database.dao.UploadJobDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Room database on the platform SQLite. The spatial callback creates
 * R*Tree virtual tables where the platform supports them and records availability
 * ([MapPilotDatabase.rtreeAvailable]); spatial queries fall back to indexed
 * lat/lon bbox scans otherwise (see ADR 0011).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MapPilotDatabase =
        Room.databaseBuilder(context, MapPilotDatabase::class.java, MapPilotDatabase.NAME)
            .addCallback(MapPilotDatabase.SPATIAL_CALLBACK)
            .build()

    @Provides fun tripDao(db: MapPilotDatabase): TripDao = db.tripDao()
    @Provides fun assetDao(db: MapPilotDatabase): AssetDao = db.assetDao()
    @Provides fun gnssFixDao(db: MapPilotDatabase): GnssFixDao = db.gnssFixDao()
    @Provides fun landmarkDao(db: MapPilotDatabase): LandmarkDao = db.landmarkDao()
    @Provides fun embeddingDao(db: MapPilotDatabase): EmbeddingDao = db.embeddingDao()
    @Provides fun eventDao(db: MapPilotDatabase): EventDao = db.eventDao()
    @Provides fun uploadJobDao(db: MapPilotDatabase): UploadJobDao = db.uploadJobDao()
}
