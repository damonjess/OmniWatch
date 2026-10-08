package com.example.omniwatch

/**
 * Normalises the page a public webcam entry points at before it is handed to a WebView.
 *
 * OpenStreetMap's `contact:webcam` tag is free text, so it holds plenty of values that are not
 * pages at all: a Wi-Fi bridge model such as `CPE510`, a mistyped scheme such as `hhttp://...`, or
 * a cleartext snapshot URL such as `http://data.example.org/images152.jpg`. Passing any of those
 * to a WebView produces Chromium's own error page, which reads to a user as the camera being
 * broken. They are rejected here instead, so the caller can show the camera's details.
 */
internal object WebcamPages {

    /**
     * Returns an https URL that a WebView can load, or null when [value] is not a web address.
     *
     * Cleartext `http://` is upgraded to `https://`. Android blocks cleartext for apps targeting
     * recent API levels, so an http page cannot load in the WebView either way; upgrading gives
     * the host a chance to answer over TLS, which almost all of these snapshot hosts support.
     */
    fun embeddableUrl(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring("http://".length)
            else -> null
        }
    }
}
