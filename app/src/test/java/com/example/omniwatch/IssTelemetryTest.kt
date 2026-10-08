package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Validates the ISS tracker sheet's value formatting. The samples are the shapes the tracking feed
 * actually returns, and the expected strings are what the sheet prints.
 */
class IssTelemetryTest {

    @Test
    fun `coordinates keep four decimals and a degree sign`() {
        assertEquals("-9.5383°", IssTelemetry.formatCoordinate(-9.5383))
        assertEquals("75.7825°", IssTelemetry.formatCoordinate(75.78252))
        assertEquals("0.0000°", IssTelemetry.formatCoordinate(0.0))
    }

    @Test
    fun `altitude is shown in whole kilometres`() {
        assertEquals("420 km", IssTelemetry.formatAltitudeKm(420.38144190283))
    }

    @Test
    fun `velocity is grouped in kilometres per hour`() {
        assertEquals("27,581 km/h", IssTelemetry.formatVelocityKmh(27581.017885965))
        assertEquals("999 km/h", IssTelemetry.formatVelocityKmh(999.4))
    }

    @Test
    fun `visibility is normalised and never blank`() {
        assertEquals("DAYLIGHT", IssTelemetry.formatVisibility("daylight"))
        assertEquals("ECLIPSED", IssTelemetry.formatVisibility("eclipsed"))
        assertEquals("UNKNOWN", IssTelemetry.formatVisibility(null))
        assertEquals("UNKNOWN", IssTelemetry.formatVisibility("   "))
    }

    @Test
    fun `a country code becomes a readable country name`() {
        assertEquals("Peru", IssTelemetry.formatRegion("PE"))
        assertEquals("United Kingdom", IssTelemetry.formatRegion("gb"))
    }

    @Test
    fun `an absent or unusable region has no name`() {
        // The coordinates endpoint answers 404 over open ocean, so there is no code to resolve.
        assertNull(IssTelemetry.formatRegion(null))
        assertNull(IssTelemetry.formatRegion(""))
        assertNull(IssTelemetry.formatRegion("  "))
        assertNull(IssTelemetry.formatRegion("ZZ"))
    }
}
