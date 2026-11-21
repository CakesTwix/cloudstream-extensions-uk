package com.lagradost.models

import com.lagradost.cloudstream3.utils.AppUtils

object FenixUtils {

    const val referer = "https://fenixplay.xyz/"
    const val defaultOrigin = "https://fenixplay.xyz"

    private val episodeRegex = Regex("""[?&]ep=(\d+)""")
    private val editionRegex = Regex("""[?&]edition=([^&]+)""")
    private val signedRegex = Regex("""^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")
    private val mediaAccessRegex = Regex("""window\.FENIX_MEDIA_ACCESS\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
    private val playlistRegex = Regex("""window\.FENIX_PLAYLIST\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
    private val apiBaseRegex = Regex("""window\.FENIX_API_BASE\s*=\s*["']([^"']+)["']""")
    private val embedSiteRegex = Regex("""window\.FENIX_EMBED_SITE\s*=\s*["']([^"']+)["']""")

    fun code(embedUrl: String): String? {
        return embedUrl.substringAfter("/embed/", "").substringBefore("?").ifBlank { null }
    }

    fun episodeNumber(embedUrl: String): Int? {
        return episodeRegex.find(embedUrl)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun edition(embedUrl: String): String {
        return editionRegex.find(embedUrl)?.groupValues?.get(1) ?: "tv"
    }

    fun isSignedCode(code: String): Boolean {
        return signedRegex.matches(code)
    }

    fun origin(embedUrl: String): String {
        return try {
            val uri = java.net.URI(embedUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            defaultOrigin
        }
    }

    fun mediaAccess(html: String): FenixMediaAccess? {
        val match = mediaAccessRegex.find(html) ?: return null
        return try {
            AppUtils.parseJson<FenixMediaAccess>(match.groupValues[1])
        } catch (e: Exception) {
            null
        }
    }

    fun inlinePlaylist(html: String): FenixPlaylist? {
        val match = playlistRegex.find(html) ?: return null
        return try {
            AppUtils.parseJson<FenixPlaylist>(match.groupValues[1])
        } catch (e: Exception) {
            null
        }
    }

    fun apiBase(html: String): String? {
        return apiBaseRegex.find(html)?.groupValues?.get(1)
    }

    fun embedSite(html: String): String? {
        return embedSiteRegex.find(html)?.groupValues?.get(1)
    }

    fun playlistApiUrl(origin: String, code: String): String {
        return if (isSignedCode(code)) "$origin/api/playlist-code/$code" else "$origin/api/playlist/$code"
    }

    fun playbackUrl(slug: String, edition: String, number: Int): String {
        return "$defaultOrigin/api/playback/" +
            java.net.URLEncoder.encode(slug, "UTF-8") + "/episodes/" +
            java.net.URLEncoder.encode(edition, "UTF-8") + "/" + number
    }

    fun addToken(url: String, token: String?): String {
        if (token.isNullOrEmpty() || url.contains("token=")) return url
        return url + (if (url.contains("?")) "&" else "?") + "token=$token"
    }

    fun headers(userAgent: String): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer,
            "Origin" to defaultOrigin,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    fun playbackHeaders(
        userAgent: String,
        playbackToken: String,
        siteToken: String?,
        cookie: String? = null
    ): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "X-Fenix-Playback" to playbackToken,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to referer
        )
        if (!siteToken.isNullOrEmpty()) {
            headers["X-Fenix-Site"] = siteToken
        }
        if (!cookie.isNullOrEmpty()) {
            headers["Cookie"] = cookie
        }
        return headers
    }
}