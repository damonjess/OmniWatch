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
        val country: String = "",
    )

    @Test
    fun `bundled TrafficVision catalogue contains only UK and Ireland records`() {
        val asset = listOf(
            File("src/main/assets/trafficvision_uk_ie.json"),
            File("app/src/main/assets/trafficvision_uk_ie.json"),
        ).first { it.exists() }
        val type = object : TypeToken<List<Record>>() {}.type
        val records: List<Record> = Gson().fromJson(asset.readText(), type)

        assertTrue("TrafficVision UK/Ireland catalogue should be large", records.size > 5000)
        assertTrue(records.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        assertTrue(records.all { it.imageUrl.isNotBlank() || it.streamUrl.isNotBlank() || it.playerUrl.isNotBlank() })
        assertTrue(records.all { it.country == "United Kingdom" || it.country.contains("Ireland", ignoreCase = true) })
    }
}
