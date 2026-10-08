package com.example.omniwatch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CameraDao {
    @Query("SELECT * FROM cameras")
    suspend fun getAllCameras(): List<CameraEntity>

    /** Cameras inside the visible map area, so panning shows local data instantly. */
    @Query(
        "SELECT * FROM cameras WHERE lat BETWEEN :latSouth AND :latNorth " +
            "AND lon BETWEEN :lonWest AND :lonEast"
    )
    suspend fun getCamerasIn(
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double
    ): List<CameraEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCameras(cameras: List<CameraEntity>)

    @Query("DELETE FROM cameras WHERE source = 'COUNCIL'")
    suspend fun clearCouncilCameras()

    @Query("DELETE FROM cameras WHERE source = 'National Highways'")
    suspend fun clearNationalHighwaysCameras()

    @Query("DELETE FROM cameras WHERE source IN ('Traffic Wales', 'TrafficWatchNI', 'Essex Highways')")
    suspend fun clearRegionalTrafficCameras()

    @Query("DELETE FROM cameras WHERE title LIKE '%MIDAS%' OR title LIKE '%TMU%'")
    suspend fun clearSensorCameras()

    @Query("DELETE FROM cameras")
    suspend fun clearAll()
}
