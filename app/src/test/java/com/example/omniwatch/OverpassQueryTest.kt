package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the set of OpenStreetMap camera schemes the app asks for. Each pattern here is a real
 * tagging scheme in use; dropping one silently removes cameras from the map.
 */
class OverpassQueryTest {

    private val bounds = ViewportBounds(north = 53.72, east = -0.44, south = 53.39, west = -0.82)
    private val query = OverpassQuery.build(bounds)
    private val box = bounds.toOverpassBbox()

    /** Held independently of the production list on purpose: this is the regression guard. */
    private val requiredPatterns = listOf(
        "nwr[\"man_made\"=\"surveillance\"]",
        "nwr[\"surveillance\"][\"surveillance\"!=\"no\"]",
        "nwr[\"surveillance:type\"]",
        "nwr[\"camera:type\"]",
        "nwr[\"camera:mount\"]",
        "nwr[\"man_made\"=\"webcam\"]",
        "nwr[\"contact:webcam\"]",
        "nwr[\"highway\"=\"speed_camera\"]",
    )

    @Test
    fun `asks for every camera tagging scheme`() {
        requiredPatterns.forEach { pattern ->
            assertTrue("missing selector: $pattern", query.contains(pattern))
        }
    }

    @Test
    fun `every selector is bounded by the viewport`() {
        val occurrences = Regex(Regex.escape("($box);")).findAll(query).count()

        assertEquals(requiredPatterns.size, occurrences)
    }

    @Test
    fun `queries nodes ways and relations rather than nodes only`() {
        // Ways and relations used to be missed entirely, which hid mapped camera poles.
        assertTrue(query.contains("nwr["))
        assertFalse(query.contains("node["))
    }

    @Test
    fun `is a single union request with a timeout and centre points`() {
        assertTrue(query.startsWith("[out:json][timeout:25];("))
        assertTrue(query.endsWith("out center;"))
        // One request, not one per selector.
        assertEquals(1, Regex("out center;").findAll(query).count())
    }

    @Test
    fun `matches the query accepted by the live Overpass API`() {
        // Exact string, because Overpass rejects the whole request over one missing semicolon:
        // a union block has to be closed with ");" before "out center;".
        val expected = "[out:json][timeout:25];(" +
            "nwr[\"man_made\"=\"surveillance\"](53.39,-0.82,53.72,-0.44);" +
            "nwr[\"surveillance\"][\"surveillance\"!=\"no\"](53.39,-0.82,53.72,-0.44);" +
            "nwr[\"surveillance:type\"](53.39,-0.82,53.72,-0.44);" +
            "nwr[\"camera:type\"](53.39,-0.82,53.72,-0.44);" +
            "nwr[\"camera:mount\"](53.39,-0.82,53.72,-0.44);" +
            "nwr[\"man_made\"=\"webcam\"](53.39,-0.82,53.72,-0.44);" +
            "nwr[\"contact:webcam\"](53.39,-0.82,53.72,-0.44);" +
            "nwr[\"highway\"=\"speed_camera\"](53.39,-0.82,53.72,-0.44);" +
            ");out center;"

        assertEquals(expected, OverpassQuery.build(bounds))
    }

    @Test
    fun `explicitly negated surveillance is excluded`() {
        // `surveillance=no` states that nothing is watching, so it must not become a camera.
        assertTrue(query.contains("[\"surveillance\"!=\"no\"]"))
    }
}
