package com.example.omniwatch

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Live International Space Station tracking.
 *
 * This is the same open feed the TrafficVision.Live ISS view is built on: wheretheiss.at publishes
 * the station's latitude, longitude, altitude and velocity, and it reports altitude in kilometres
 * and velocity in km/h. TrafficVision's own catalogue entry for the ISS carries a *frozen*
 * position, so a marker placed from it drifts for ever; the coordinates have to be re-read while
 * the app is open.
 */
interface IssApi {
    /** The station's position now. 25544 is the ISS's NORAD catalogue number. */
    @GET("v1/satellites/25544")
    suspend fun getPosition(): IssPosition

    /**
     * The country under a point, used for the tracker sheet's "OVER" row. Answers 404 over open
     * ocean, which callers treat as "no country" rather than as a failure.
     */
    @GET("v1/coordinates/{lat},{lon}")
    suspend fun getRegion(
        @Path("lat") latitude: Double,
        @Path("lon") longitude: Double,
    ): IssRegion
}

data class IssPosition(
    val latitude: Double,
    val longitude: Double,
    /** Kilometres above sea level. */
    val altitude: Double,
    /** Kilometres per hour. */
    val velocity: Double,
    /** "daylight" or "eclipsed". */
    val visibility: String? = null,
)

data class IssRegion(
    @SerializedName("country_code") val countryCode: String? = null,
)
