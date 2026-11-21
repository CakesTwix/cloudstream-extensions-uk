package com.lagradost.models

import java.util.concurrent.ConcurrentHashMap

class FenixSession(
    private val userAgent: String,
    private val client: okhttp3.OkHttpClient
) {

    private val playlists = ConcurrentHashMap<String, TimedValue<FenixPlaylist>>()
    private val episodes = ConcurrentHashMap<String, TimedValue<FenixPlaybackEpisodeResponse>>()
    private val mediaAccess = ConcurrentHashMap<String, FenixMediaAccess>()
    private val siteTokens = ConcurrentHashMap<String, String>()
    private val cookies = ConcurrentHashMap<String, TimedValue<String>>()
    private val posterUrls = ConcurrentHashMap<String, String>()
    private val posters = ConcurrentHashMap<String, ByteArray>()
    private val probes = ConcurrentHashMap<String, TimedValue<Boolean>>()
    private val streams = ConcurrentHashMap<String, TimedValue<List<FenixStream>>>()

    private var lastMediaAccess: FenixMediaAccess? = null
    private var lastCookie: String? = null

    private val embedSiteRegex = Regex("""window\.FENIX_EMBED_SITE\s*=\s*["']([^"']+)["']""")
    private val posterRegex = Regex(""""poster_url"\s*:\s*["']([^"']+)["']""")

    fun rememberCookie(code: String, header: String) {
        if (header.isBlank()) return
        cookies[code] = TimedValue(header)
        lastCookie = header
    }

    fun rememberEmbed(code: String, text: String) {
        if (text.isEmpty()) return

        embedSiteRegex.find(text)?.groupValues?.get(1)?.let { token ->
            if (token.isNotBlank()) siteTokens[code] = token
        }

        posterRegex.find(text)?.groupValues?.get(1)?.let { poster ->
            if (poster.startsWith("http")) posterUrls[code] = poster
        }

        FenixUtils.mediaAccess(text)?.let { access ->
            mediaAccess[code] = access
            lastMediaAccess = access
        }
    }

    fun putPlaylist(code: String, playlist: FenixPlaylist) {
        playlists[code] = TimedValue(playlist)
    }

    fun playlist(code: String, ttlMs: Long): FenixPlaylist? {
        return playlists[code]?.takeIf { it.isFresh(ttlMs) }?.value
    }

    fun putEpisode(key: String, response: FenixPlaybackEpisodeResponse) {
        episodes[key] = TimedValue(response)
    }

    fun episode(key: String, ttlMs: Long): FenixPlaybackEpisodeResponse? {
        return episodes[key]?.takeIf { it.isFresh(ttlMs) }?.value
    }

    fun cookie(code: String?, ttlMs: Long): String? {
        if (code == null) return lastCookie
        return cookies[code]?.takeIf { it.isFresh(ttlMs) }?.value ?: lastCookie
    }

    fun currentCookie(): String? = lastCookie

    fun siteToken(code: String?): String? {
        if (code == null) return null
        return siteTokens[code]
    }

    fun mediaToken(code: String?, fallback: String?): String? {
        return code?.let { mediaAccess[it]?.token } ?: fallback ?: lastMediaAccess?.token
    }

    fun posterUrl(code: String?): String? {
        if (code == null) return null
        return posterUrls[code]
    }

    fun cachedPoster(url: String): ByteArray? = posters[url]

    fun cachePoster(url: String, bytes: ByteArray) {
        posters[url] = bytes
    }

    fun streams(url: String, ttlMs: Long): List<FenixStream>? {
        return streams[url.substringBefore("?")]?.takeIf { it.isFresh(ttlMs) }?.value
    }

    fun putStreams(url: String, value: List<FenixStream>) {
        streams[url.substringBefore("?")] = TimedValue(value)
    }

    fun mediaHeaders(cookie: String?): Map<String, String> {
        val headers = FenixUtils.headers(userAgent).toMutableMap()
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie
        return headers
    }

    fun playbackHeaders(playbackToken: String, siteToken: String?, cookie: String?): Map<String, String> {
        return FenixUtils.playbackHeaders(userAgent, playbackToken, siteToken, cookie)
    }

    fun probe(url: String, cookie: String?, ttlMs: Long): Boolean {
        val key = url.substringBefore("?")
        probes[key]?.takeIf { it.isFresh(ttlMs) }?.let { return it.value }

        val alive = try {
            val builder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", FenixUtils.referer)
                .header("Origin", FenixUtils.defaultOrigin)
                .header("Accept", "*/*")
            if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)

            val response = client.newCall(builder.build()).execute()
            val code = response.code
            response.close()
            code in 200..299
        } catch (e: Exception) {
            false
        }

        probes[key] = TimedValue(alive)
        return alive
    }
}