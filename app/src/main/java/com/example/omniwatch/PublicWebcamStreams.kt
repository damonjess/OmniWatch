package com.example.omniwatch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Public webcam providers that publish their stream behind a player page instead of a stable
 * playlist URL.
 *
 * SkylineWebcams is the main case. The catalogue stores the operator's page because the real
 * playlist carries a short-lived, per-visit token: a playlist copied into `public_webcams.json`
 * would be dead by the time anyone tapped it. Instead the token is read from the page's player
 * configuration immediately before playback.
 *
 * The operator's own Clappr player builds its stream URL as
 * `https://hd-auth.skylinewebcams.com/` + the page's `source` value with the `livee` prefix
 * swapped for `live`, for example `livee.m3u8?a=TOKEN` becomes
 * `https://hd-auth.skylinewebcams.com/live.m3u8?a=TOKEN`.
 */
object PublicWebcamStreams {

    private const val TAG = "PublicWebcamStreams"
    private const val SKYLINE_HOST = "skylinewebcams.com"
    private const val SKYLINE_AUTH_BASE = "https://hd-auth.skylinewebcams.com/"

    /**
     * Player configuration embedded in the page, as either key used by the operator's player:
     * `source:'livee.m3u8?a=token'` or `url: "livee.m3u8?a=token"`.
     */
    private val PLAYER_SOURCE_REGEX =
        Regex("""(?:url|source)\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")

    /**
     * Last resort for pages that name the playlist under some other key (`file`, `src`, `hls`...):
     * any quoted string that contains an `.m3u8` address.
     */
    private val ANY_QUOTED_PLAYLIST_REGEX =
        Regex("""['"]([^'"\s]*\.m3u8[^'"\s]*)['"]""")

    /** Fallback for pages that already spell out the signed playlist host. */
    private val ABSOLUTE_PLAYLIST_REGEX =
        Regex("""https?://hd-auth\.skylinewebcams\.com/[^'"\s]+\.m3u8[^'"\s]*""")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** True when [url] points at an operator page whose live playlist has to be resolved. */
    fun isSkylinePage(url: String?): Boolean =
        url?.contains(SKYLINE_HOST, ignoreCase = true) == true

    /**
     * Resolves the signed HLS playlist for [pageUrl]. Returns null when the page cannot be
     * fetched or no longer embeds a playlist, so callers can report a clear failure instead of
     * silently showing an empty player.
     */
    suspend fun resolveLiveHlsUrl(pageUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", AppUserAgent.value)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Skyline page returned HTTP ${response.code} for $pageUrl")
                    return@use null
                }
                val html = response.body?.string().orEmpty()
                extractPlaylistUrl(html).also { found ->
                    if (found == null) {
                        Log.w(
                            TAG,
                            "No Skyline playlist in $pageUrl (page ${html.length} chars, " +
                                "mentions m3u8: ${html.contains(".m3u8")}, " +
                                "mentions youtube: ${html.contains("youtube", ignoreCase = true)})",
                        )
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not resolve the live stream for $pageUrl", error)
        }.getOrNull()
    }

    /**
     * Pure parsing step, kept separate from the network call so it can be unit tested. Takes the
     * page HTML and returns the playable playlist URL, or null when no playlist is embedded.
     */
    internal fun extractPlaylistUrl(html: String): String? {
        val source = PLAYER_SOURCE_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: ABSOLUTE_PLAYLIST_REGEX.find(html)?.value
            ?: ANY_QUOTED_PLAYLIST_REGEX.find(html.replace("\\/", "/"))?.groupValues?.getOrNull(1)
        return toPlayableUrl(source)
    }

    private fun toPlayableUrl(source: String?): String? {
        val trimmed = source?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.contains(".m3u8", ignoreCase = true)) return null
        val signed = trimmed.replace("livee.m3u8", "live.m3u8", ignoreCase = true)
        return when {
            signed.startsWith("http:", ignoreCase = true) ||
                signed.startsWith("https:", ignoreCase = true) -> signed
            else -> SKYLINE_AUTH_BASE + signed.trimStart('/')
        }
    }
}
