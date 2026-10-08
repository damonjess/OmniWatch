package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Validates the filtering of OpenStreetMap `contact:webcam` values before they reach a WebView.
 * These are real values taken from the OpenStreetMap nodes the app ingested.
 */
class WebcamPagesTest {

    @Test
    fun `an https page is passed through unchanged`() {
        assertEquals(
            "https://camsecure.co/httpswebcam/cyc/cyc.html",
            WebcamPages.embeddableUrl("https://camsecure.co/httpswebcam/cyc/cyc.html"),
        )
    }

    @Test
    fun `a cleartext snapshot URL is upgraded to https`() {
        assertEquals(
            "https://data.nottinghamtravelwise.org.uk/images152.jpg",
            WebcamPages.embeddableUrl("http://data.nottinghamtravelwise.org.uk/images152.jpg"),
        )
    }

    @Test
    fun `query strings and fragments survive the upgrade`() {
        assertEquals(
            "https://example.org/cam?id=7#live",
            WebcamPages.embeddableUrl("http://example.org/cam?id=7#live"),
        )
    }

    @Test
    fun `a mistyped scheme is rejected rather than loaded`() {
        assertNull(WebcamPages.embeddableUrl("hhttp://data.nottinghamtravelwise.org.uk/images127.jpg"))
    }

    @Test
    fun `a value that is not a URL at all is rejected`() {
        // OSM nodes sometimes carry a hardware model in contact:webcam.
        assertNull(WebcamPages.embeddableUrl("CPE510"))
        assertNull(WebcamPages.embeddableUrl("camera 4"))
    }

    @Test
    fun `blank and missing values are rejected`() {
        assertNull(WebcamPages.embeddableUrl(null))
        assertNull(WebcamPages.embeddableUrl(""))
        assertNull(WebcamPages.embeddableUrl("   "))
    }

    @Test
    fun `surrounding whitespace and an upper case scheme are handled`() {
        assertEquals("https://example.org/live", WebcamPages.embeddableUrl("  https://example.org/live  "))
        assertEquals("https://example.org/live", WebcamPages.embeddableUrl("HTTP://example.org/live"))
    }

    @Test
    fun `other schemes are rejected`() {
        assertNull(WebcamPages.embeddableUrl("rtsp://192.168.0.10/stream"))
        assertNull(WebcamPages.embeddableUrl("ftp://example.org/cam.mjpg"))
    }
}
