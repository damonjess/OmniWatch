package com.example.omniwatch

import android.content.Context
import com.example.omniwatch.data.db.CameraEntity
import com.example.omniwatch.data.db.CameraTags
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Loads explicitly curated public webcam records shipped with the app. */
object PublicWebcamDataLoader {
    const val SOURCE_PUBLIC_WEBCAM = "PUBLIC_WEBCAM"
    const val SOURCE_INSECAM = "INSECAM"
    private const val DEFAULT_ASSET = "public_webcams.json"

    private data class WebcamRecord(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val operator: String,
        val streamUrl: String,
        val streamType: String,
        val websiteUrl: String,
    )

    fun loadFromAssets(context: Context, fileName: String = DEFAULT_ASSET): List<CameraEntity> = runCatching {
        val type = object : TypeToken<List<WebcamRecord>>() {}.type
        val records: List<WebcamRecord> = context.assets.open(fileName).use { input ->
            Gson().fromJson(input.reader(), type) ?: emptyList()
        }
        records.mapNotNull { record ->
            if (record.id.isBlank() || record.name.isBlank() || record.streamUrl.isBlank()) return@mapNotNull null
            if (record.latitude !in -90.0..90.0 || record.longitude !in -180.0..180.0) return@mapNotNull null
            val sourceName = if (record.streamType.equals("INSECAM", ignoreCase = true) || record.operator.equals("Insecam", ignoreCase = true)) {
                SOURCE_INSECAM
            } else {
                SOURCE_PUBLIC_WEBCAM
            }
            CameraEntity(
                id = record.id,
                lat = record.latitude,
                lon = record.longitude,
                title = record.name,
                operator = record.operator,
                type = "Public Webcam",
                source = sourceName,
                tagsJson = CameraTags.encode(
                    mapOf(
                        "streamUrl" to record.streamUrl,
                        "streamType" to record.streamType,
                        "websiteUrl" to record.websiteUrl,
                    )
                ),
            )
        }
    }.getOrDefault(emptyList())
}
