package com.example.omniwatch

import android.content.Context
import com.example.omniwatch.data.db.CameraEntity
import com.example.omniwatch.data.db.CameraTags
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Loads the filtered UK and Ireland catalogue exported from TrafficVision.Live. */
object TrafficVisionDataLoader {
    const val SOURCE_TRAFFICVISION = "TrafficVision"
    private const val DEFAULT_ASSET = "trafficvision_uk_ie.json"

    private data class Record(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val operator: String,
        val imageUrl: String = "",
        val streamUrl: String = "",
        val playerUrl: String = "",
        val feedType: String = "",
        val country: String = "",
        val source: String = "",
        // Optional: Gson leaves absent keys null, and most records are not YouTube feeds.
        val youtubeVideoId: String? = null,
        val tags: Map<String, String> = emptyMap(),
    )

    fun loadFromAssets(context: Context, fileName: String = DEFAULT_ASSET): List<CameraEntity> = runCatching {
        val type = object : TypeToken<List<Record>>() {}.type
        val records: List<Record> = context.assets.open(fileName).use { input ->
            Gson().fromJson(input.reader(), type) ?: emptyList()
        }
        records.mapNotNull { record ->
            if (record.id.isBlank() || record.name.isBlank()) return@mapNotNull null
            if (record.latitude !in -90.0..90.0 || record.longitude !in -180.0..180.0) return@mapNotNull null
            if (record.imageUrl.isBlank() && record.streamUrl.isBlank() && record.playerUrl.isBlank()) return@mapNotNull null

            val tags = record.tags.toMutableMap()
            if (record.imageUrl.isNotBlank()) tags["liveImageUrl"] = record.imageUrl
            if (record.streamUrl.isNotBlank()) {
                tags["streamUrl"] = record.streamUrl
                // Stated explicitly so the player picks HLS or progressive MP4 from the catalogue
                // rather than guessing from a URL that may hide its type behind a query string.
                tags["streamType"] = CameraStreams.kind(record.streamUrl)
            }
            if (record.playerUrl.isNotBlank()) tags["playerUrl"] = record.playerUrl
            val youtubeVideoId = record.youtubeVideoId.orEmpty()
            if (youtubeVideoId.isNotBlank()) {
                tags["youtubeVideoId"] = youtubeVideoId
                tags["websiteUrl"] = "https://www.youtube.com/watch?v=$youtubeVideoId"
            }
            // A player page is the fallback for anything that is not a direct media stream; the
            // stream URL is deliberately excluded now that it can hold a playlist or MP4 file.
            val webUrl = record.playerUrl.ifBlank { tags["sourceUrl"].orEmpty() }
            if (webUrl.isNotBlank() && tags["websiteUrl"].isNullOrBlank()) tags["websiteUrl"] = webUrl
            if (record.feedType.isNotBlank()) tags["feedType"] = record.feedType
            if (record.country.isNotBlank()) tags["country"] = record.country
            if (record.source.isNotBlank()) tags["sourceNetwork"] = record.source

            CameraEntity(
                id = "trafficvision_${record.id}",
                lat = record.latitude,
                lon = record.longitude,
                title = record.name,
                operator = record.operator.ifBlank { "TrafficVision" },
                type = "Public Traffic Camera",
                source = SOURCE_TRAFFICVISION,
                tagsJson = CameraTags.encode(tags),
                isTrafficCamera = true,
            )
        }
    }.getOrDefault(emptyList())
}
