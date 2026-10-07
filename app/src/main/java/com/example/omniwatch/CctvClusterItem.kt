package com.example.omniwatch

/**
 * Plain, map-library agnostic model for a single camera shown on the map.
 */
data class CctvClusterItem(
    val lat: Double,
    val lon: Double,
    val titleStr: String,
    val source: String, // where the record came from, e.g. "OVERPASS"
    val operator: String,
    val type: String,
    val snippet: String = "",
    // Every raw attribute from the source, shown verbatim in the detail sheet.
    val tags: Map<String, String> = emptyMap(),
    val isTrafficCamera: Boolean = false,
)
