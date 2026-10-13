package com.example.omniwatch

/**
 * Decides which map symbol a camera gets: the live-video dome icon, or the plain coloured dot for a
 * camera that only offers a still image.
 */
object MarkerStyle {

    /**
     * True when tapping the camera plays live video: a direct HLS/MP4 stream, a Skyline page, a
     * YouTube or Twitch page, an Insecam feed, a curated public webcam, or a TrafficVision camera
     * that is not image-only. Cameras that only publish a still, which the app re-fetches every
     * few seconds, return false.
     *
     * OpenStreetMap nodes that merely carry a webcam page link are not counted: the app only finds
     * out whether such a page holds a playable stream after the user taps it.
     */
    fun hasLiveVideo(node: CctvNode): Boolean {
        if (node.source == PublicWebcamDataLoader.SOURCE_INSECAM ||
            node.source == PublicWebcamDataLoader.SOURCE_PUBLIC_WEBCAM
        ) {
            return true
        }
        if (node.source == TrafficVisionDataLoader.SOURCE_TRAFFICVISION) {
            val feedType = node.tags["feedType"].orEmpty()
            return feedType.isNotBlank() && feedType != "image"
        }
        val streamUrl = node.streamUrl?.takeIf { it.isNotBlank() } ?: return false
        return CameraStreams.isDirectVideo(streamUrl, node.streamType) ||
            PublicWebcamStreams.isSkylinePage(streamUrl) ||
            NativeStreamResolver.isResolvablePage(streamUrl)
    }
}
