package com.example.omniwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerStyleTest {

    private fun node(
        source: String,
        streamUrl: String? = null,
        streamType: String? = null,
        imageUrl: String? = null,
        tags: Map<String, String> = emptyMap(),
    ) = CctvNode(
        lat = 0.0,
        lon = 0.0,
        titleStr = "cam",
        source = source,
        imageUrl = imageUrl,
        streamUrl = streamUrl,
        streamType = streamType,
        tags = tags,
    )

    @Test
    fun curatedWebcamsAndInsecamAreLiveVideo() {
        assertTrue(MarkerStyle.hasLiveVideo(node(PublicWebcamDataLoader.SOURCE_PUBLIC_WEBCAM)))
        assertTrue(MarkerStyle.hasLiveVideo(node(PublicWebcamDataLoader.SOURCE_INSECAM)))
    }

    @Test
    fun trafficVisionIsLiveVideoUnlessImageOnly() {
        val source = TrafficVisionDataLoader.SOURCE_TRAFFICVISION
        assertFalse(MarkerStyle.hasLiveVideo(node(source, imageUrl = "https://x/a.jpg", tags = mapOf("feedType" to "image"))))
        assertFalse(MarkerStyle.hasLiveVideo(node(source, imageUrl = "https://x/a.jpg")))
        for (feedType in listOf("hybrid", "video", "youtube", "ipcamlive-iframe", "embed-iframe", "rtspme-iframe")) {
            assertTrue(feedType, MarkerStyle.hasLiveVideo(node(source, tags = mapOf("feedType" to feedType))))
        }
    }

    @Test
    fun otherSourcesNeedARecognisedStream() {
        assertTrue(MarkerStyle.hasLiveVideo(node("TFL", streamUrl = "https://x/cam.m3u8", streamType = "HLS")))
        assertTrue(MarkerStyle.hasLiveVideo(node("OVERPASS", streamUrl = "https://www.youtube.com/watch?v=H3A2RSYTzdI")))
        assertFalse(MarkerStyle.hasLiveVideo(node("National Highways", imageUrl = "https://x/a.jpg")))
        assertFalse(MarkerStyle.hasLiveVideo(node("OVERPASS", streamUrl = "https://example.com/some-page.html")))
        assertFalse(MarkerStyle.hasLiveVideo(node("OVERPASS")))
    }
}
