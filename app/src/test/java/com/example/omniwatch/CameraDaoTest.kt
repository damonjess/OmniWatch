package com.example.omniwatch

import com.example.omniwatch.data.db.CameraEntity
import com.example.omniwatch.data.db.CameraTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDaoTest {

    @Test
    fun testViewportContainment() {
        val cameraIn = CameraEntity(
            id = "osm_1",
            lat = 53.585,
            lon = -0.655,
            title = "In Viewport",
            operator = "Op",
            type = "type",
            source = "OVERPASS",
            tagsJson = CameraTags.encode(emptyMap()),
            isTrafficCamera = false
        )

        val cameraOut = CameraEntity(
            id = "osm_2",
            lat = 54.0,
            lon = -1.0,
            title = "Out Viewport",
            operator = "Op",
            type = "type",
            source = "OVERPASS",
            tagsJson = CameraTags.encode(emptyMap()),
            isTrafficCamera = false
        )

        val bounds = ViewportBounds(north = 53.72, east = -0.44, south = 53.39, west = -0.82)

        assertTrue(bounds.contains(cameraIn.lat, cameraIn.lon))
        assertTrue(!bounds.contains(cameraOut.lat, cameraOut.lon))
    }
}
