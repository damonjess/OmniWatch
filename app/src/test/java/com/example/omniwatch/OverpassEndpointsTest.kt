package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the Overpass mirror ordering that keeps a flaky or blocked endpoint from being the
 * first thing every viewport change waits on.
 */
class OverpassEndpointsTest {

    @Test
    fun `without a preference the canonical order is used unchanged`() {
        assertEquals(OverpassEndpoints.ALL, OverpassEndpoints.ordered(null))
    }

    @Test
    fun `an unknown preference does not disturb the canonical order`() {
        assertEquals(OverpassEndpoints.ALL, OverpassEndpoints.ordered("https://example.invalid/api"))
    }

    @Test
    fun `a preferred mirror is promoted to the front without losing any endpoint`() {
        val last = OverpassEndpoints.ALL.last()
        val ordered = OverpassEndpoints.ordered(last)

        assertEquals("Preferred mirror must be tried first", last, ordered.first())
        assertEquals("No endpoint may be dropped or duplicated", OverpassEndpoints.ALL.size, ordered.size)
        assertEquals(
            "Every endpoint must still be present",
            OverpassEndpoints.ALL.toSet(),
            ordered.toSet(),
        )
        assertEquals(
            "The remaining endpoints keep their canonical order",
            OverpassEndpoints.ALL.filterNot { it == last },
            ordered.drop(1),
        )
    }

    @Test
    fun `every endpoint is a usable https interpreter url`() {
        assertTrue("Expected several mirrors", OverpassEndpoints.ALL.size >= 2)
        OverpassEndpoints.ALL.forEach { endpoint ->
            assertTrue("$endpoint must use https", endpoint.startsWith("https://"))
            assertTrue("$endpoint must target the interpreter", endpoint.endsWith("/api/interpreter"))
        }
        assertEquals(
            "Hardcoded mirrors must be unique",
            OverpassEndpoints.ALL.size,
            OverpassEndpoints.ALL.toSet().size,
        )
    }
}
