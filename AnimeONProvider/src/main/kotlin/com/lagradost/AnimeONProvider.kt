package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnimeONProvider : MainAPI() {

    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$mainUrl/api/anime?search="
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

    private val hevcMain10Supported: Boolean by lazy { ProviderUtils.checkHevcMain10() }

    private val posterProxy = ByteProxyServer("poster", "application/octet-stream") {
        ProviderUtils.imageContentType(it)
    }
    private val subtitleProxy = ByteProxyServer("sub", "text/vtt; charset=utf-8")
    private val hlsProxy = ByteProxyServer("hls", "application/vnd.apple.mpegurl")

    private val posterFetchTasks = java.util.concurrent.ConcurrentHashMap<String, PosterFetchTask>()
    private val episodePosterCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val subtitleUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val episodeVideoCache = java.util.concurrent.ConcurrentHashMap<Int, TimedValue<String>>()
    private val moonHtmlCache = java.util.concurrent.ConcurrentHashMap<String, TimedValue<String>>()

    private var moonCookieHeader: String? = null
    private var moonCookieChecked = false
    private var moonCookies: Map<String, String>? = null
    private val moonCookieMutex = Mutex()

    private val htmlHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val posterHttpClient = htmlHttpClient.newBuilder()
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .build()
            chain.proceed(request)
        }
        .build()

    private val fenix = FenixSession(userAgent, htmlHttpClient)
    private val fenixPlaylistMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val fenixEpisodeMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private val backgroundScope = CoroutineScope(Dispatchers.IO)

    private val episodeResolutionCache =
        java.util.concurrent.ConcurrentHashMap<String, ResolvedEpisode>()
    private val episodeSequences =
        java.util.concurrent.ConcurrentHashMap<Int, List<String>>()
    private val episodeDataIndex =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    private val episodeResolveMutexes =
        java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    init {
        posterProxy.resolver = { key ->
            posterFetchTasks[key]?.let { fetchEpisodePosterBytes(it) }
        }
        posterProxy.onStored = { key ->
            posterFetchTasks[key]?.let { task ->
                episodePosterCache["${task.animeId}:${task.epNum}"] = posterProxy.url(key)
            }
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor {
        return okhttp3.Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            val newRequest = when {
                url.contains("moonanime") || url.contains("mooncdn") -> {
                    val base = MoonUtils.baseFromUrl(url)
                    request.newBuilder()
                        .header("Referer", "$base/")
                        .header("Origin", base)
                        .header("User-Agent", userAgent)
                        .build()
                }
                url.contains("fenixplay") || url.contains("fanixplay") -> {
                    val builder = request.newBuilder()
                        .header("Referer", FenixUtils.referer)
                        .header("Origin", FenixUtils.defaultOrigin)
                        .header("User-Agent", userAgent)
                    fenix.currentCookie()?.let { builder.header("Cookie", it) }
                    builder.build()
                }
                else -> request
            }

            chain.proceed(newRequest)
        }
    }

    override val mainPage = mainPageOf(
        "$apiUrl/seasons" to ProviderUtils.currentSeasonLabel(),
        "$mainUrl/api/stats/anime/popular/" to "Популярні аніме",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    private fun getMoonCookieHeader(): String? {
        moonCookieHeader?.let { return it }
        if (moonCookieChecked) return null
        moonCookieChecked = true

        for (base in MoonUtils.baseDomains) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("$base/")
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7")
                    .get()
                    .build()

                val response = posterHttpClient.newCall(request).execute()
                val setCookies = response.headers.toMultimap()["set-cookie"] ?: emptyList()
                response.close()

                val header = setCookies.mapNotNull { cookie ->
                    val value = cookie.substringBefore(";").trim()
                    if (value.isNotEmpty()) value else null
                }.joinToString("; ")

                if (header.isNotBlank()) {
                    moonCookieHeader = header
                    return header
                }
            } catch (e: Exception) {
            }
        }

        return null
    }

    private suspend fun getMoonCookies(): Map<String, String> {
        moonCookies?.let { return it }

        return moonCookieMutex.withLock {
            moonCookies?.let { return@withLock it }

            val all = mutableMapOf<String, String>()
            for (base in MoonUtils.baseDomains) {
                try {
                    val r = app.get(
                        "$base/",
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to "uk-UA,uk;q=0.9",
                        ),
                        cacheTime = 30
                    )
                    all.putAll(r.cookies)
                } catch (e: Exception) {
                }
            }

            moonCookies = all
            all
        }
    }

    private fun fetchPosterBytes(originalUrl: String, extraCookie: String? = null): ByteArray {
        if (originalUrl.isBlank() || originalUrl.contains("mooncdn.")) return ByteArray(0)

        return try {
            val requestBuilder = okhttp3.Request.Builder()
                .url(originalUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Accept-Language", "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "cross-site")
                .get()

            when {
                originalUrl.contains("moonanime") -> {
                    val base = MoonUtils.baseFromUrl(originalUrl)
                    requestBuilder.header("Referer", "$base/")
                    requestBuilder.header("Origin", base)

                    getMoonCookieHeader()?.let { requestBuilder.header("Cookie", it) }
                }
                originalUrl.contains("ashdi.vip") -> {
                    requestBuilder.header("Referer", "https://ashdi.vip/")
                    requestBuilder.header("Origin", "https://ashdi.vip")
                    requestBuilder.header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                    )
                    requestBuilder.header("Sec-Fetch-Dest", "document")
                    requestBuilder.header("Sec-Fetch-Mode", "navigate")
                    requestBuilder.header("Sec-Fetch-Site", "none")
                    requestBuilder.header("Cookie", "_ga=GA1.1.1899212404.1785951203; _gid=GA1.2.1071268545.1786650637")
                }
                originalUrl.contains("fenixplay") -> {
                    requestBuilder.header("Referer", FenixUtils.referer)
                    requestBuilder.header("Origin", FenixUtils.defaultOrigin)

                    val cookie = extraCookie ?: fenix.currentCookie()
                    if (!cookie.isNullOrEmpty()) requestBuilder.header("Cookie", cookie)
                }
            }

            val response = posterHttpClient.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                response.close()
                return ByteArray(0)
            }

            val bytes = response.body?.bytes() ?: ByteArray(0)
            response.close()
            bytes
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                ),
                timeout = 10
            ).text

            if (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("[")) {
                null
            } else {
                response
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchJsonOrNullSync(url: String): String? {
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Referer", mainUrl)
                .header("User-Agent", userAgent)
                .get()
                .build()

            val response = htmlHttpClient.newCall(request).execute()
            val text = if (response.isSuccessful) response.body?.string() else null
            response.close()

            if (text != null && !text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) {
                null
            } else {
                text
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchJsonOrNullSyncRetry(url: String, retries: Int = 3): String? {
        repeat(retries) {
            val result = fetchJsonOrNullSync(url)
            if (result != null) return result
        }
        return null
    }

    private fun httpGetText(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String> = emptyMap(),
        cookieSink: ((String) -> Unit)? = null
    ): String? {
        return try {
            val builder = okhttp3.Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (cookies.isNotEmpty()) {
                builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            val response = htmlHttpClient.newCall(builder.build()).execute()

            if (cookieSink != null) {
                val setCookies = response.headers.toMultimap()["set-cookie"] ?: emptyList()
                val header = setCookies.mapNotNull { cookie ->
                    val value = cookie.substringBefore(";").trim()
                    if (value.isNotEmpty()) value else null
                }.joinToString("; ")

                if (header.isNotBlank()) cookieSink(header)
            }

            val body = if (response.isSuccessful) response.body?.string() ?: "" else ""
            response.close()
            body
        } catch (e: Exception) {
            null
        }
    }

    private fun collectEpisodesVariant(
        baseUrl: String,
        includeAlt: String,
        fetch: (String) -> String?
    ): List<FundubEpisode> {
        val collected = mutableListOf<FundubEpisode>()
        val seenIDs = mutableSetOf<Int>()

        fetch("$baseUrl&skip=-1&includeAlternative=$includeAlt")?.let { json ->
            try {
                val eps = AppUtils.parseJson<SafePlayerEpisodes>(json).episodes
                collected.addAll(eps.filter { it.episode <= 0 && seenIDs.add(it.id) })
            } catch (e: Exception) {
            }
        }

        var skip = 0
        while (true) {
            val json = fetch("$baseUrl&skip=$skip&includeAlternative=$includeAlt") ?: break
            try {
                val eps = AppUtils.parseJson<SafePlayerEpisodes>(json).episodes
                if (eps.isEmpty()) break
                collected.addAll(eps.filter { seenIDs.add(it.id) })
                if (eps.size < 1000) break
            } catch (e: Exception) {
                break
            }
            skip += 1000
        }

        return collected
    }

    private fun collectEpisodes(baseUrl: String, fetch: (String) -> String?): List<FundubEpisode> {
        val seenIDs = mutableSetOf<Int>()
        return listOf("true", "false")
            .flatMap { collectEpisodesVariant(baseUrl, it, fetch) }
            .filter { seenIDs.add(it.id) }
    }

    private suspend fun buildFranchise(animeId: Int): List<SearchResponse> {
        val json = fetchJsonOrNull("$mainUrl/api/franchise/full/$animeId") ?: return emptyList()

        return try {
            val items = AppUtils.parseJson<List<FranchiseItem>>(json)
            items.filter { it.id != animeId }.map { item ->
                newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                    this.posterUrl = item.image?.preview?.let { posterApi.format(it) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveAnimeSlug(animeId: Int): String {
        val initial = fetchJsonOrNull("$apiUrl/$animeId") ?: return animeId.toString()

        return try {
            val redirect = AppUtils.parseJson<RedirectResponse>(initial)
            if (redirect?.moved == true && !redirect.slug.isNullOrEmpty()) redirect.slug!! else animeId.toString()
        } catch (e: Exception) {
            animeId.toString()
        }
    }

    private suspend fun fetchEpisodeInfoMap(slug: String): Map<Int, String> {
        val infoJson = fetchJsonOrNull("$mainUrl/api/anime/$slug/episodes-info") ?: return emptyMap()

        return try {
            val list = AppUtils.parseJson<List<EpisodeInfo>>(infoJson)
            list.associate { ep ->
                ep.episode to (ep.titleUa?.takeIf { it.isNotBlank() } ?: ep.title?.takeIf { it.isNotBlank() } ?: "")
            }.filter { it.value.isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun fetchEpisodeVideoUrlSync(episodeId: Int): String? {
        episodeVideoCache[episodeId]?.takeIf { it.isFresh(VIDEO_URL_TTL) }?.let { return it.value }

        val json = httpGetText(
            "$mainUrl/api/player/$episodeId/episode",
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl,
                "Accept" to "application/json, text/plain, */*"
            )
        ) ?: return null

        return try {
            val resp = AppUtils.parseJson<EpisodeVideoResponse>(json)
            val url = resp.videoUrl ?: resp.fileUrl
            if (!url.isNullOrEmpty()) {
                episodeVideoCache[episodeId] = TimedValue(url)
            }
            url
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getEpisodeVideoUrl(episodeId: Int): String? {
        episodeVideoCache[episodeId]?.takeIf { it.isFresh(VIDEO_URL_TTL) }?.let { return it.value }

        val json = fetchJsonOrNull("$mainUrl/api/player/$episodeId/episode") ?: return null

        return try {
            val response = AppUtils.parseJson<EpisodeVideoResponse>(json)
            val url = response.videoUrl ?: response.fileUrl
            if (!url.isNullOrEmpty()) {
                episodeVideoCache[episodeId] = TimedValue(url)
            }
            url
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getMoonIframeHtml(iframeUrl: String): String {
        val cleanUrl = MoonUtils.withPlayerParam(iframeUrl)
        moonHtmlCache[cleanUrl]?.takeIf { it.isFresh(IFRAME_HTML_TTL) }?.let { return it.value }

        val html = try {
            app.get(
                cleanUrl,
                headers = MoonUtils.iframeHeaders(mainUrl, userAgent),
                cacheTime = 0
            ).text
        } catch (e: Exception) {
            ""
        }

        if (html.isNotEmpty()) moonHtmlCache[cleanUrl] = TimedValue(html)
        return html
    }

    private fun getMoonIframeHtmlSync(iframeUrl: String): String {
        val cleanUrl = MoonUtils.withPlayerParam(iframeUrl)
        moonHtmlCache[cleanUrl]?.takeIf { it.isFresh(IFRAME_HTML_TTL) }?.let { return it.value }

        val html = httpGetText(cleanUrl, MoonUtils.iframeHeaders(mainUrl, userAgent)) ?: ""
        if (html.isNotEmpty()) moonHtmlCache[cleanUrl] = TimedValue(html)
        return html
    }

    private fun getAshdiRawPosterUrl(videoUrl: String): String? {
        val url = if (videoUrl.contains("?")) videoUrl else "$videoUrl?player=animeon.club"

        val html = httpGetText(
            url,
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none"
            ),
            mapOf(
                "_ga" to "GA1.1.1899212404.1785951203",
                "_gid" to "GA1.2.1071268545.1786650637"
            )
        )

        if (html.isNullOrEmpty() || html.contains("недоступний")) {
            return null
        }
        if (!html.contains("new Playerjs") && html.contains("country")) {
            return null
        }

        val patterns = listOf(
            Regex("""poster["']?\s*:\s*["']([^"']+)["']"""),
            Regex("""((?:https?:)?//[^"'\s]+screen\.jpg)"""),
            Regex("""((?:https?:)?//[^"'\s]+\.ashdi\.vip[^"'\s]*(?:screen|poster)[^"'\s]*)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val posterUrl = match.groupValues[1]
                return if (posterUrl.startsWith("http")) posterUrl else "https:$posterUrl"
            }
        }

        return null
    }

    private fun getMoonRawPosterUrl(iframeUrl: String): String? {
        if (!iframeUrl.contains("moonanime")) return null
        return MoonUtils.posterUrl(getMoonIframeHtmlSync(iframeUrl))
    }

    private fun fetchEpisodePosterBytes(task: PosterFetchTask): ByteArray? {
        for (episodeId in task.moonIds) {
            val bytes = fetchSingleEpisodePosterBytes(episodeId)
            if (ProviderUtils.isImageBytes(bytes)) return bytes
        }

        for (posterUrl in task.apiPosters) {
            val bytes = fetchPosterBytes(posterUrl)
            if (ProviderUtils.isImageBytes(bytes)) return bytes
        }

        for (episodeId in task.otherIds) {
            val bytes = fetchSingleEpisodePosterBytes(episodeId)
            if (ProviderUtils.isImageBytes(bytes)) return bytes
        }

        return null
    }

    private fun fetchSingleEpisodePosterBytes(episodeId: Int): ByteArray? {
        val videoUrl = fetchEpisodeVideoUrlSync(episodeId) ?: return null

        if (videoUrl.contains("fenixplay.xyz")) {
            val code = FenixUtils.code(videoUrl)
            val playlist = getFenixPlaylistSync(videoUrl) ?: return null
            val cookie = fenix.cookie(code, FENIX_COOKIE_TTL)

            val slug = playlist.slug
            val playbackToken = playlist.playback?.token
            val epNum = FenixUtils.episodeNumber(videoUrl) ?: 1
            val edition = FenixUtils.edition(videoUrl)

            if (!slug.isNullOrEmpty() && !playbackToken.isNullOrEmpty()) {
                val details = fetchFenixEpisodePlaybackSync(
                    slug,
                    playbackToken,
                    edition,
                    epNum,
                    fenix.siteToken(code),
                    cookie
                )
                val thumb = details?.episode?.thumbnail_url

                if (!thumb.isNullOrBlank()) {
                    val bytes = fetchPosterBytes(thumb, cookie)
                    if (ProviderUtils.isImageBytes(bytes)) return bytes

                    val tokenUrl = FenixUtils.addToken(thumb, fenix.mediaToken(code, details?.token))
                    if (tokenUrl != thumb) {
                        val tokenBytes = fetchPosterBytes(tokenUrl, cookie)
                        if (ProviderUtils.isImageBytes(tokenBytes)) return tokenBytes
                    }
                }
            }

            val publicPoster = fenix.posterUrl(code)
            if (!publicPoster.isNullOrBlank()) {
                fenix.cachedPoster(publicPoster)?.let { cached ->
                    if (ProviderUtils.isImageBytes(cached)) return cached
                }

                val bytes = fetchPosterBytes(publicPoster)
                if (ProviderUtils.isImageBytes(bytes)) {
                    fenix.cachePoster(publicPoster, bytes)
                    return bytes
                }
            }

            return null
        }

        val rawPosterUrl = when {
            videoUrl.contains("moonanime") -> getMoonRawPosterUrl(videoUrl)
            videoUrl.contains("ashdi.vip") -> getAshdiRawPosterUrl(videoUrl)
            else -> null
        } ?: return null

        val bytes = fetchPosterBytes(rawPosterUrl)
        if (!ProviderUtils.isImageBytes(bytes)) return null

        return bytes
    }

    private fun buildEpisodePoster(
        animeId: Int,
        epNum: Int,
        sources: List<EpisodeSource>,
        executor: java.util.concurrent.ExecutorService,
        prefetch: Boolean
    ): String? {
        episodePosterCache["$animeId:$epNum"]?.takeIf {
            posterProxy.has(it.substringAfterLast("?"))
        }?.let { return it }

        val moonIds = sources.filter { it.playerName.contains("Moon", true) }
            .map { it.episodeId }.distinct()

        val otherIds = sources.filterNot { it.playerName.contains("Moon", true) }
            .sortedWith(
                compareBy<EpisodeSource> {
                    when {
                        it.playerName.contains("Ashdi", true) -> 0
                        it.playerName.contains("Fenix", true) -> 1
                        else -> 2
                    }
                }
            )
            .map { it.episodeId }.distinct()

        val apiPosters = sources.mapNotNull { s ->
            s.apiPoster?.takeIf {
                it.isNotEmpty() && !it.contains("mooncdn.") && !it.contains("media.fenixplay.xyz")
            }
        }.distinct()

        if (moonIds.isEmpty() && otherIds.isEmpty() && apiPosters.isEmpty()) return null

        val key = posterProxy.newKey()
        val task = PosterFetchTask(moonIds, otherIds, apiPosters, animeId, epNum)
        posterFetchTasks[key] = task

        if (prefetch) {
            executor.submit {
                if (!posterProxy.has(key)) {
                    val bytes = fetchEpisodePosterBytes(task)
                    if (bytes != null && bytes.isNotEmpty()) {
                        posterProxy.put(key, bytes)
                        episodePosterCache["$animeId:$epNum"] = posterProxy.url(key)
                    }
                }
            }
        }

        return posterProxy.url(key)
    }

    private suspend fun fetchFenixPlaylist(embedUrl: String): FenixPlaylist? {
        val code = FenixUtils.code(embedUrl) ?: return null

        val embedResponse = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                cacheTime = 0
            )
        } catch (e: Exception) {
            null
        }

        val html = try {
            embedResponse?.text ?: ""
        } catch (e: Exception) {
            ""
        }

        try {
            embedResponse?.cookies?.let { jar ->
                if (jar.isNotEmpty()) {
                    fenix.rememberCookie(code, jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }
        } catch (e: Exception) {
        }

        fenix.rememberEmbed(code, html)

        FenixUtils.inlinePlaylist(html)?.let { p ->
            if (!p.slug.isNullOrEmpty() || p.playback != null) {
                fenix.putPlaylist(code, p)
                return p
            }
        }

        val apiBase = FenixUtils.apiBase(html) ?: FenixUtils.origin(embedUrl)

        return try {
            val json = app.get(
                FenixUtils.playlistApiUrl(apiBase, code),
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl,
                    "Accept" to "application/json, */*"
                ),
                cacheTime = 0
            ).text

            fenix.rememberEmbed(code, json)

            val playlist = AppUtils.parseJson<FenixPlaylist>(json)
            if (!playlist.slug.isNullOrEmpty()) {
                fenix.putPlaylist(code, playlist)
            }
            playlist
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFenixPlaylistSync(embedUrl: String): FenixPlaylist? {
        val code = FenixUtils.code(embedUrl) ?: return null

        val html = httpGetText(
            embedUrl,
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
            ),
            cookieSink = { header -> fenix.rememberCookie(code, header) }
        ) ?: ""

        fenix.rememberEmbed(code, html)

        FenixUtils.inlinePlaylist(html)?.let { p ->
            if (!p.slug.isNullOrEmpty() || p.playback != null) {
                fenix.putPlaylist(code, p)
                return p
            }
        }

        val json = httpGetText(
            FenixUtils.playlistApiUrl(FenixUtils.origin(embedUrl), code),
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to embedUrl,
                "Accept" to "application/json, */*"
            ),
            cookieSink = { header -> fenix.rememberCookie(code, header) }
        ) ?: return null

        fenix.rememberEmbed(code, json)

        return try {
            val playlist = AppUtils.parseJson<FenixPlaylist>(json)
            if (!playlist.slug.isNullOrEmpty()) {
                fenix.putPlaylist(code, playlist)
            }
            playlist
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getFenixPlaylist(videoUrl: String): FenixPlaylist? {
        val code = FenixUtils.code(videoUrl) ?: return null
        fenix.playlist(code, FENIX_PLAYLIST_TTL)?.let { return it }

        val mutex = fenixPlaylistMutexes.getOrPut(code) { Mutex() }
        return mutex.withLock {
            fenix.playlist(code, FENIX_PLAYLIST_TTL) ?: fetchFenixPlaylist(videoUrl)?.also { p ->
                if (!p.slug.isNullOrEmpty()) fenix.putPlaylist(code, p)
            }
        }
    }

    private fun getFenixPlaylistSync(videoUrl: String): FenixPlaylist? {
        val code = FenixUtils.code(videoUrl) ?: return null
        fenix.playlist(code, FENIX_PLAYLIST_TTL)?.let { return it }
        val p = fetchFenixPlaylistSync(videoUrl)
        if (p != null && !p.slug.isNullOrEmpty()) fenix.putPlaylist(code, p)
        return p
    }

    private suspend fun fetchFenixEpisodePlayback(
        slug: String,
        playbackToken: String,
        edition: String,
        number: Int,
        siteToken: String?,
        cookie: String?
    ): FenixPlaybackEpisodeResponse? {
        return try {
            val response = app.get(
                FenixUtils.playbackUrl(slug, edition, number),
                headers = fenix.playbackHeaders(playbackToken, siteToken, cookie),
                cacheTime = 0
            ).text
            AppUtils.parseJson<FenixPlaybackEpisodeResponse>(response)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFenixEpisodePlaybackSync(
        slug: String,
        playbackToken: String,
        edition: String,
        number: Int,
        siteToken: String?,
        cookie: String?
    ): FenixPlaybackEpisodeResponse? {
        val json = httpGetText(
            FenixUtils.playbackUrl(slug, edition, number),
            fenix.playbackHeaders(playbackToken, siteToken, cookie)
        ) ?: return null

        return try {
            AppUtils.parseJson<FenixPlaybackEpisodeResponse>(json)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun prepareFenixStreams(url: String, cookie: String?): List<FenixStream> {
        fenix.streams(url, MASTER_TTL)?.let { return it }

        val text = try {
            app.get(url, headers = fenix.mediaHeaders(cookie), cacheTime = 0).text
        } catch (e: Exception) {
            ""
        }

        val variants = if (text.isEmpty()) emptyList() else HlsUtils.splitMaster(text, url)

        val result = if (variants.isEmpty()) {
            listOf(FenixStream(url, 0, null))
        } else {
            val supported = variants.filter {
                ProviderUtils.isVariantSupported(it.codecs, it.width, it.height)
            }
            val chosen = supported.ifEmpty { listOfNotNull(variants.minByOrNull { it.height }) }

            if (chosen.size == 1 && variants.size == 1) {
                listOf(FenixStream(url, chosen.first().height, chosen.first().codecs))
            } else {
                hlsProxy.ensureStarted()
                chosen.sortedByDescending { it.height }.map { variant ->
                    val key = hlsProxy.newKey(".m3u8")
                    hlsProxy.put(key, variant.master.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
                    FenixStream(hlsProxy.url(key), variant.height, variant.codecs)
                }
            }
        }

        fenix.putStreams(url, result)
        return result
    }

    private suspend fun fenixTrackStreams(
        hlsUrl: String?,
        avcUrl: String?,
        mediaToken: String?,
        cookie: String?
    ): List<FenixStream> {
        val native = hlsUrl?.takeIf { it.isNotBlank() }
        val avc = avcUrl?.takeIf { it.isNotBlank() }

        val ordered = if (hevcMain10Supported) {
            listOfNotNull(native, avc)
        } else {
            listOfNotNull(avc, native)
        }

        val candidates = ordered.map { FenixUtils.addToken(it, mediaToken) }.distinct()
        if (candidates.isEmpty()) return emptyList()

        val alive = if (candidates.size == 1) {
            candidates.first()
        } else {
            candidates.firstOrNull { fenix.probe(it, cookie, PROBE_TTL) } ?: candidates.first()
        }

        return prepareFenixStreams(alive, cookie)
    }

    private fun fenixStreamName(baseName: String, codecs: String?): String {
        val label = ProviderUtils.codecLabel(codecs)
        return if (label != null) "$baseName · $label" else baseName
    }

    private suspend fun resolveMoonContent(contentUrl: String): String? {
        return try {
            val base = MoonUtils.baseFromUrl(contentUrl)

            val response = app.get(
                contentUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "*/*",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "$base/",
                    "Origin" to base,
                    "Sec-Fetch-Site" to "same-site",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty",
                ),
                cookies = getMoonCookies(),
                allowRedirects = false,
                cacheTime = 0
            )

            val location = response.headers["location"] ?: response.headers["Location"]

            if (!location.isNullOrEmpty()) {
                location
            } else {
                val body = response.text.trim()
                if (body.startsWith("http")) body else null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Популярні аніме") {
            val currentDate = java.text.SimpleDateFormat(
                "yyyy-MM-dd",
                java.util.Locale.ENGLISH
            ).format(java.util.Date())

            val skip = (page - 1) * 24
            val url = "${request.data}$currentDate?skip=$skip&take=24&sort=views-desc"

            val jsonText = fetchJsonOrNull(url)
                ?: return newHomePageResponse(request.name, emptyList())

            val parsedJSON = try {
                AppUtils.parseJson<PopularAnimeResponse>(jsonText)
            } catch (e: Exception) {
                null
            }

            val items = parsedJSON?.results ?: emptyList()

            if (items.isEmpty()) {
                return newHomePageResponse(request.name, emptyList())
            }

            return newHomePageResponse(
                request.name,
                items.map { item ->
                    newAnimeSearchResponse(item.anime.titleUa, "anime/${item.anime.id}", TvType.Anime) {
                        this.posterUrl = item.anime.image?.preview?.let { posterApi.format(it) }
                    }
                },
                hasNext = items.size >= 24
            )
        }

        if (request.data.contains("seasons") && page != 1) {
            return newHomePageResponse(emptyList())
        }

        val jsonText = fetchJsonOrNull(
            if (request.data.contains("%d")) request.data.format(page) else request.data
        ) ?: return newHomePageResponse(request.name, emptyList())

        return if (!request.data.contains("seasons")) {
            val parsedJSON = try {
                AppUtils.parseJson<SafeNewAnimeModel>(jsonText)
            } catch (e: Exception) {
                return newHomePageResponse(request.name, emptyList())
            }

            newHomePageResponse(request.name, parsedJSON.results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        } else {
            val parsedJSON = try {
                AppUtils.parseJson<List<LocalResult>>(jsonText)
            } catch (e: Exception) {
                return newHomePageResponse(request.name, emptyList())
            }

            newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val id = query.toIntOrNull()

        if (id != null) {
            val animeById = searchById(id)
            if (animeById != null) return listOf(animeById)
        }

        val url = "$searchApi$query"
        val jsonText = fetchJsonOrNull(url) ?: return emptyList()

        return try {
            val response = AppUtils.parseJson<SafeSearchApiResponse>(jsonText)

            response.results.map { result ->
                newAnimeSearchResponse(result.titleUa, "anime/${result.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(result.image.preview)
                    addDubStatus(isDub = true, result.episodes)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun searchById(id: Int): SearchResponse? {
        val slug = resolveAnimeSlug(id)
        val jsonText = fetchJsonOrNull("$apiUrl/$slug") ?: return null

        val anime = try {
            AppUtils.parseJson<SafeAnimeInfoModel>(jsonText)
        } catch (e: Exception) {
            return null
        }

        return newAnimeSearchResponse(anime.titleUa, "anime/${anime.id}", TvType.Anime) {
            this.posterUrl = anime.image?.preview?.let { posterApi.format(it) }
            addDubStatus(isDub = true, anime.episodes)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toIntOrNull()
            ?: throw Exception("Invalid anime ID in URL: $url")

        val slug = resolveAnimeSlug(animeId)

        val jsonText = fetchJsonOrNull("$apiUrl/$slug")
            ?: throw Exception("Failed to load anime $animeId")

        val animeJSON = AppUtils.parseJson<SafeAnimeInfoModel>(jsonText)
            ?: throw Exception("Failed to parse anime $animeId")

        val posterUrl = animeJSON.image?.preview?.let { posterApi.format(it) } ?: ""
        val genres = animeJSON.genres?.map { it.nameUa } ?: emptyList()

        val showStatus = if (animeJSON.status?.contains("ongoing") == true) {
            ShowStatus.Ongoing
        } else {
            ShowStatus.Completed
        }

        val tvType = with(animeJSON.type ?: "") {
            when {
                contains("tv") -> TvType.Anime
                contains("OVA") || contains("ONA") || contains("Спеціальний випуск") -> TvType.OVA
                contains("movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        val episodeInfoMap = fetchEpisodeInfoMap(slug)

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")

        if (translationsJson != null) {
            try {
                val translations = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations

                val episodeSources = java.util.concurrent.ConcurrentHashMap<Int, MutableList<EpisodeSource>>()
                val seenSourceKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                val poolSize = (translations.sumOf { it.player.size } * 2).coerceIn(4, 24)
                val episodeExecutor = java.util.concurrent.Executors.newFixedThreadPool(poolSize)
                val futures = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.Future<CollectedEpisodes>>()

                try {
                    for (translation in translations) {
                        val translationId = translation.translation.id

                        for (player in translation.player) {
                            val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=1000&playerId=${player.id}&translationId=$translationId"
                            val tName = translation.translation.name
                            val pName = player.name

                            for (includeAlt in listOf("true", "false")) {
                                futures.add(episodeExecutor.submit(java.util.concurrent.Callable<CollectedEpisodes> {
                                    CollectedEpisodes(
                                        tName,
                                        pName,
                                        collectEpisodesVariant(baseUrl, includeAlt) { u -> fetchJsonOrNullSync(u) }
                                    )
                                }))
                            }
                        }
                    }

                    val deadline = System.currentTimeMillis() + 20_000

                    for (future in futures) {
                        try {
                            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(500)
                            val result = future.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue

                            for (ep in result.episodes) {
                                val key = "${result.translationName}|${result.playerName}|${ep.id}"
                                if (!seenSourceKeys.add(key)) continue

                                val sources = episodeSources.getOrPut(ep.episode) { mutableListOf() }
                                sources.add(
                                    EpisodeSource(
                                        translationName = result.translationName,
                                        playerName = result.playerName,
                                        episodeId = ep.id,
                                        episodeNum = ep.episode,
                                        apiPoster = ep.poster
                                    )
                                )
                            }
                        } catch (e: Exception) {
                        }
                    }
                } finally {
                    episodeExecutor.shutdown()
                }

                posterProxy.ensureStarted()

                val posterPrefetchExecutor = java.util.concurrent.Executors.newFixedThreadPool(
                    (episodeSources.size / 2).coerceIn(4, 16)
                )

                episodeSources.keys.sorted().forEach { epNum ->
                    val sources = episodeSources[epNum] ?: return@forEach

                    val epPoster = buildEpisodePoster(
                        animeId,
                        epNum,
                        sources,
                        posterPrefetchExecutor,
                        epNum <= PREFETCH_EPISODES
                    )
                    val episodeName = episodeInfoMap[epNum]?.takeIf { it.isNotBlank() }

                    episodes.add(
                        newEpisode(ProviderUtils.buildEpisodeDataJson(sources)).apply {
                            this.name = episodeName
                            this.episode = epNum
                            this.posterUrl = epPoster
                        }
                    )
                }

                posterPrefetchExecutor.shutdown()

            } catch (e: Exception) {
            }
        }

        val franchise = buildFranchise(animeId)

        val sequence = episodes.map { it.data }
        episodeSequences[animeId] = sequence
        sequence.forEachIndexed { idx, d -> episodeDataIndex[d] = animeId to idx }

        if (sequence.isNotEmpty()) {
            val firstData = sequence.first()
            backgroundScope.launch {
                try {
                    resolveEpisodeToCache(firstData)
                } catch (_: Exception) {
                }
            }
        }

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
                this.posterUrl = posterUrl
                this.engName = animeJSON.titleEn
                this.tags = genres
                this.plot = animeJSON.description

                addTrailer(animeJSON.trailer)

                this.showStatus = showStatus
                this.duration = animeJSON.episodeTime?.let { ProviderUtils.extractIntFromString(it) }
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.score = Score.from10(animeJSON.rating)

                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId)

                this.recommendations = franchise
            }
        } else {
            val backgroundImage = if (animeJSON.backgroundImage.isNullOrBlank()) {
                posterUrl
            } else {
                animeJSON.backgroundImage
            }

            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType, animeId.toString()) {
                this.posterUrl = posterUrl
                this.tags = genres
                this.plot = animeJSON.description

                addTrailer(animeJSON.trailer)

                this.duration = animeJSON.episodeTime?.let { ProviderUtils.extractIntFromString(it) }
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score = Score.from10(animeJSON.rating)

                addMalId(animeJSON.malId)

                this.recommendations = franchise
            }
        }
    }

    private suspend fun resolveEpisodeToCache(data: String) {
        val mutex = episodeResolveMutexes.getOrPut(data) { Mutex() }
        mutex.withLock {
            episodeResolutionCache[data]?.let { cached ->
                if (cached.isUsable(LINKS_TTL)) return@withLock
            }

            val capturedLinks = mutableListOf<ExtractorLink>()
            val capturedSubs = mutableListOf<SubtitleFile>()

            resolveEpisodeLinks(data, { capturedLinks.add(it) }, { capturedSubs.add(it) })

            if (capturedLinks.isNotEmpty()) {
                episodeResolutionCache.putIfAbsent(data, ResolvedEpisode(capturedLinks, capturedSubs))
            }
        }
    }

    private fun prefetchNext(currentData: String) {
        val loc = episodeDataIndex[currentData] ?: return
        val seq = episodeSequences[loc.first] ?: return
        val nextIdx = loc.second + 1
        if (nextIdx >= seq.size) return

        val nextData = seq[nextIdx]
        episodeResolutionCache[nextData]?.let {
            if (it.isUsable(LINKS_TTL)) return
        }

        backgroundScope.launch {
            try {
                resolveEpisodeToCache(nextData)
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val animeId = data.trim().toIntOrNull()

        if (animeId != null) {
            return loadMovieLinks(animeId, subtitleCallback, callback)
        }

        episodeResolutionCache[data]?.let { cached ->
            if (cached.isUsable(LINKS_TTL)) {
                cached.links.forEach { callback(it) }
                cached.subtitles.forEach { subtitleCallback(it) }
                prefetchNext(data)
                return true
            }
        }

        val mutex = episodeResolveMutexes.getOrPut(data) { Mutex() }
        return mutex.withLock {
            episodeResolutionCache[data]?.let { cached ->
                if (cached.isUsable(LINKS_TTL)) {
                    cached.links.forEach { callback(it) }
                    cached.subtitles.forEach { subtitleCallback(it) }
                    prefetchNext(data)
                    return@withLock true
                }
            }

            val capturedLinks = mutableListOf<ExtractorLink>()
            val capturedSubs = mutableListOf<SubtitleFile>()

            val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                capturedLinks.add(link)
                callback(link)
            }
            val wrappedSubCallback: (SubtitleFile) -> Unit = { sub ->
                capturedSubs.add(sub)
                subtitleCallback(sub)
            }

            val result = resolveEpisodeLinks(data, wrappedCallback, wrappedSubCallback)

            if (capturedLinks.isNotEmpty()) {
                episodeResolutionCache.putIfAbsent(data, ResolvedEpisode(capturedLinks, capturedSubs))
            }

            prefetchNext(data)

            result
        }
    }

    private suspend fun emitM3u8(
        streamUrl: String,
        sourceName: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val streams = M3u8Helper.generateM3u8(
            source = sourceName,
            streamUrl = streamUrl,
            referer = referer,
            headers = headers
        )

        ProviderUtils.filterStreams(streams).forEach {
            callback(ProviderUtils.fixMovieExtractorLink(it, sourceName))
        }
    }

    private suspend fun emitFenixEpisode(
        ep: FenixPlaybackEpisode,
        mediaToken: String?,
        cookie: String?,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val headers = fenix.mediaHeaders(cookie)

        ep.tracks?.forEach { track ->
            val label = track.label ?: track.language ?: "Fenix"
            val baseName = "$label (Fenix)"

            fenixTrackStreams(track.hls_url, track.cast_avc_url, mediaToken, cookie).forEach { stream ->
                callback(
                    ExtractorLink(
                        source = name,
                        name = fenixStreamName(baseName, stream.codecs),
                        url = stream.url,
                        referer = FenixUtils.referer,
                        quality = if (stream.quality > 0) stream.quality else com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
            }
        }

        if (ep.tracks.isNullOrEmpty()) {
            fenixTrackStreams(ep.hls_master_url, ep.cast_avc_url, mediaToken, cookie).forEach { stream ->
                callback(
                    ExtractorLink(
                        source = name,
                        name = fenixStreamName(sourceName, stream.codecs),
                        url = stream.url,
                        referer = FenixUtils.referer,
                        quality = if (stream.quality > 0) stream.quality else com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
            }
        }

        emitFenixSubtitles(ep, subtitleCallback, headers) { FenixUtils.addToken(it, mediaToken) }
    }

    private suspend fun processVideoUrl(
        videoUrl: String,
        playerName: String,
        sourceName: String,
        fallbackEpNum: Int?,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val isAshdi = playerName.contains("Ashdi", ignoreCase = true)
        val isFenix = playerName.contains("Fenix", ignoreCase = true)

        if (isFenix && videoUrl.contains("fenixplay.xyz")) {
            val code = FenixUtils.code(videoUrl) ?: return false
            val playlist = getFenixPlaylist(videoUrl)

            val slug = playlist?.slug
            val playbackToken = playlist?.playback?.token
            val epNum = FenixUtils.episodeNumber(videoUrl) ?: fallbackEpNum
            val edition = FenixUtils.edition(videoUrl)

            if (slug.isNullOrEmpty() || playbackToken.isNullOrEmpty() || epNum == null) return false

            val cookie = fenix.cookie(code, FENIX_COOKIE_TTL)
            val site = fenix.siteToken(code)
            val cacheKey = "$code|$edition|$epNum"

            val mutex = fenixEpisodeMutexes.getOrPut(cacheKey) { Mutex() }
            val details = mutex.withLock {
                fenix.episode(cacheKey, FENIX_EPISODE_TTL)
                    ?: fetchFenixEpisodePlayback(slug, playbackToken, edition, epNum, site, cookie)?.also {
                        fenix.putEpisode(cacheKey, it)
                    }
            } ?: return false

            val ep = details.episode ?: return false

            emitFenixEpisode(
                ep,
                fenix.mediaToken(code, details.token),
                cookie,
                sourceName,
                callback,
                subtitleCallback
            )
            return true
        }

        if (isAshdi) {
            if (videoUrl.contains("ashdi.vip")) {
                processAshdiIframe(videoUrl, sourceName, isMovie, callback)
                return true
            }
            if (videoUrl.contains(".m3u8")) {
                emitM3u8(videoUrl, sourceName, "https://ashdi.vip", emptyMap(), callback)
                return true
            }
            return false
        }

        if (videoUrl.contains("moonanime")) {
            if (videoUrl.contains("m3u8")) {
                emitM3u8(
                    videoUrl,
                    sourceName,
                    "${MoonUtils.baseFromUrl(videoUrl)}/",
                    MoonUtils.videoHeaders(videoUrl, userAgent),
                    callback
                )
                return true
            }

            val (rawFile, subUrl) = getMoonFile(videoUrl)
            if (rawFile.isNotEmpty()) {
                invokeSubtitles(subUrl, subtitleCallback)
                processMoonRawFile(rawFile, sourceName, isMovie, callback)
                return true
            }
        }

        return false
    }

    private suspend fun resolveEpisodeLinks(
        data: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val sources: List<EpisodeSource> = try {
            AppUtils.parseJson<List<EpisodeSource>>(data)
        } catch (e: Exception) {
            return false
        }

        if (sources.isEmpty()) return false

        val seenLinks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val seenSubs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val emitLock = Any()

        val safeCallback: (ExtractorLink) -> Unit = { link ->
            val key = "${link.name}|${link.quality}|${link.url.substringBefore("?")}"
            if (seenLinks.add(key)) {
                synchronized(emitLock) { callback(link) }
            }
        }

        val safeSubCallback: (SubtitleFile) -> Unit = { sub ->
            if (seenSubs.add("${sub.lang}|${sub.url}")) {
                synchronized(emitLock) { subtitleCallback(sub) }
            }
        }

        val results = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    try {
                        val videoUrl = getEpisodeVideoUrl(source.episodeId)
                        if (videoUrl.isNullOrEmpty()) return@async false

                        processVideoUrl(
                            videoUrl,
                            source.playerName,
                            "${source.translationName} (${source.playerName})",
                            source.episodeNum,
                            false,
                            safeCallback,
                            safeSubCallback
                        )
                    } catch (e: Exception) {
                        false
                    }
                }
            }.awaitAll()
        }

        return results.any { it }
    }

    private suspend fun loadMovieLinks(
        animeId: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false

        var foundAny = false

        val seenLinks = mutableSetOf<String>()
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            val cleanUrl = link.url.substringBefore("?")
            val key = "${link.name}|${link.quality}|$cleanUrl"
            if (seenLinks.add(key)) {
                callback(link)
            }
        }

        try {
            val translations = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations

            for (translation in translations) {
                val translationId = translation.translation.id

                for (player in translation.player) {
                    val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=1000&playerId=${player.id}&translationId=$translationId"
                    val collected = collectEpisodes(baseUrl) { u -> fetchJsonOrNullSyncRetry(u) }

                    val sourceName = "${translation.translation.name} (${player.name})"

                    if (collected.isEmpty()) {
                        val directJson = fetchJsonOrNull("$mainUrl/api/player/${player.id}/$translationId") ?: continue

                        try {
                            val directSource = AppUtils.parseJson<DirectPlayerResponse>(directJson)
                            val finalUrl = directSource.videoUrl ?: directSource.fileUrl

                            if (!finalUrl.isNullOrEmpty()) {
                                val found = processVideoUrl(
                                    finalUrl,
                                    player.name,
                                    sourceName,
                                    1,
                                    true,
                                    safeCallback,
                                    subtitleCallback
                                )
                                if (found) foundAny = true
                            }
                        } catch (e: Exception) {
                        }
                        continue
                    }

                    for (ep in collected) {
                        val videoUrl = getEpisodeVideoUrl(ep.id)
                        if (videoUrl.isNullOrEmpty()) continue

                        try {
                            val found = processVideoUrl(
                                videoUrl,
                                player.name,
                                sourceName,
                                ep.episode,
                                true,
                                safeCallback,
                                subtitleCallback
                            )
                            if (found) foundAny = true
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }

        return foundAny
    }

    private suspend fun fetchSubtitleBytes(url: String, headers: Map<String, String>): ByteArray {
        return try {
            app.get(url, headers = headers, cacheTime = 0).body.bytes()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private suspend fun emitFenixSubtitles(
        episode: FenixPlaybackEpisode,
        subtitleCallback: (SubtitleFile) -> Unit,
        headers: Map<String, String>,
        addToken: (String) -> String
    ) {
        subtitleProxy.ensureStarted()

        val subsToAdd = mutableListOf<SubToAdd>()

        episode.subtitles?.forEach { sub ->
            val baseLang = sub.language ?: sub.label ?: "UK"
            val variants = mutableListOf<Pair<String?, String?>>()

            variants.add(sub.kind to (sub.url ?: sub.styled_url))

            sub.renditions?.forEach { (kind, rendition) ->
                variants.add(kind to (rendition.url ?: rendition.styled_url))
            }

            variants.forEach { (kind, u) ->
                if (u.isNullOrEmpty()) return@forEach

                val kindLabel = when {
                    kind.isNullOrEmpty() || kind == "full" -> ""
                    kind == "signs" -> " · Написи"
                    kind.equals("sdh", true) -> " · SDH"
                    else -> " · $kind"
                }
                val studioLabel = sub.voice_studio_name?.let { " · $it" } ?: ""
                val priority = if (sub.is_default == true) 0 else 1

                subsToAdd.add(SubToAdd("$baseLang$kindLabel$studioLabel", u, priority))
            }
        }

        val seenSubs = mutableSetOf<String>()

        subsToAdd.sortedBy { it.priority }.forEach { s ->
            val cleanUrl = s.url.substringBefore("?")
            if (!seenSubs.add("${s.lang}|$cleanUrl")) return@forEach

            val cacheId = "${s.lang}|$cleanUrl"
            subtitleUrlCache[cacheId]?.takeIf { subtitleProxy.has(it) }?.let { key ->
                subtitleCallback.invoke(newSubtitleFile(s.lang, subtitleProxy.url(key)))
                return@forEach
            }

            val tokenUrl = addToken(s.url)
            var bytes = fetchSubtitleBytes(tokenUrl, headers)

            if (bytes.isEmpty() && tokenUrl != s.url) {
                bytes = fetchSubtitleBytes(s.url, headers)
            }

            if (bytes.isEmpty()) {
                subtitleCallback.invoke(newSubtitleFile(s.lang, s.url))
                return@forEach
            }

            val ext = ProviderUtils.subtitleExtFromUrl(s.url)
            val key = subtitleProxy.newKey(ext)
            subtitleProxy.put(key, bytes, ProviderUtils.subtitleContentType(ext))
            subtitleUrlCache[cacheId] = key

            subtitleCallback.invoke(newSubtitleFile(s.lang, subtitleProxy.url(key)))
        }
    }

    private fun moonSourceName(sourceName: String, url: String): String {
        return if (ProviderUtils.isVp9Webm(url)) "$sourceName · VP9" else sourceName
    }

    private suspend fun processMoonRawFile(
        rawFile: String,
        sourceName: String,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        if (rawFile.startsWith("[")) {
            val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")

            qualityRegex.findAll(rawFile).forEach { match ->
                val qualityInt = match.groupValues[1].replace("p", "").toIntOrNull()
                    ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                val qUrl = match.groupValues[2]
                val headers = MoonUtils.videoHeaders(qUrl, userAgent)

                when {
                    qUrl.contains(".m3u8") -> {
                        emitM3u8(qUrl, sourceName, "${MoonUtils.baseFromUrl(qUrl)}/", headers, callback)
                    }
                    qUrl.contains("moonanime") -> {
                        val finalUrl = resolveMoonContent(qUrl)
                        if (!finalUrl.isNullOrEmpty()) {
                            val displayName = moonSourceName(sourceName, finalUrl)
                            callback(
                                ProviderUtils.fixMovieExtractorLink(
                                    ExtractorLink(
                                        source = name,
                                        name = displayName,
                                        url = finalUrl,
                                        referer = "${MoonUtils.baseFromUrl(qUrl)}/",
                                        quality = qualityInt,
                                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                        headers = headers
                                    ),
                                    displayName
                                )
                            )
                        }
                    }
                    else -> {
                        val displayName = moonSourceName(sourceName, qUrl)
                        callback(
                            ProviderUtils.fixMovieExtractorLink(
                                ExtractorLink(
                                    source = name,
                                    name = displayName,
                                    url = qUrl,
                                    referer = "${MoonUtils.baseFromUrl(qUrl)}/",
                                    quality = qualityInt,
                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                    headers = headers
                                ),
                                displayName
                            )
                        )
                    }
                }
            }
            return
        }

        val headers = MoonUtils.videoHeaders(rawFile, userAgent)
        val referer = "${MoonUtils.baseFromUrl(rawFile)}/"

        if (rawFile.contains(".m3u8")) {
            emitM3u8(rawFile, sourceName, referer, headers, callback)
            return
        }

        val finalUrl = if (rawFile.contains("moonanime")) {
            resolveMoonContent(rawFile) ?: return
        } else {
            rawFile
        }

        val displayName = moonSourceName(sourceName, finalUrl)

        callback(
            ProviderUtils.fixMovieExtractorLink(
                ExtractorLink(
                    source = name,
                    name = displayName,
                    url = finalUrl,
                    referer = referer,
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                    headers = headers
                ),
                displayName
            )
        )
    }

    private suspend fun processAshdiIframe(
        iframeUrl: String,
        sourceName: String,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanUrl = iframeUrl
                .replace(Regex("""\?season=null\?"""), "?")
                .replace(Regex("""\?season=null$"""), "")

            val url = if (cleanUrl.contains("?")) cleanUrl else "$cleanUrl?player=animeon.club"

            val html = app.get(
                url,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                cacheTime = 0
            ).text

            val fileIndex = html.indexOf("file:'")
            if (fileIndex == -1) return

            val urlStart = fileIndex + 6
            val urlEnd = html.indexOf('\'', urlStart)
            if (urlEnd == -1) return

            val masterUrl = html.substring(urlStart, urlEnd)
            if (masterUrl.isNotEmpty() && masterUrl.endsWith(".m3u8")) {
                emitM3u8(masterUrl, sourceName, "https://ashdi.vip/", emptyMap(), callback)
            }
        } catch (e: Exception) {
        }
    }

    private suspend fun getMoonFile(iframeUrl: String): Pair<String, String?> {
        val cleanUrl = MoonUtils.withPlayerParam(iframeUrl)
        val html = getMoonIframeHtml(iframeUrl)

        if (html.isNotEmpty()) {
            val decodedJs = MoonUtils.playerJs(html)

            if (decodedJs.isNotEmpty()) {
                val xorKey = MoonUtils.xorKey(decodedJs)
                var subtitleUrl: String? = null

                if (!xorKey.isNullOrEmpty()) {
                    subtitleUrl = MoonUtils.subtitleString(decodedJs, xorKey)

                    val rawFile = MoonUtils.rawFile(decodedJs, xorKey)
                    if (rawFile != null) {
                        return Pair(rawFile, subtitleUrl)
                    }
                }

                val contentUrl = MoonUtils.contentUrl(decodedJs)
                if (contentUrl != null) {
                    val resolved = resolveMoonContent(contentUrl)
                    if (!resolved.isNullOrEmpty()) {
                        return Pair(resolved, subtitleUrl)
                    }
                }
            }
        }

        val hash = MoonUtils.iframeHash(cleanUrl)

        if (!hash.isNullOrEmpty()) {
            val cdn = MoonUtils.baseFromUrl(cleanUrl).replace("://moonanime", "://s.moonanime")
            val qualityResults = mutableListOf<String>()

            for (quality in listOf(1080, 720, 480, 360)) {
                val resolved = resolveMoonContent("$cdn/content/v/$hash/$quality/")
                if (!resolved.isNullOrEmpty()) {
                    qualityResults.add("[${quality}p]$resolved")
                }
            }

            if (qualityResults.isNotEmpty()) {
                return Pair(qualityResults.joinToString("."), null)
            }
        }

        return Pair("", null)
    }

    private suspend fun invokeSubtitles(subUrl: String?, subtitleCallback: (SubtitleFile) -> Unit) {
        if (subUrl == null) return
        subtitleProxy.ensureStarted()

        subUrl.split("|||").forEach { entry ->
            val parts = entry.split("::", limit = 2)

            if (parts.size == 2) {
                val lang = parts[0]
                val url = parts[1]
                val base = MoonUtils.baseFromUrl(url)
                val cacheId = "$lang|${url.substringBefore("?")}"

                subtitleUrlCache[cacheId]?.takeIf { subtitleProxy.has(it) }?.let { key ->
                    subtitleCallback.invoke(newSubtitleFile(lang, subtitleProxy.url(key)))
                    return@forEach
                }

                try {
                    val bytes = app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "$base/",
                            "Origin" to base,
                            "Accept" to "*/*",
                            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                        ),
                        cacheTime = 0
                    ).body.bytes()

                    val ext = ProviderUtils.subtitleExtFromUrl(url)
                    val key = subtitleProxy.newKey(ext)
                    subtitleProxy.put(key, bytes, ProviderUtils.subtitleContentType(ext))
                    subtitleUrlCache[cacheId] = key

                    subtitleCallback.invoke(newSubtitleFile(lang, subtitleProxy.url(key)))
                } catch (e: Exception) {
                    subtitleCallback.invoke(newSubtitleFile(lang, url))
                }
            }
        }
    }

    companion object {
        private const val VIDEO_URL_TTL = 600_000L
        private const val IFRAME_HTML_TTL = 300_000L
        private const val FENIX_PLAYLIST_TTL = 1_800_000L
        private const val FENIX_COOKIE_TTL = 7_200_000L
        private const val FENIX_EPISODE_TTL = 1_800_000L
        private const val PROBE_TTL = 300_000L
        private const val MASTER_TTL = 300_000L
        private const val LINKS_TTL = 300_000L
        private const val PREFETCH_EPISODES = 3
    }
}