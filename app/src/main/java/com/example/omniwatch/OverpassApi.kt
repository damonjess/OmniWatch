package com.example.omniwatch

import retrofit2.http.GET
import retrofit2.http.Query

// Retrofit Interface for background network requests
interface OverpassApi {
    @GET("/api/interpreter")
    suspend fun getCctvCameras(@Query("data") query: String): OverpassResponse
}

data class OverpassResponse(
    val elements: List<OsmNode>
)

data class OsmNode(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>?
)
