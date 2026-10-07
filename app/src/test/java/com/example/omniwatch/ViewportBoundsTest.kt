package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the viewport rules that decide how much of the map is queried for cameras.
 */
class ViewportBoundsTest {

    @Test
    fun `small box is left unchanged`() {
        val box = ViewportBounds(north = 53.63, east = -0.55, south = 53.53, west = -0.75)

        val clamped = box.clampedTo(maxSpanDegrees = 0.6)

        assertEquals(box, clamped)
    }

    @Test
    fun `oversized box is shrunk around its own centre`() {
        // A whole-country view: 4 degrees tall by 6 wide.
        val box = ViewportBounds(north = 55.0, east = 2.0, south = 51.0, west = -4.0)

        val clamped = requireNotNull(box.clampedTo(maxSpanDegrees = 0.6))

        assertEquals(0.6, clamped.latitudeSpan, 1e-9)
        assertEquals(0.6, clamped.longitudeSpan, 1e-9)
        // Centre must be preserved so the query still covers what the user is looking at.
        assertEquals(box.centerLatitude, clamped.centerLatitude, 1e-9)
        assertEquals(box.centerLongitude, clamped.centerLongitude, 1e-9)
    }

    @Test
    fun `clamping only applies to the axis that is too large`() {
        val box = ViewportBounds(north = 53.9, east = -0.4, south = 51.0, west = -0.6)

        val clamped = requireNotNull(box.clampedTo(maxSpanDegrees = 0.6))

        assertEquals(0.6, clamped.latitudeSpan, 1e-9)
        // Longitude was already narrow, so it must be untouched.
        assertEquals(-0.6, clamped.west, 1e-9)
        assertEquals(-0.4, clamped.east, 1e-9)
    }

    @Test
    fun `box with no area is rejected`() {
        // Before the map view is laid out the bounding box is empty.
        assertNull(ViewportBounds(north = 0.0, east = 0.0, south = 0.0, west = 0.0).clampedTo(0.6))
        assertNull(ViewportBounds(north = 53.0, east = 0.0, south = 53.0, west = 0.0).clampedTo(0.6))
    }

    @Test
    fun `overpass bbox uses south,west,north,east order`() {
        val box = ViewportBounds(north = 53.63, east = -0.55, south = 53.53, west = -0.75)

        assertEquals("53.53,-0.75,53.63,-0.55", box.toOverpassBbox())
    }

    @Test
    fun `contains matches points inside and rejects points outside`() {
        val box = ViewportBounds(north = 53.63, east = -0.55, south = 53.53, west = -0.75)

        assertTrue(box.contains(53.58, -0.65))
        assertTrue(box.contains(53.53, -0.75)) // on the corner
        assertFalse(box.contains(53.70, -0.65)) // north of the box
        assertFalse(box.contains(53.58, -0.40)) // east of the box
    }

    @Test
    fun `same area comparison tolerates float noise but detects real movement`() {
        val box = ViewportBounds(north = 53.63, east = -0.55, south = 53.53, west = -0.75)

        assertTrue(box.coversSameAreaAs(box.copy(north = 53.63 + 1e-9)))
        assertFalse(box.coversSameAreaAs(box.copy(north = 53.64)))
        assertFalse(box.coversSameAreaAs(null))
    }
}
