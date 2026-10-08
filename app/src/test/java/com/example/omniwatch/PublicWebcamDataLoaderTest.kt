package com.example.omniwatch

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates public webcams JSON schema and dataset.
 */
class PublicWebcamDataLoaderTest {

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

    @Test
    fun `bundled public_webcams json exists and parses correctly`() {
        val asset = listOf(
            File("src/main/assets/public_webcams.json"),
            File("app/src/main/assets/public_webcams.json"),
        ).firstOrNull { it.exists() }

        assertTrue("bundled public_webcams.json not found", asset != null)

        val type = object : TypeToken<List<WebcamRecord>>() {}.type
        val records: List<WebcamRecord> = Gson().fromJson(requireNotNull(asset).readText(), type)

        assertTrue("public_webcams.json should contain records", records.isNotEmpty())

        val ids = records.map { it.id }
        assertTrue("Eernewoude HLS webcam missing", ids.contains("public-webcam-eernewoude-princenhof"))
        assertTrue("Ramsgate Harbour webcam missing", ids.contains("public-webcam-ramsgate-royal-harbour"))
        assertTrue("Lyme Regis Harbour webcam missing", ids.contains("public-webcam-lyme-regis-seafront"))
        assertTrue("CMAL Scotland Harbours webcam missing", ids.contains("public-webcam-cmal-scotland-harbours"))

        records.forEach { record ->
            assertTrue("Invalid latitude for ${record.id}", record.latitude in -90.0..90.0)
            assertTrue("Invalid longitude for ${record.id}", record.longitude in -180.0..180.0)
            assertTrue("Name missing for ${record.id}", record.name.isNotBlank())
            assertTrue("Stream or website URL missing for ${record.id}", record.streamUrl.isNotBlank() || record.websiteUrl.isNotBlank())
        }
    }
}
