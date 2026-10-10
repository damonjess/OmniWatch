package com.example.omniwatch

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Turns a webcam *page* into a media URL that ExoPlayer can play, so the app never has to fall
 * back to a WebView.
 *
 * ExoPlayer only plays media (HLS, MP4), not web pages. YouTube and Twitch publish their live
 * streams behind a player page, so each one is resolved to its HLS playlist at tap time, the
 * same way [PublicWebcamStreams] does for SkylineWebcams. Any other page is scraped for an
 * `.m3u8` or `.mp4` address.
 *
 * Every resolver here uses an unofficial, undocumented endpoint. They can stop working when the
 * provider changes something; when that happens [resolve] returns null and the caller shows
 * "stream unavailable" instead of a website.
 */
object NativeStreamResolver {

    private const val TAG = "NativeStreamResolver"

    /** A playable address, its kind ([CameraStreams.HLS] or [CameraStreams.MP4]) and any headers it needs. */
    data class Resolved(
        val url: String,
        val kind: String,
        val headers: Map<String, String> = emptyMap(),
    )

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    // Twitch's own web player Client-ID. Public, but Twitch can rotate it; change it here if
    // Twitch playback starts failing with an authorization error in logcat.
    private const val TWITCH_CLIENT_ID = "kimne78kh0ncgl6j9vzmw0wv3ykpqa"

    private val YOUTUBE_ID_REGEX = Regex(
        """(?:youtube(?:-nocookie)?\.com/(?:watch\?(?:[^#]*&)?v=|live/|embed/(?!live_stream\b))|youtu\.be/)([A-Za-z0-9_-]{6,})"""
    )
    private val YOUTUBE_CHANNEL_REGEX = Regex(
        """(?:youtube(?:-nocookie)?\.com/channel/|embed/live_stream\?(?:[^#]*&)?channel=)([A-Za-z0-9_-]{10,})""",
        RegexOption.IGNORE_CASE,
    )
    private val YOUTUBE_CANONICAL_REGEX = Regex(
        """<link\s+rel=["']canonical["']\s+href=["']https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{6,})""",
        RegexOption.IGNORE_CASE,
    )
    private val TWITCH_PLAYER_REGEX =
        Regex("""player\.twitch\.tv/\?(?:[^#]*&)?channel=([A-Za-z0-9_]{3,25})""")
    private val TWITCH_CHANNEL_REGEX =
        Regex("""(?:^|//)(?:www\.)?twitch\.tv/([A-Za-z0-9_]{3,25})(?:[/?#]|$)""")
    private val TWITCH_RESERVED = setOf("videos", "directory", "downloads", "settings", "p", "jobs")

    private val M3U8_REGEX = Regex("""https?://[^"'\s<>\\]+?\.m3u8(?:\?[^"'\s<>\\]*)?""")
    private val MP4_REGEX = Regex("""https?://[^"'\s<>\\]+?\.mp4(?:\?[^"'\s<>\\]*)?""")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- recognising pages

    internal fun youtubeVideoId(url: String?): String? =
        url?.let { YOUTUBE_ID_REGEX.find(it)?.groupValues?.getOrNull(1) }

    internal fun youtubeChannelId(url: String?): String? =
        url?.let { YOUTUBE_CHANNEL_REGEX.find(it)?.groupValues?.getOrNull(1) }

    internal fun extractYoutubeVideoIdFromChannelPage(html: String): String? =
        YOUTUBE_CANONICAL_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: YOUTUBE_ID_REGEX.find(html)?.groupValues?.getOrNull(1)

    internal fun twitchChannel(url: String?): String? {
        if (url.isNullOrBlank()) return null
        TWITCH_PLAYER_REGEX.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        val channel = TWITCH_CHANNEL_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return null
        return channel.takeUnless { it.lowercase() in TWITCH_RESERVED }
    }

