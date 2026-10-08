package com.example.omniwatch

/**
 * Decides how a camera's stream URL should be played.
 *
 * TrafficVision publishes three things behind one URL field: HLS playlists (earthcam, ozolio,
 * camsecure), progressive MP4 clips (TfL JamCams), and operator player pages that have to be
 * loaded in a WebView. The kind has to be known before a sheet is opened, so it is derived here
 * from the URL and, when the catalogue states it, the declared stream type.
 */
object CameraStreams {

    const val HLS = "HLS"
    const val MP4 = "MP4"

    /** A player page rather than a media file, including anything unrecognised. */
    const val PAGE = "PAGE"

    /**
     * Classifies [streamUrl]. A declared [streamType] from the catalogue wins, so a provider that
     * serves a playlist from a query string with no `.m3u8` in the path is still played as HLS.
     */
    fun kind(streamUrl: String?, streamType: String? = null): String {
        val url = streamUrl?.trim().orEmpty()
        if (url.isEmpty()) return PAGE

        val declared = streamType?.trim()?.uppercase().orEmpty()
        return when {
            declared == HLS || url.contains(".m3u8", ignoreCase = true) -> HLS
            declared == MP4 ||
                url.contains(".mp4", ignoreCase = true) ||
                url.contains(".m4v", ignoreCase = true) ||
                url.contains(".mov", ignoreCase = true) -> MP4
            else -> PAGE
        }
    }

    /** True when the in-app ExoPlayer can open [streamUrl] directly. */
    fun isDirectVideo(streamUrl: String?, streamType: String? = null): Boolean =
        kind(streamUrl, streamType) != PAGE
}
