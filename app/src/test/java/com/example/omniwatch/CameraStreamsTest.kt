package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates how a camera stream URL is routed: direct HLS, progressive MP4, or an operator page.
 * The sample URLs are the shapes TrafficVision actually publishes.
 */
class CameraStreamsTest {

    @Test
    fun `hls playlist is a direct video`() {
        assertEquals(
            CameraStreams.HLS,
            CameraStreams.kind("https://media.trafficvision.live/earthcam/master.m3u8?u=https%3A%2F%2Fx"),
        )
        assertTrue(CameraStreams.isDirectVideo("https://camsecure.co/HLS/pentreplaylistz.m3u8"))
    }

    @Test
    fun `a declared hls type wins over an opaque url`() {
        assertEquals(CameraStreams.HLS, CameraStreams.kind("https://relay.example.com/live?token=abc", "HLS"))
    }

    @Test
    fun `progressive mp4 is a direct video`() {
        assertEquals(
            CameraStreams.MP4,
            CameraStreams.kind("https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.01251.mp4"),
        )
        assertTrue(CameraStreams.isDirectVideo("https://example.org/clip.MP4"))
    }

    @Test
    fun `a declared mp4 type is honoured`() {
        assertEquals(CameraStreams.MP4, CameraStreams.kind("https://example.org/stream", "MP4"))
    }

    @Test
    fun `an operator player page is not a direct video`() {
        assertEquals(
            CameraStreams.PAGE,
            CameraStreams.kind("https://g1.ipcamlive.com/player/player.php?alias=6745eb6be37dd"),
        )
        assertFalse(CameraStreams.isDirectVideo("https://player.twitch.tv/?channel=cmalkennacraig"))
        assertFalse(CameraStreams.isDirectVideo("https://www.skylinewebcams.com/en/webcam/blackpool.html"))
    }

    @Test
    fun `blank or missing urls are treated as pages`() {
        assertEquals(CameraStreams.PAGE, CameraStreams.kind(null))
        assertEquals(CameraStreams.PAGE, CameraStreams.kind("   "))
        assertFalse(CameraStreams.isDirectVideo(null))
    }
}
