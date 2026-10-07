package com.example.omniwatch.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.omniwatch.CctvClusterItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey
    val id: String,
    val lat: Double,
    val lon: Double,
    val title: String,
    val operator: String,
    val type: String,
    val source: String, // where the record came from, e.g. "OVERPASS"
    // Every tag the source published, as JSON. Kept as a blob so any new OSM tag shows up
    // in the detail sheet without a schema change.
    val tagsJson: String? = null
) {
    fun toClusterItem(): CctvClusterItem {
        return CctvClusterItem(
            lat = lat,
            lon = lon,
            titleStr = title,
            source = source,
            operator = operator,
            type = type,
            tags = CameraTags.decode(tagsJson)
        )
    }
}

/** Encodes/decodes the raw tag map that backs [CameraEntity.tagsJson]. */
internal object CameraTags {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    fun encode(tags: Map<String, String>): String = gson.toJson(tags)

    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { gson.fromJson<Map<String, String>>(json, mapType) }.getOrNull().orEmpty()
    }
}
