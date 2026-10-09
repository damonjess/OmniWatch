package com.example.omniwatch

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
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

        assertEquals("Expected 34 webcams in expanded catalogue", 34, records.size)

        val ids = records.map { it.id }
        assertTrue("Eernewoude HLS webcam missing", ids.contains("public-webcam-eernewoude-princenhof"))
        assertTrue("Ramsgate Harbour webcam missing", ids.contains("public-webcam-ramsgate-royal-harbour"))
        assertTrue("Lyme Regis Harbour webcam missing", ids.contains("public-webcam-lyme-regis-seafront"))
        assertTrue("CMAL Scotland Harbours webcam missing", ids.contains("public-webcam-cmal-scotland-harbours"))
        assertTrue("Blackpool webcam missing", ids.contains("public-webcam-blackpool-central-pier"))
        assertTrue("St Ives webcam missing", ids.contains("public-webcam-st-ives-harbour"))
        assertTrue("Dover webcam missing", ids.contains("public-webcam-dover-beach-kent"))
        assertTrue("Brighton webcam missing", ids.contains("public-webcam-brighton-pier"))
        assertTrue("Deal webcam missing", ids.contains("public-webcam-deal-beach-kent"))
        assertTrue("Barmouth webcam missing", ids.contains("public-webcam-barmouth-harbour"))
        assertTrue("Bala Lake webcam missing", ids.contains("public-webcam-bala-lake"))
        assertTrue("Porlock Weir webcam missing", ids.contains("public-webcam-porlock-weir"))
        assertTrue("Hereford webcam missing", ids.contains("public-webcam-hereford-high-town"))
        assertTrue("Cardiff webcam missing", ids.contains("public-webcam-cardiff-st-mary-street"))
        assertTrue("St Ives Porthmeor webcam missing", ids.contains("public-webcam-st-ives-porthmeor-beach"))
        assertTrue("Newport Pembrokeshire webcam missing", ids.contains("public-webcam-newport-pembrokeshire"))
        assertTrue("Barmouth Bay webcam missing", ids.contains("public-webcam-barmouth-bay-llanaber"))
        assertTrue("Barmouth Beach webcam missing", ids.contains("public-webcam-barmouth-beach"))
        assertTrue("Hawes webcam missing", ids.contains("public-webcam-hawes-yorkshire-dales"))
        assertTrue("River Wye webcam missing", ids.contains("public-webcam-river-wye-hereford"))
        assertTrue("Sutton Coldfield webcam missing", ids.contains("public-webcam-sutton-coldfield"))
        assertTrue("Bala Gwynedd webcam missing", ids.contains("public-webcam-bala-gwynedd"))
        assertTrue("Insecam London webcam missing", ids.contains("insecam-gb-1011059"))

        // SkylineWebcams hides its playlist behind a page, so these entries must stay marked
        // SKYLINE with the operator page as the stream URL for the resolver to find a feed.
        val skyline = records.filter { it.streamType.equals("SKYLINE", ignoreCase = true) }
        assertEquals("Expected 18 SkylineWebcams entries marked SKYLINE", 18, skyline.size)
        skyline.forEach { record ->
            assertTrue(
                "Skyline entry ${record.id} must point at a resolvable operator page",
                record.streamUrl.contains("skylinewebcams.com", ignoreCase = true)
            )
        }

        records.forEach { record ->
            assertTrue("Invalid latitude for ${record.id}", record.latitude in -90.0..90.0)
            assertTrue("Invalid longitude for ${record.id}", record.longitude in -180.0..180.0)
            assertTrue("Name missing for ${record.id}", record.name.isNotBlank())
            assertTrue("Stream or website URL missing for ${record.id}", record.streamUrl.isNotBlank() || record.websiteUrl.isNotBlank())
        }
    }
}