    /** True for pages that have a dedicated resolver (YouTube, Twitch, Webcamtaxi). */
    fun isResolvablePage(url: String?): Boolean =
        youtubeVideoId(url) != null ||
            youtubeChannelId(url) != null ||
            twitchChannel(url) != null ||
            url?.contains("webcamtaxi.com", ignoreCase = true) == true

    // ---------------------------------------------------------------- resolving

    /** Resolves [pageUrl] to a playable stream, or null when none can be found. */
    suspend fun resolve(pageUrl: String): Resolved? = withContext(Dispatchers.IO) {
        try {
            val youtubeId = youtubeVideoId(pageUrl)
            val youtubeChannel = youtubeChannelId(pageUrl)
            val twitch = twitchChannel(pageUrl)
            when {
                youtubeId != null -> resolveYoutube(youtubeId)
                youtubeChannel != null -> resolveYoutubeChannel(youtubeChannel)?.let { resolveYoutube(it) }
                twitch != null -> resolveTwitch(twitch)
                else -> resolveGeneric(pageUrl)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runCatching { Log.w(TAG, "Could not resolve a native stream for $pageUrl", error) }
            null
        }
    }

    // ---------------------------------------------------------------- YouTube

    private class YoutubeClient(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val extra: Map<String, Any> = emptyMap(),
        val embedded: Boolean = false,
    )

    // Client identities for YouTube's internal player endpoint, tried in order. The iOS client
    // returns a plain HLS manifest for live streams without needing a proof-of-origin token.
    // The version numbers go stale; bump them if every client starts answering "not playable".
    private val YOUTUBE_CLIENTS = listOf(
        YoutubeClient(
            name = "IOS",
            version = "20.10.4",
            clientId = "5",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82",
            ),
        ),
        YoutubeClient(
            name = "ANDROID",
            version = "20.10.38",
            clientId = "3",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            extra = mapOf("androidSdkVersion" to 30, "osName" to "Android", "osVersion" to "11"),
        ),
        YoutubeClient(
            name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            version = "2.0",
            clientId = "85",
            userAgent = BROWSER_USER_AGENT,
            embedded = true,
        ),
    )

