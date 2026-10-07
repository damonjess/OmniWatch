package com.example.omniwatch

import android.content.Context
import com.example.omniwatch.data.db.CameraEntity
import com.google.gson.Gson
import java.io.InputStreamReader

data class GeoJsonFeatureCollection(
    val type: String,
    val features: List<GeoJsonFeature>
)

data class GeoJsonFeature(
    val type: String,
    val geometry: GeoJsonGeometry,
    val properties: Map<String, String>?
)

data class GeoJsonGeometry(
    val type: String,
    val coordinates: List<Double> // [longitude, latitude]
)

object CouncilDataLoader {
    /**
     * Reads a GeoJSON file from the assets folder and converts features into [CameraEntity] list.
     */
    fun loadCouncilCctvFromAssets(context: Context, fileName: String = "council_cctv.geojson"): List<CameraEntity> {
        return try {
            context.assets.open(fileName).use { inputStream ->
                val reader = InputStreamReader(inputStream)
                val featureCollection = Gson().fromJson(reader, GeoJsonFeatureCollection::class.java)
                featureCollection.features.mapNotNull { feature ->
                    if (feature.geometry.type == "Point" && feature.geometry.coordinates.size >= 2) {
                        val lon = feature.geometry.coordinates[0]
                        val lat = feature.geometry.coordinates[1]
                        val id = feature.properties?.get("id") ?: "council_${lat}_${lon}"
                        val operator = feature.properties?.get("operator") ?: "Council CCTV"
                        val type = feature.properties?.get("type") ?: "Surveillance"
                        CameraEntity(
                            id = id,
                            lat = lat,
                            lon = lon,
                            title = "Council CCTV Camera",
                            operator = operator,
                            type = type,
                            source = "COUNCIL"
                        )
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
