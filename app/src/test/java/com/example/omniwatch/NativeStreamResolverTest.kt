package com.example.omniwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStreamResolverTest {

    @Test
    fun youtubeVideoIdIsFoundInWatchEmbedAndShortLinks() {
        assertEquals("H3A2RSYTzdI", NativeStreamResolver.youtubeVideoId("https://www.youtube.com/watch?v=H3A2RSYTzdI"))
        assertEquals("rulGLEdCHZU", NativeStreamResolver.youtubeVideoId("https://www.youtube.com/watch?feature=share&v=rulGLEdCHZU"))
        assertEquals("awQzjn72bI0", NativeStreamResolver.youtubeVideoId("https://www.youtube-nocookie.com/embed/awQzjn72bI0?autoplay=1"))
        assertEquals("abcDEF12345", NativeStreamResolver.youtubeVideoId("https://youtu.be/abcDEF12345"))
        assertNull(NativeStreamResolver.youtubeVideoId("https://example.com/watch?v=abc"))
    }

    @Test
    fun twitchChannelIsFoundInPlayerAndChannelLinks() {
        assertEquals(
            "cmaloban",
            NativeStreamResolver.twitchChannel("https://player.twitch.tv/?channel=cmaloban&parent=www.cmassets.co.uk&autoplay=true"),
        )
        assertEquals("cmaloban", NativeStreamResolver.twitchChannel("https://www.twitch.tv/cmaloban"))
        assertNull(NativeStreamResolver.twitchChannel("https://www.twitch.tv/directory"))
        assertNull(NativeStreamResolver.twitchChannel("https://example.com/twitch.tv/x"))
    }

    @Test
    fun onlyYoutubeAndTwitchPagesAreResolvable() {
        assertTrue(NativeStreamResolver.isResolvablePage("https://www.youtube.com/watch?v=H3A2RSYTzdI"))
        assertTrue(NativeStreamResolver.isResolvablePage("https://player.twitch.tv/?channel=cmaloban"))
        assertFalse(NativeStreamResolver.isResolvablePage("https://example.com/cam.html"))
        assertFalse(NativeStreamResolver.isResolvablePage(null))
    }

    @Test
    fun youtubeManifestIsReadFromAPlayerResponse() {
        val live = """{"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/x/index.m3u8"}}"""
        assertEquals(
            "https://manifest.googlevideo.com/api/manifest/hls_variant/x/index.m3u8",
            NativeStreamResolver.parseYoutubeHlsManifest(live),
        )
        assertNull(NativeStreamResolver.parseYoutubeHlsManifest("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""))
        assertNull(NativeStreamResolver.parseYoutubeHlsManifest("not json"))
    }

    @Test
    fun twitchTokenIsReadFromAGqlResponse() {
        val json = """{"data":{"streamPlaybackAccessToken":{"value":"{\"channel\":\"x\"}","signature":"abc123"}}}"""
        val token = NativeStreamResolver.parseTwitchToken(json)
        assertEquals("""{"channel":"x"}""", token?.first)
        assertEquals("abc123", token?.second)
        assertNull(NativeStreamResolver.parseTwitchToken("""{"data":{"streamPlaybackAccessToken":null}}"""))
    }

    @Test
    fun genericExtractionPrefersHlsOverMp4AndUnescapesTheUrl() {
        val html = """<script>var a="https:\/\/cdn.example.com\/clip.mp4";
            var s='https://cdn.example.com/live/cam1/playlist.m3u8?token=a&amp;b=2';</script>"""
        val resolved = NativeStreamResolver.extractMediaUrl(html, "https://example.com/cam")
        assertEquals("https://cdn.example.com/live/cam1/playlist.m3u8?token=a&b=2", resolved?.url)
        assertEquals(CameraStreams.HLS, resolved?.kind)
        assertEquals("https://example.com/cam", resolved?.headers?.get("Referer"))
    }

    @Test
    fun genericExtractionFallsBackToMp4AndReturnsNullWhenThereIsNoStream() {
        val mp4 = NativeStreamResolver.extractMediaUrl("""<video src="https://x.org/a/b.mp4"></video>""", "https://x.org")
        assertEquals(CameraStreams.MP4, mp4?.kind)
        assertNull(NativeStreamResolver.extractMediaUrl("<html>no player here</html>", "https://x.org"))
    }
}
