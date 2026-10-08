package com.example.omniwatch

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrafficVisionDataLoaderTest {
    private data class Record(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val imageUrl: String = "",
        val streamUrl: String = "",
        val playerUrl: String = "",
        val feedType: String = "",
        val country: String = "",
        // Absent for every record that is not a YouTube feed, so it arrives as null.
        val youtubeVideoId: String? = null,
    )

    private fun loadRecords(): List<Record> {
        val asset = listOf(
            File("src/main/assets/trafficvision_uk_ie.json"),
            File("app/src/main/assets/trafficvision_uk_ie.json"),
        ).first { it.exists() }
        val type = object : TypeToken<List<Record>>() {}.type
        return Gson().fromJson(asset.readText(), type)
    }

    @Test
    fun `bundled TrafficVision catalogue contains only UK and Ireland records`() {
        val records = loadRecords()

        assertTrue("TrafficVision UK/Ireland catalogue should be large", records.size > 5000)
        assertTrue(records.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        assertTrue(records.all { it.imageUrl.isNotBlank() || it.streamUrl.isNotBlank() || it.playerUrl.isNotBlank() })
        assertTrue(records.all { it.country == "United Kingdom" || it.country.contains("Ireland", ignoreCase = true) })
    }

    /**
     * The first TrafficVision import dropped the catalogue's video and YouTube fields, so every
     * marker opened a still image. The bundled catalogue has to keep the playback URLs.
     */
    @Test
    fun `bundled TrafficVision catalogue carries playable video streams`() {
        val records = loadRecords()
        val withVideo = records.filter { it.streamUrl.isNotBlank() }

        assertTrue("Expected many TrafficVision cameras with a direct video stream", withVideo.size > 500)
        assertTrue(
            "Every video stream should be HLS or MP4",
            withVideo.all {
                it.streamUrl.contains(".m3u8", ignoreCase = true) ||
                    it.streamUrl.contains(".mp4", ignoreCase = true)
            },
        )
        assertTrue(
            "Hybrid and video feeds should resolve to a direct stream",
            records.filter { it.feedType == "hybrid" || it.feedType == "video" }
                .all { it.streamUrl.isNotBlank() },
        )
        assertTrue(
            "YouTube cameras should keep their video id",
            records.any { !it.youtubeVideoId.isNullOrBlank() },
        )
    }
}
