package com.example.omniwatch

import com.example.omniwatch.data.db.CameraEntity
import com.example.omniwatch.data.db.CameraTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraEntityTest {

    @Test
    fun `national highways camera correctly maps to traffic node`() {
        val camera = CameraEntity(
            id = "nh_101",
            lat = 53.58,
            lon = -0.65,
            title = "M180 J2 Camera",
            operator = "National Highways",
            type = "Motorway Camera",
            source = "TRAFFIC",
            tagsJson = CameraTags.encode(mapOf("liveImageUrl" to "https://example.com/cam.jpg")),
            isTrafficCamera = true,
        )

        val node = camera.toNode()

        assertTrue(node.isTrafficCamera)
        assertEquals("https://example.com/cam.jpg", node.imageUrl)
        assertEquals("National Highways", node.operator)
        assertEquals("Motorway Camera", node.type)
    }

    @Test
    fun `overpass speed camera is classified as traffic camera`() {
        val camera = CameraEntity(
            id = "osm_12345",
            lat = 53.59,
            lon = -0.64,
            title = "Speed Camera A15",
            operator = "Humberside Police",
            type = "speed_camera",
            source = "OVERPASS",
            tagsJson = CameraTags.encode(mapOf("highway" to "speed_camera")),
            isTrafficCamera = true,
        )

        val node = camera.toNode()

        assertTrue(node.isTrafficCamera)
    }

    @Test
    fun `trafficvision hybrid camera maps to a playable hls node`() {
        val camera = CameraEntity(
            id = "trafficvision_earthcam-0e8ba55957cce32c61b35cab363e93dc",
            lat = 53.345546,
            lon = -6.264545,
            title = "Dublin Cam",
            operator = "TrafficVision / earthcam",
            type = "Public Traffic Camera",
            source = "TrafficVision",
            tagsJson = CameraTags.encode(
                mapOf(
                    "liveImageUrl" to "https://www.earthcam.com/cams/includes/image.php?img=x",
                    "streamUrl" to "https://media.trafficvision.live/earthcam/master.m3u8?u=x",
                    "streamType" to "HLS",
                )
            ),
            isTrafficCamera = true,
        )

        val node = camera.toNode()

        assertEquals("HLS", node.streamType)
        assertTrue(CameraStreams.isDirectVideo(node.streamUrl, node.streamType))
    }
}
