package com.example.omniwatch.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.omniwatch.CctvNode
import com.example.omniwatch.CouncilDataLoader
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
    val source: String, // where the record came from, e.g. "OVERPASS", "TRAFFIC", "COUNCIL"
    // Every tag the source published, as JSON. Kept as a blob so any new OSM tag shows up
    // in the detail sheet without a schema change.
    val tagsJson: String? = null,
    val isTrafficCamera: Boolean = false
) {
    fun toNode(): CctvNode {
        val tagsMap = CameraTags.decode(tagsJson)
        val liveImg = tagsMap["liveImageUrl"] ?: tagsMap["imageUrl"] ?: tagsMap["image"] ?: tagsMap["url"]
        val streamUrl = tagsMap["streamUrl"] ?: tagsMap["playerUrl"] ?: tagsMap["contact:webcam"]
        val streamType = tagsMap["streamType"] ?: if (streamUrl?.contains("m3u8", ignoreCase = true) == true) "HLS" else "WEB"
        val websiteUrl = tagsMap["websiteUrl"] ?: tagsMap["playerUrl"] ?: tagsMap["website"] ?: tagsMap["contact:website"] ?: tagsMap["url"]
        val isTraffic = isTrafficCamera ||
                source == "TRAFFIC" ||
                source == "National Highways" ||
                source == "TFL" ||
                type.equals("Traffic Camera", ignoreCase = true) ||
                type.equals("Motorway Camera", ignoreCase = true) ||
                tagsMap["highway"] == "speed_camera" ||
                tagsMap["highway"] == "enforcement" ||
                tagsMap["surveillance:type"]?.contains("traffic", ignoreCase = true) == true

        val isWebcam = source == "PUBLIC_WEBCAM" ||
                tagsMap["man_made"] == "webcam" ||
                tagsMap["contact:webcam"] != null ||
                !tagsMap["playerUrl"].isNullOrBlank()

        return CctvNode(
            lat = lat,
            lon = lon,
            titleStr = title,
            source = source,
            operator = operator,
            type = type,
            imageUrl = liveImg,
            streamUrl = streamUrl,
            streamType = streamType,
            websiteUrl = websiteUrl,
            tags = tagsMap,
            isCouncil = source == CouncilDataLoader.SOURCE_COUNCIL,
            isTrafficCamera = isTraffic,
            isWebcam = isWebcam,
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