    /**
     * Resolves a channel's /live page to the current live video. YouTube redirects the page to
     * the active broadcast, so the final URL is more reliable than scraping a channel listing.
     */
    private fun resolveYoutubeChannel(channelId: String): String? {
        val request = Request.Builder()
            .url("https://www.youtube.com/channel/$channelId/live")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept-Language", "en-GB,en;q=0.9")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val redirectedId = response.request.url.queryParameter("v")
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }
            if (redirectedId != null) return@use redirectedId
            val body = response.body?.string().orEmpty()
            extractYoutubeVideoIdFromChannelPage(body)
        }
    }

    private fun resolveYoutube(videoId: String): Resolved? {
        for (client in YOUTUBE_CLIENTS) {
            val body = youtubePlayerRequestBody(videoId, client)
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", client.userAgent)
                .header("X-YouTube-Client-Name", client.clientId)
                .header("X-YouTube-Client-Version", client.version)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val manifest = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseYoutubeHlsManifest(response.body?.string().orEmpty())
            }
            if (manifest != null) {
                return Resolved(
                    url = manifest,
                    kind = CameraStreams.HLS,
                    headers = mapOf("User-Agent" to client.userAgent),
                )
            }
            runCatching { Log.w(TAG, "YouTube client ${client.name} returned no live manifest for $videoId") }
        }
        return null
    }

    private fun youtubePlayerRequestBody(videoId: String, client: YoutubeClient): String {
        val clientJson = JsonObject().apply {
            addProperty("clientName", client.name)
            addProperty("clientVersion", client.version)
            addProperty("hl", "en")
            addProperty("gl", "GB")
            client.extra.forEach { (key, value) ->
                if (value is Number) addProperty(key, value) else addProperty(key, value.toString())
            }
        }
        val context = JsonObject().apply {
            add("client", clientJson)
            if (client.embedded) {
                add("thirdParty", JsonObject().apply {
                    addProperty("embedUrl", "https://www.youtube.com/watch?v=$videoId")
                })
            }
        }
        return JsonObject().apply {
            addProperty("videoId", videoId)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
            add("context", context)
        }.toString()
    }

    /** Pulls `streamingData.hlsManifestUrl` out of a player response; null when it is not a live stream. */
    internal fun parseYoutubeHlsManifest(json: String): String? = runCatching {
        JsonParser.parseString(json).asJsonObject
            .getAsJsonObject("streamingData")
            ?.get("hlsManifestUrl")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
    }.getOrNull()

    // ---------------------------------------------------------------- Twitch

    private fun resolveTwitch(channel: String): Resolved? {
        // The channel name is restricted to [A-Za-z0-9_] by twitchChannel(), so it is safe to
        // place inside the query string literal.
        val query = "query { streamPlaybackAccessToken(channelName: \"$channel\", " +
            "params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\"}) " +
            "{ value signature } }"
        val body = JsonObject().apply { addProperty("query", query) }.toString()
        val request = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .header("Client-ID", TWITCH_CLIENT_ID)
            .header("User-Agent", BROWSER_USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val token = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            parseTwitchToken(response.body?.string().orEmpty())
        } ?: return null

        val playlist = "https://usher.ttvnw.net/api/channel/hls/$channel.m3u8".toHttpUrl()
            .newBuilder()
            .addQueryParameter("sig", token.second)
            .addQueryParameter("token", token.first)
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("player", "twitchweb")
            .addQueryParameter("p", (100000..999999).random().toString())
            .build()
            .toString()
        return Resolved(playlist, CameraStreams.HLS, mapOf("User-Agent" to BROWSER_USER_AGENT))
    }

    /** Returns (token, signature) from a GQL playback-access-token response, or null. */
    internal fun parseTwitchToken(json: String): Pair<String, String>? = runCatching {
        val node = JsonParser.parseString(json).asJsonObject
            .getAsJsonObject("data")
            .getAsJsonObject("streamPlaybackAccessToken")
        val value = node.get("value").asString
        val signature = node.get("signature").asString
        if (value.isBlank() || signature.isBlank()) null else value to signature
    }.getOrNull()

    // ---------------------------------------------------------------- generic pages

    private fun resolveGeneric(pageUrl: String): Resolved? {
        val url = WebcamPages.embeddableUrl(pageUrl) ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val rawHtml = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.take(MAX_PAGE_CHARS)
        } ?: return null

        val html = rawHtml.replace("\\/", "/").replace("&amp;", "&")

        youtubeVideoId(html)?.let { ytId ->
            resolveYoutube(ytId)?.let { return it }
        }
        youtubeChannelId(html)?.let { channelId ->
            resolveYoutubeChannel(channelId)?.let { resolveYoutube(it) }?.let { return it }
        }
        twitchChannel(html)?.let { channel ->
            resolveTwitch(channel)?.let { return it }
        }

        return extractMediaUrl(html, url)
    }

    private const val MAX_PAGE_CHARS = 2_000_000

    /**
     * Finds the first HLS playlist (preferred) or MP4 file mentioned in [html]. Pure, so it is
     * unit tested. Pages that build the player entirely in JavaScript will not contain either.
     */
    internal fun extractMediaUrl(html: String, pageUrl: String): Resolved? {
        val text = html.replace("\\/", "/").replace("&amp;", "&")
        val headers = mapOf("Referer" to pageUrl, "User-Agent" to BROWSER_USER_AGENT)
        M3U8_REGEX.find(text)?.value?.let { return Resolved(it, CameraStreams.HLS, headers) }
        MP4_REGEX.find(text)?.value?.let { return Resolved(it, CameraStreams.MP4, headers) }
        return null
    }
}
