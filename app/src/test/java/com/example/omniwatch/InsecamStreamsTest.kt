package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates Insecam stream URL resolution and regex parsing.
 */
class InsecamStreamsTest {

    @Test
    fun `extracts stream URL when id precedes src`() {
        val html = """
            <html><body>
            <div class="image-container">
            <img id="image0" class="img-responsive" src="http://192.168.1.100:8080/mjpg/video.mjpg" alt="Live Camera" />
            </div>
            </body></html>
        """.trimIndent()

        assertEquals(
            "http://192.168.1.100:8080/mjpg/video.mjpg",
            InsecamStreams.extractStreamUrl(html),
        )
    }

    @Test
    fun `extracts stream URL when src precedes id`() {
        val html = """
            <html><body>
            <img class="camera-feed" src="http://camera.example.com/cgi-bin/faststream.jpg?camera=1" id="image0" />
            </body></html>
        """.trimIndent()

        assertEquals(
            "http://camera.example.com/cgi-bin/faststream.jpg?camera=1",
            InsecamStreams.extractStreamUrl(html),
        )
    }

    @Test
    fun `decodes html entities in snapshot camera URLs`() {
        val html = """<img id="image0" src="http://camera.example.com/SnapshotJPEG?Quality=Clarity&amp;COUNTER">"""

        assertEquals(
            "http://camera.example.com/SnapshotJPEG?Quality=Clarity&COUNTER",
            InsecamStreams.extractStreamUrl(html),
        )
    }

    @Test
    fun `returns null when no camera image tag is present`() {
        val html = "<html><body><h1>Camera Offline</h1></body></html>"
        assertNull(InsecamStreams.extractStreamUrl(html))
    }

    @Test
    fun `uses direct camera stream URLs without resolving an Insecam page`() = kotlinx.coroutines.runBlocking {
        val url = "http://87.74.69.86:80/mjpg/video.mjpg"

        assertEquals(url, InsecamStreams.resolveStreamUrl(url))
    }

    @Test
    fun `recognises insecam pages by host`() {
        assertTrue(InsecamStreams.isInsecamPage("http://www.insecam.org/en/view/1011059/"))
        assertTrue(InsecamStreams.isInsecamPage("https://insecam.org/en/bycountry/GB/"))
        assertFalse(InsecamStreams.isInsecamPage("https://www.skylinewebcams.com/en/webcam/blackpool.html"))
        assertFalse(InsecamStreams.isInsecamPage(null))
    }

    @Test
    @org.junit.Ignore("Live network test")
    fun `live insecam page resolution check`() = kotlinx.coroutines.runBlocking {
        val url = InsecamStreams.resolveStreamUrl("http://www.insecam.org/en/view/1011059/")
        assertTrue(url != null)
    }
}
