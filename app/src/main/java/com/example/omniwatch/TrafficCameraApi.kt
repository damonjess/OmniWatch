package com.example.omniwatch

import retrofit2.http.GET

interface TrafficCameraApi {
    // The official live endpoint for TfL JamCams
    @GET("/Place/Type/JamCam")
    suspend fun getLiveCameras(): List<TflPlace>
}

// Data classes mapped directly to the TfL Unified API JSON structure
data class TflPlace(
    val commonName: String,
    val lat: Double,
    val lon: Double,
    val additionalProperties: List<TflProperty>?
)

data class TflProperty(
    val key: String,
    val value: String
)
