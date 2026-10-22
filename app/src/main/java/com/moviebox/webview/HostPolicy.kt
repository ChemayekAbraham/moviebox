package com.moviebox.webview

import android.net.Uri
import java.util.Locale

/**
 * Central place that decides which websites are trusted.
 *
 * - [isMovieBoxHost]: the only hosts where the DJ SHIRLEY BEATS profile is injected.
 * - [isNavigationAllowed]: the only hosts the WebView may navigate the main page to.
 *   Everything else (betting, casino, ad redirects) is blocked.
 */
object HostPolicy {

    /** MovieBox-owned sites + its official video players. Profile injection happens ONLY here. */
    val MOVIEBOX_HOSTS = listOf(
        "officialmoviebox.com",
        "themoviebox.org",
        "movieboxonline.net",
        "aoneroom.com",
        "123movienow.cc",
        "fmoviesunblocked.net",
        "lok-lok.cc"
    )

    /** Extra hosts the main page may navigate to (streaming partners & media CDNs). */
    private val EXTRA_NAVIGATION_HOSTS = listOf(
        "netflix.com",
        "plex.tv",
        "trasre.com",
        "cloudfront.net",
        "bytefuntv.com"
    )

    fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            Uri.parse(url).host?.lowercase(Locale.ROOT) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun matches(host: String, domains: List<String>): Boolean =
        host.isNotEmpty() && domains.any { host == it || host.endsWith(".$it") }

    fun isMovieBoxHost(url: String?): Boolean = matches(hostOf(url), MOVIEBOX_HOSTS)

    fun isNavigationAllowed(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) return true
        val host = hostOf(url)
        return matches(host, MOVIEBOX_HOSTS) || matches(host, EXTRA_NAVIGATION_HOSTS)
    }

    fun isWebUrl(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://"))
}
