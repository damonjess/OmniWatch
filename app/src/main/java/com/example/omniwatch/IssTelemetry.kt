package com.example.omniwatch

import java.util.Locale

/**
 * Formats the live ISS telemetry for the tracker sheet.
 *
 * Deliberately free of Android and network types, so the number shapes and the country lookup can
 * be unit tested. All formatting is pinned to [Locale.US]: the sheet shows grouped thousands and a
 * decimal point, and the value must not change shape on a device set to another locale.
 */
object IssTelemetry {

    /** Kept short enough for the sheet's value column. */
    private const val UNKNOWN = "UNKNOWN"

    /**
     * "420 km": the feed reports altitude in kilometres, and metres would imply a precision the
     * orbital data does not have.
     */
    fun formatAltitudeKm(kilometres: Double): String =
        String.format(Locale.US, "%.0f km", kilometres)

    /** "27,581 km/h": grouped, because the station covers roughly 27,600 km every hour. */
    fun formatVelocityKmh(kilometresPerHour: Double): String =
        String.format(Locale.US, "%,.0f km/h", kilometresPerHour)

    /**
     * "-9.5383°": four decimals is about 10 m of ground track, which is as precise as the feed is,
     * and it matches how the station's position is quoted elsewhere.
     */
    fun formatCoordinate(degrees: Double): String =
        String.format(Locale.US, "%.4f°", degrees)

    /**
     * The feed reports "daylight" or "eclipsed". An absent or unexpected value is shown as
     * [UNKNOWN] rather than hidden, so the row never looks like a rendering fault.
     */
    fun formatVisibility(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.US) ?: UNKNOWN

    /**
     * The platform's own placeholder for a region it does not recognise. Resolved once from a
     * deliberately bogus code, so the check does not depend on one hard-coded English string.
     */
    private val unknownRegionPlaceholder: String by lazy {
        runCatching {
            Locale.Builder().setRegion(BOGUS_REGION).build().getDisplayCountry(Locale.ENGLISH)
        }.getOrDefault("")
    }

    /**
     * Turns the ISO country code under the station into a readable name ("PE" -> "Peru") for the
     * "OVER" row. Returns null over open ocean, where there is no country to name, and for codes
     * the platform does not know.
     */
    fun formatRegion(countryCode: String?): String? {
        val code = countryCode?.trim()?.uppercase(Locale.US).orEmpty()
        // Only two-letter ISO regions can be looked up; anything else is not a country code.
        if (code.length != 2) return null
        return runCatching {
            Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            // An unrecognised code comes back as the code itself, or as the platform's placeholder.
            ?.takeIf { !it.equals(code, ignoreCase = true) }
            ?.takeIf { !it.equals(unknownRegionPlaceholder, ignoreCase = true) }
    }

    /** Not a real ISO region, so the platform answers with its "unknown" placeholder. */
    private const val BOGUS_REGION = "ZZ"
}
