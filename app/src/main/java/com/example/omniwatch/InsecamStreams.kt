package com.example.omniwatch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves live stream and snapshot URLs embedded in Insecam camera view pages.
 *
 * Insecam catalogs public IP security cameras behind view pages such as
 * `http://www.insecam.org/en/view/1011059/`. The page embeds the raw camera stream or JPEG
 * refresh feed in an `<img>` tag with `id="image0"`.
 */
object InsecamStreams {

    private const val TAG = "InsecamStreams"
    private const val INSECAM_HOST = "insecam.org"

    /**
     * Matches image/stream source in Insecam's viewer HTML:
     * e.g., `<img id="image0" class="img-responsive" src="http://192.168.1.1:8080/mjpg/video.mjpg">`
     */
    private val IMAGE_STREAM_REGEX = Regex(
        """<img[^>]*\bid=["']image\d*["'][^>]*\bsrc=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Secondary fallback regex when `src` precedes `id`:
     * e.g., `<img src="http://192.168.1.1:8080/mjpg/video.mjpg" id="image0">`
     */
    private val IMAGE_STREAM_ALT_REGEX = Regex(
        """<img[^>]*\bsrc=["']([^"']+)["'][^>]*\bid=["']image\d*["']""",
        RegexOption.IGNORE_CASE
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** True when [url] points at an Insecam viewer page. */
    fun isInsecamPage(url: String?): Boolean =
        url?.contains(INSECAM_HOST, ignoreCase = true) == true

    /**
     * Resolves the direct live stream/image URL for an Insecam page, or returns an already
     * direct HTTP(S) camera URL unchanged. Returns null when a page cannot be fetched or does
     * not embed a camera stream.
     */
    suspend fun resolveStreamUrl(pageOrStreamUrl: String): String? = withContext(Dispatchers.IO) {
        if (!isInsecamPage(pageOrStreamUrl)) {
            return@withContext pageOrStreamUrl.takeIf {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
            }
        }

        runCatching {
            val request = Request.Builder()
                .url(pageOrStreamUrl)
                .header("User-Agent", AppUserAgent.value)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                extractStreamUrl(response.body?.string().orEmpty())
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not resolve stream URL for $pageOrStreamUrl", error)
        }.getOrNull()
    }

    /**
     * Extracts the direct stream or image URL embedded in Insecam viewer HTML.
     */
    internal fun extractStreamUrl(html: String): String? {
        val direct = IMAGE_STREAM_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: IMAGE_STREAM_ALT_REGEX.find(html)?.groupValues?.getOrNull(1)
        val trimmed = direct?.trim()?.replace("&amp;", "&", ignoreCase = true).orEmpty()
        if (trimmed.isEmpty()) return null
        
        // Ensure the extracted URL has a valid scheme (http/https). 
        // Insecam often returns relative URLs or URLs starting with //
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "http:$trimmed"
            trimmed.startsWith("/") -> "http://www.insecam.org$trimmed"
            else -> "http://$trimmed"
        }
    }
}
