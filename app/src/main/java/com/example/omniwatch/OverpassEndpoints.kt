package com.example.omniwatch

/**
 * Public Overpass mirrors used to look up OpenStreetMap cameras.
 *
 * The endpoints are not interchangeable at runtime. A mirror can be up one minute and answering
 * HTTP 500 the next, and some networks cannot reach one host at all: on several mobile carriers
 * TCP to the main `overpass-api.de` host is refused while community mirrors still work. Always
 * starting at the top of a fixed list therefore means every viewport change pays for the dead
 * host first, so a mirror that answered recently is remembered and promoted.
 */
internal object OverpassEndpoints {

    /** Canonical order: the project's own servers first, then community mirrors. */
    val ALL: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
    )

    /**
     * [ALL] with [preferred] moved to the front, so a mirror that answered recently is retried
     * before the ones known to be failing. An unknown or absent preference leaves the canonical
     * order untouched, and the promoted mirror is still only one attempt: the remaining mirrors
     * are tried if it starts failing again.
     */
    fun ordered(preferred: String?): List<String> {
        if (preferred == null || preferred !in ALL) return ALL
        return listOf(preferred) + ALL.filterNot { endpoint -> endpoint == preferred }
    }
}
