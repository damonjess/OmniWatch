package com.example.omniwatch

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

// Retrofit interface for background Overpass (OpenStreetMap) requests.
// The endpoint is passed per call so mirror servers can be used as fallbacks.
interface OverpassApi {
    @GET
    suspend fun getCctvCameras(@Url url: String, @Query("data") query: String): OverpassResponse
}

data class OverpassResponse(
    // Nullable: Overpass may omit "elements" entirely, and Gson would leave it null regardless.
    val elements: List<OverpassElement>?,
    // Overpass reports timeouts, rate limiting and query errors as HTTP 200 with an empty
    // "elements" list plus this remark, so callers must check it before trusting the result.
    val remark: String? = null,
)

/**
 * A node, way or relation. Nodes carry [lat]/[lon] directly; ways and relations carry their
 * representative point in [center] when the query asked for `out center`.
 */
data class OverpassElement(
    val type: String? = null,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String>? = null,
)

data class OverpassCenter(
    val lat: Double,
    val lon: Double,
)
