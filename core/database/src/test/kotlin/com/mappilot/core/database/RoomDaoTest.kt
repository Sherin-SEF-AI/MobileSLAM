package com.mappilot.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mappilot.core.database.entity.AssetEntity
import com.mappilot.core.database.entity.KeyframeEntity
import com.mappilot.core.database.entity.TripEntity
import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.EnuPose
import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Pose
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.TrackingFailureReason
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end Room test on Robolectric's SQLite: builds the real schema (incl. the
 * spatial callback), inserts via the generated DAOs, and runs the spatial query
 * through the R*Tree (or the indexed fallback when the test SQLite lacks rtree).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDaoTest {

    private lateinit var db: MapPilotDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MapPilotDatabase::class.java,
        ).allowMainThreadQueries()
            .addCallback(MapPilotDatabase.SPATIAL_CALLBACK)
            .build()
    }

    @After fun tearDown() = db.close()

    @Test
    fun `trip insert and read round-trips`() = runTest {
        val id = db.tripDao().insert(
            TripEntity(
                startedNs = 1000, endedNs = 2000, distanceM = 12.5, areaM2 = 0.0,
                slamScore = 0.9f, gnssScore = 0.8f, mcapPath = "/t/trip.mcap",
                mp4Path = "/t/trip.mp4", status = "RECORDED", provenance = "ON_DEVICE",
            ),
        )
        val trip = db.tripDao().byId(id)!!
        assertThat(trip.distanceM).isEqualTo(12.5)
        assertThat(db.tripDao().count()).isEqualTo(1)
    }

    @Test
    fun `assets persist and spatial query finds those in the box`() = runTest {
        val tripId = 1L
        suspend fun add(cls: String, lat: Double, lon: Double) = db.assetDao().insert(
            AssetEntity(
                tripId = tripId, assetClass = cls, lat = lat, lon = lon, alt = 920.0,
                bboxLeft = 0f, bboxTop = 0f, bboxRight = 1f, bboxBottom = 1f,
                confidence = 0.9f, sourceFrameId = 0, depthM = 5f, embeddingId = null,
            ),
        )
        add("POTHOLE", 12.9716, 77.5946)        // center
        add("POTHOLE", 12.9716, 77.5951)        // ~54 m east — inside 500 m
        add("TRAFFIC_LIGHT", 12.9716, 77.6100)  // ~1.7 km east — outside

        val box = SpatialQueries.boundingBox(12.9716, 77.5946, 500.0)
        val hits = db.assetDao().spatial(
            SpatialQueries.assetsInBox(box[0], box[1], box[2], box[3], useRtree = MapPilotDatabase.rtreeAvailable),
        )
        // bbox prefilter + exact haversine refine
        val within = hits.map { it.toDomain() }
            .filter { Haversine.distanceM(12.9716, 77.5946, it.geo.latitude, it.geo.longitude) <= 500.0 }
        assertThat(within).hasSize(2)
        assertThat(db.assetDao().byClass("TRAFFIC_LIGHT")).hasSize(1)
    }

    @Test
    fun `keyframes persist via repository and map back to domain`() = runTest {
        val repo = MapPilotRepository(db)
        val tripId = 7L
        val kf = Keyframe(
            frameId = 42,
            timestampNs = 1_000,
            pose = Pose(1_000, Vector3(1.0, 2.0, 3.0), Quaternion(0.0, 0.0, 0.0, 1.0), TrackingState.TRACKING, TrackingFailureReason.NONE, 1f),
            enuPose = EnuPose(1_000, EnuPoint(10.0, 20.0, 30.0), Quaternion(0.0, 0.0, 0.0, 1.0), 0),
            intrinsics = null,
        )
        repo.saveKeyframes(tripId, listOf(kf))
        val back = repo.keyframesForTrip(tripId)
        assertThat(back).hasSize(1)
        assertThat(back[0].frameId).isEqualTo(42)
        assertThat(back[0].pose.position).isEqualTo(Vector3(1.0, 2.0, 3.0))
        assertThat(back[0].enuPose?.enu).isEqualTo(EnuPoint(10.0, 20.0, 30.0))
    }

    @Test
    fun `deleteTrip removes the trip and all its child rows`() = runTest {
        val repo = MapPilotRepository(db)
        val tripId = db.tripDao().insert(
            TripEntity(
                startedNs = 0, endedNs = 1, distanceM = 1.0, areaM2 = 0.0,
                slamScore = 1f, gnssScore = 1f, mcapPath = "/t/trip.mcap",
                mp4Path = null, status = "RECORDED", provenance = "ON_DEVICE",
            ),
        )
        db.assetDao().insert(
            AssetEntity(
                tripId = tripId, assetClass = "POTHOLE", lat = 1.0, lon = 2.0, alt = 0.0,
                bboxLeft = 0f, bboxTop = 0f, bboxRight = 1f, bboxBottom = 1f,
                confidence = 0.9f, sourceFrameId = 0, depthM = 5f, embeddingId = null,
            ),
        )
        db.keyframeDao().insertAll(
            listOf(
                KeyframeEntity(
                    tripId = tripId, frameId = 1, timestampNs = 1,
                    px = 0.0, py = 0.0, pz = 0.0, qx = 0.0, qy = 0.0, qz = 0.0, qw = 1.0,
                    east = null, north = null, up = null, fx = null, fy = null, cx = null, cy = null,
                ),
            ),
        )

        repo.deleteTrip(tripId)

        assertThat(db.tripDao().byId(tripId)).isNull()
        assertThat(db.assetDao().byTrip(tripId)).isEmpty()
        assertThat(db.keyframeDao().byTrip(tripId)).isEmpty()
    }
}
