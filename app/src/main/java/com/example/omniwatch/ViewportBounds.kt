package com.example.omniwatch

/**
 * A latitude/longitude box for the visible map area.
 *
 * Deliberately free of any map-library types so the clamping and containment rules can be unit
 * tested on the JVM.
 */
data class ViewportBounds(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double,
) {
    val latitudeSpan: Double get() = north - south
    val longitudeSpan: Double get() = east - west
    val centerLatitude: Double get() = (north + south) / 2
    val centerLongitude: Double get() = (east + west) / 2

    /**
     * Shrinks an oversized box around its own centre, or returns null for a box with no area
     * (which happens before the map view has been laid out).
     *
     * Zooming right out would otherwise ask a shared public API for every camera in the country.
     */
    fun clampedTo(maxSpanDegrees: Double): ViewportBounds? {
        if (latitudeSpan <= 0.0 || longitudeSpan <= 0.0) return null

        val half = maxSpanDegrees / 2
        return ViewportBounds(
            north = if (latitudeSpan > maxSpanDegrees) centerLatitude + half else north,
            east = if (longitudeSpan > maxSpanDegrees) centerLongitude + half else east,
            south = if (latitudeSpan > maxSpanDegrees) centerLatitude - half else south,
            west = if (longitudeSpan > maxSpanDegrees) centerLongitude - half else west,
        )
    }

    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in south..north && longitude in west..east

    /** Overpass expects its bounding box as south,west,north,east. */
    fun toOverpassBbox(): String = "$south,$west,$north,$east"

    /**
     * True when both boxes cover effectively the same area, used to skip a repeat query for a
     * viewport that has not actually moved.
     */
    fun coversSameAreaAs(other: ViewportBounds?, epsilon: Double = DEFAULT_EPSILON): Boolean {
        if (other == null) return false
        return kotlin.math.abs(north - other.north) < epsilon &&
            kotlin.math.abs(south - other.south) < epsilon &&
            kotlin.math.abs(east - other.east) < epsilon &&
            kotlin.math.abs(west - other.west) < epsilon
    }

    companion object {
        const val DEFAULT_EPSILON = 1e-6
    }
}
