package com.example.omniwatch

/**
 * Builds the Overpass QL request for the cameras inside a viewport.
 *
 * OpenStreetMap has no single camera tag, so the request is the union of every scheme mappers
 * actually use. Asking for fewer patterns silently hides real cameras: a webcam, a safety camera
 * or a camera mapped only through `camera:type` carries none of the `man_made`/`surveillance`
 * tags, so a narrower query never returns it.
 */
object OverpassQuery {

    /** One selector per camera scheme, `%s` standing for the bounding box. */
    private val SELECTORS = listOf(
        // The two dominant schemes.
        "nwr[\"man_made\"=\"surveillance\"](%s);",
        "nwr[\"surveillance\"][\"surveillance\"!=\"no\"](%s);",
        // Cameras mapped only through a sub-key of either scheme.
        "nwr[\"surveillance:type\"](%s);",
        "nwr[\"camera:type\"](%s);",
        "nwr[\"camera:mount\"](%s);",
        // Webcams and cameras reached through a contact point.
        "nwr[\"man_made\"=\"webcam\"](%s);",
        "nwr[\"contact:webcam\"](%s);",
        // Safety cameras, mapped as part of the road network rather than as surveillance.
        "nwr[\"highway\"=\"speed_camera\"](%s);",
    )

    /**
     * `nwr` covers nodes, ways and relations; `out center` gives ways and relations a
     * representative point so they can be placed on the map.
     */
    fun build(bounds: ViewportBounds): String {
        val box = bounds.toOverpassBbox()
        val union = SELECTORS.joinToString("") { it.format(box) }
        // The union block itself must be closed with ");" before the output statement. Without
        // that semicolon Overpass answers HTTP 400 with "parse error: ';' expected - 'out' found".
        return "$TIMEOUT_HEADER($union);out center;"
    }

    private const val TIMEOUT_HEADER = "[out:json][timeout:25];"
}
