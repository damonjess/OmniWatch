package com.example.omniwatch

/**
 * Plain, map-library agnostic model for a single camera shown on the map.
 */
data class CctvNode(
    val lat: Double,
    val lon: Double,
    val titleStr: String,
    val source: String = "",
    val operator: String = "",
    val type: String = "",
    val snippet: String = "",
    val imageUrl: String? = null,
    val streamUrl: String? = null,
    val streamType: String? = null,
    val websiteUrl: String? = null,
    // Every raw attribute from the source, shown verbatim in the detail sheet.
    val tags: Map<String, String> = emptyMap(),
    val isCouncil: Boolean = false,
    val isTrafficCamera: Boolean = false,
    val isWebcam: Boolean = false,
)
