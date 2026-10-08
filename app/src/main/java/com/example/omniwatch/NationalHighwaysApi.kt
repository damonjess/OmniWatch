package com.example.omniwatch

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET

interface NationalHighwaysApi {
    @GET("/api/v1.0/sites")
    suspend fun getWebtrisSites(): WebtrisResponse
}

data class WebtrisResponse(
    @SerializedName("row_count")
    val rowCount: Int? = null,
    val sites: List<WebtrisSite>? = null
)

data class WebtrisSite(
    @SerializedName("Id")
    val id: String,
    @SerializedName("Name")
    val name: String? = null,
    @SerializedName("Description")
    val description: String? = null,
    @SerializedName("Longitude")
    val longitude: Double = 0.0,
    @SerializedName("Latitude")
    val latitude: Double = 0.0,
    @SerializedName("Status")
    val status: String? = null
)
