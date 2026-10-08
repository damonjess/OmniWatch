package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the SkylineWebcams playlist resolution used for public webcams.
 *
 * The stream URL carries a short-lived token, so only the parsing and URL construction are
 * unit tested here; the live fetch itself is verified on a device.
 */
class PublicWebcamStreamsTest {

    private val pageHtml = """
        <html><head><base href="https://www.skylinewebcams.com/"></head><body>
        <script>
        var player=new Clappr.Player({hideVolumeBar:true,autoPlay:true,
        source:'livee.m3u8?a=abe4mag2ufpt7pk54ki3fj0ap4',persistConfig:true});
        </script></body></html>
    """.trimIndent()

    @Test
    fun `clappr source is rewritten to the signed hd-auth playlist`() {
        assertEquals(
            "https://hd-auth.skylinewebcams.com/live.m3u8?a=abe4mag2ufpt7pk54ki3fj0ap4",
            PublicWebcamStreams.extractPlaylistUrl(pageHtml),
        )
    }

    @Test
    fun `double quoted and leading slash sources resolve to the same playlist`() {
        val doubleQuoted = """<script>player={source:"livee.m3u8?a=token123"};</script>"""
        assertEquals(
            "https://hd-auth.skylinewebcams.com/live.m3u8?a=token123",
            PublicWebcamStreams.extractPlaylistUrl(doubleQuoted),
        )

        val leadingSlash = """<script>url: '/livee.m3u8?a=token456'</script>"""
        assertEquals(
            "https://hd-auth.skylinewebcams.com/live.m3u8?a=token456",
            PublicWebcamStreams.extractPlaylistUrl(leadingSlash),
        )
    }

    @Test
    fun `an absolute signed playlist is used as is`() {
        val absolute = """<script>url: "https://hd-auth.skylinewebcams.com/live.m3u8?a=zzz"</script>"""
        assertEquals(
            "https://hd-auth.skylinewebcams.com/live.m3u8?a=zzz",
            PublicWebcamStreams.extractPlaylistUrl(absolute),
        )
    }

    @Test
    fun `pages without a playlist resolve to null`() {
        assertNull(PublicWebcamStreams.extractPlaylistUrl("<html><body>Camera offline</body></html>"))
        assertNull(PublicWebcamStreams.extractPlaylistUrl("""<script>source:'promo.mp4'</script>"""))
    }

    @Test
    fun `skyline pages are recognised by host`() {
        assertTrue(
            PublicWebcamStreams.isSkylinePage(
                "https://www.skylinewebcams.com/en/webcam/united-kingdom/england/blackpool/blackpool.html"
            )
        )
        assertFalse(PublicWebcamStreams.isSkylinePage("https://www.youtube.com/watch?v=H3A2RSYTzdI"))
        assertFalse(PublicWebcamStreams.isSkylinePage(null))
    }
}
