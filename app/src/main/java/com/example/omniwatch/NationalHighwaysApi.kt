package com.example.omniwatch

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET

/** Public camera catalogue used by the National Highways CCTV service. */
interface NationalHighwaysApi {
    @GET("/api/cameras")
    suspend fun getCameras(): List<NationalHighwaysCamera>
}

data class NationalHighwaysCamera(
    val id: Long? = null,
    @SerializedName("internal_id")
    val internalId: String? = null,
    val active: Boolean = true,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val name: String? = null,
    val road: String? = null,
    val direction: String? = null,
    @SerializedName("image_url")
    val imageUrl: String? = null,
    val source: String? = null,
)
