package com.example.omniwatch

/**
 * OpenStreetMap services reject requests whose User-Agent is missing or looks like a bare
 * package/bot token: the tile server answers with a blank placeholder tile, and the Overpass
 * API answers HTTP 406. Both policies require a descriptive, app-identifying User-Agent.
 *
 * See https://operations.osmfoundation.org/policies/tiles/
 */
object AppUserAgent {
    val value: String =
        "OmniWatch/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"
}
