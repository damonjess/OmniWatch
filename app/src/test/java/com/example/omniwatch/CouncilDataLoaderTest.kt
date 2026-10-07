package com.example.omniwatch

import com.example.omniwatch.data.db.CameraTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the council GeoJSON import. Council open-data exports do not share one schema, so the
 * important behaviour here is that a real download still produces placeable cameras.
 */
class CouncilDataLoaderTest {

    @Test
    fun `reads a council export that uses its own field names`() {
        // Shaped like an ArcGIS/data.gov.uk export: different key names, numeric and boolean
        // properties, and a CRS block the loader must ignore.
        val geoJson = """
            {
              "type": "FeatureCollection",
              "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
              "features": [
                {
                  "type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [-0.6553, 53.5851]},
                  "properties": {
                    "camera_id": "NLC-001",
                    "location": "High Street, Scunthorpe",
                    "organisation": "North Lincolnshire Council",
                    "camera_type": "PTZ",
                    "fid": 1234,
                    "easting": 489000.0,
                    "in_service": true
                  }
                }
              ]
            }
        """.trimIndent()

        val cameras = CouncilDataLoader.parseJson(geoJson)

        assertEquals(1, cameras.size)
        val camera = cameras.single()
        assertEquals("NLC-001", camera.id)
        assertEquals(53.5851, camera.lat, 1e-9)
        assertEquals(-0.6553, camera.lon, 1e-9)
        assertEquals("High Street, Scunthorpe", camera.title)
        assertEquals("North Lincolnshire Council", camera.operator)
        assertEquals("PTZ", camera.type)
        assertEquals(CouncilDataLoader.SOURCE_COUNCIL, camera.source)

        // Numbers and booleans arrive as strings, with whole numbers not showing a ".0".
        val attributes = CameraTags.decode(camera.tagsJson)
        assertEquals("1234", attributes["fid"])
        assertEquals("489000", attributes["easting"])
        assertEquals("true", attributes["in_service"])
    }

    @Test
    fun `bundled dataset still parses`() {
        // Guards the file that actually ships: renaming or reshaping it must not break the import.
        val asset = listOf(
            File("src/main/assets/council_cctv.geojson"),
            File("app/src/main/assets/council_cctv.geojson"),
        ).firstOrNull { it.exists() }

        assertTrue("bundled council_cctv.geojson not found", asset != null)

        val cameras = CouncilDataLoader.parseJson(requireNotNull(asset).readText())

        assertTrue("bundled dataset produced no cameras", cameras.isNotEmpty())
        assertTrue(cameras.all { it.source == CouncilDataLoader.SOURCE_COUNCIL })
        assertTrue(cameras.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
    }

    @Test
    fun `falls back to defaults when the export carries no properties`() {
        val geoJson = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-0.65,53.58]}}
            ]}
        """.trimIndent()

        val camera = CouncilDataLoader.parseJson(geoJson).single()

        assertEquals("Council CCTV Camera", camera.title)
        assertEquals("Council", camera.operator)
        assertEquals("Surveillance", camera.type)
        assertEquals("council_53.58_-0.65", camera.id)
    }

    @Test
    fun `skips features that cannot be placed on the map`() {
        // A line geometry, a broken coordinate, a point with no geometry and one missing its
        // properties: none of these may become a stray marker, and none may throw.
        val geoJson = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]},
               "properties":{"id":"line"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[999.0,53.5]},
               "properties":{"id":"bad_lon"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-0.6,95.0]},
               "properties":{"id":"bad_lat"}},
              {"type":"Feature","properties":{"id":"no_geometry"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-0.64,53.57]},
               "properties":{"id":"good"}}
            ]}
        """.trimIndent()

        val cameras = CouncilDataLoader.parseJson(geoJson)

        assertEquals(listOf("good"), cameras.map { it.id })
    }

    @Test
    fun `an empty or unreadable document yields no cameras`() {
        assertTrue(CouncilDataLoader.parseJson("""{"type":"FeatureCollection"}""").isEmpty())
        assertTrue(CouncilDataLoader.parseJson("""{"features":null}""").isEmpty())
    }

    @Test
    fun `property lookup ignores blank values`() {
        val geoJson = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-0.65,53.58]},
               "properties":{"id":"  ","camera_id":"NLC-002"}}
            ]}
        """.trimIndent()

        val camera = CouncilDataLoader.parseJson(geoJson).single()

        assertEquals("NLC-002", camera.id)
        assertNull(CameraTags.decode(camera.tagsJson)["missing"])
    }
}
