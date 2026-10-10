package com.example.omniwatch

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertEquals("Expected 57 webcams in expanded catalogue", 57, records.size)

        val ids = records.map { it.id }
        val ukInsecamIds = listOf(
            "1012698", "1012559", "1012453", "1012256", "1012235", "1012127",
            "1011726", "1011654", "1011555", "1011197", "1011059", "1010292",
            "964411", "964408", "932345", "906603", "873170", "850577",
            "809464", "551005", "257932", "251593", "237453",
        ).map { "insecam-gb-$it" }
        assertEquals("Expected 23 UK Insecam entries", 23, ids.count { it in ukInsecamIds })
        assertTrue("UK Insecam catalogue incomplete", ids.containsAll(ukInsecamIds))
        assertFalse("Stale Birmingham Insecam listing must not be included", ids.contains("insecam-gb-964281"))
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
        val londonTraffic = records.first { it.id == "webcamtaxi-london-traffic" }
        assertEquals("MP4", londonTraffic.streamType)
        assertTrue(londonTraffic.streamUrl.endsWith("00001.02500.mp4"))
        val londonUnderground = records.first { it.id == "webcamtaxi-london-underground" }
        assertEquals("YOUTUBE", londonUnderground.streamType)
        assertTrue(londonUnderground.streamUrl.contains("/channel/UCmEC5XS9Ol-6jqBaKpzqehQ/live"))
        val londonBusTour = records.first { it.id == "webcamtaxi-london-bus-tour" }
        assertEquals("YOUTUBE", londonBusTour.streamType)
        assertTrue(londonBusTour.streamUrl.endsWith("watch?v=wDaV8EkYHmk"))
        val firstUkBatch = records.filter { it.id in setOf(
            "webcamtaxi-university-oxford",
            "webcamtaxi-penybanc-ammanford",
            "webcamtaxi-polzeath-beach",
            "webcamtaxi-shepards-wharf-cowes",
        ) }
        assertEquals(4, firstUkBatch.size)
        assertTrue(firstUkBatch.all { it.streamType == "YOUTUBE" })
        assertTrue(firstUkBatch.all { it.websiteUrl.startsWith("https://www.webcamtaxi.com/en/") })
        val secondUkBatch = records.filter { it.id in setOf(
            "webcamtaxi-aberdour",
            "webcamtaxi-river-teign-teignmouth",
            "webcamtaxi-bridport-harbour",
            "webcamtaxi-york-railway-station",
            "webcamtaxi-saundersfoot-beach-harbour",
        ) }
        assertEquals(5, secondUkBatch.size)
        assertTrue(secondUkBatch.all { it.streamType == "YOUTUBE" })
        assertTrue(secondUkBatch.all { it.websiteUrl.startsWith("https://www.webcamtaxi.com/en/") })
        assertTrue("Insecam Birmingham webcam missing", ids.contains("insecam-gb-1011059"))
        assertTrue("Insecam London webcam missing", ids.contains("insecam-gb-1012453"))
        val birminghamInsecam = records.first { it.id == "insecam-gb-1011059" }
        assertEquals(52.48142, birminghamInsecam.latitude, 0.000001)
        assertEquals(-1.89983, birminghamInsecam.longitude, 0.000001)
        assertEquals("Insecam Axis Camera #1011059 (Birmingham)", birminghamInsecam.name)
        assertEquals("http://87.74.69.86:80/mjpg/video.mjpg", birminghamInsecam.streamUrl)
        assertEquals("http://www.insecam.org/en/view/1011059/", birminghamInsecam.websiteUrl)
        val londonInsecam = records.first { it.id == "insecam-gb-1012453" }
        assertEquals(51.5074, londonInsecam.latitude, 0.000001)
        assertEquals(-0.1278, londonInsecam.longitude, 0.000001)
        assertEquals("http://www.insecam.org/en/view/1012453/", londonInsecam.streamUrl)

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
