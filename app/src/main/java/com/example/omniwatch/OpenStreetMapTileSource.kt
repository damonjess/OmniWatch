package com.example.omniwatch

import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Standard OpenStreetMap raster tiles.
 *
 * osmdroid's built-in `TileSourceFactory.MAPNIK` advertises
 * [TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED], which makes its downloader send
 * `"<packageName>/<versionCode>"` and silently ignore the configured User-Agent. OSM's tile
 * server rejects that form and answers with a blank placeholder tile, which leaves the map
 * looking like an empty white screen.
 *
 * This source keeps OSM's usage-policy restrictions (no bulk download, no preventive
 * prefetching, meaningful User-Agent required) but lets [AppUserAgent.value] through.
 *
 * See https://operations.osmfoundation.org/policies/tiles/
 */
val OpenStreetMapTileSource: XYTileSource = XYTileSource(
    "OpenStreetMap",
    0, // min zoom
    19, // max zoom
    256, // tile size in pixels
    ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors",
    TileSourcePolicy(
        2, // max concurrent tile downloads
        TileSourcePolicy.FLAG_NO_BULK or
            TileSourcePolicy.FLAG_NO_PREVENTIVE or
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL,
    ),
)
