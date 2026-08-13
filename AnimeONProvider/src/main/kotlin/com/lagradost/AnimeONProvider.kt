package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*

class AnimeONProvider : MainAPI() {

    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor {
        return okhttp3.Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            val newRequest = if (url.contains("moonanime.art") || url.contains("s.moonanime.art")) {
                request.newBuilder()
                    .header("Referer", "https://moonanime.art/")
                    .header("Origin", "https://moonanime.art")
                    .header("User-Agent", userAgent)
                    .build()
            } else {
                request
            }

            chain.proceed(newRequest)
        }
    }

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

    private var posterProxyPort: Int = 0
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val posterSources = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var moonCookieHeader: String? = null

    private val posterHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .build()
            chain.proceed(request)
        }
        .build()

    private fun currentSeasonLabel(): String {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1

        val seasonUa = when (month) {
            12, 1, 2 -> "зимового"
            3, 4, 5 -> "весняного"
            6, 7, 8 -> "літнього"
            else -> "осіннього"
        }

        return "Аніме ${seasonUa} сезону"
    }

    override val mainPage = mainPageOf(
        "$apiUrl/seasons" to currentSeasonLabel(),
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    private data class SafeResult(
        val id: Int,
        val titleUa: String,
        val description: String? = null,
        val image: Image,
        val malId: Int? = null,
        val rating: Double? = 0.0,
        val status: String? = null,
        val type: String? = null,
        val genres: List<Genres>? = null,
        val episodes: Int? = null
    )

    private data class SafeNewAnimeModel(
        val results: List<SafeResult>,
        val totalCount: Int? = 0
    )

    private data class SafeSearchApiResponse(
        val results: List<SafeResult>,
        val totalCount: Int? = 0
    )

    private data class SafeAnimeInfoModel(
        val id: Int,
        val titleUa: String,
        val titleEn: String? = null,
        val description: String? = null,
        val image: Image? = null,
        val backgroundImage: String? = null,
        val trailer: String? = null,
        val rating: Double? = 0.0,
        val status: String? = "completed",
        val type: String? = "tv",
        val genres: List<Genres>? = null,
        val episodes: Int? = 0,
        val episodeTime: String? = "",
        val releaseDate: String? = null,
        val malId: Int? = 0
    )

    private data class SafeTranslationsResponse(
        val translations: List<TranslationItem>
    )

    private data class SafePlayerEpisodes(
        val episodes: List<FundubEpisode>
    )

    private data class LocalResult(
        val id: Int,
        val titleUa: String,
        val slug: String?,
        val episodesAired: Int?,
        val rating: String?,
        val image: Image,
        val description: String? = null
    )

    private data class RedirectResponse(
        val moved: Boolean? = null,
        val redirectTo: String? = null,
        val slug: String? = null,
    )

    private data class EpisodeSource(
        val translationName: String,
        val playerName: String,
        val episodeId: Int,
        val apiPoster: String? = null
    )

    private data class DirectPlayerResponse(
        val videoUrl: String? = null,
        val fileUrl: String? = null,
    )

    private data class EpisodeVideoResponse(
        val videoUrl: String? = null,
        val fileUrl: String? = null,
    )

    private data class FranchiseItem(
        val id: Int,
        val slug: String?,
        val titleUa: String,
        val type: String?,
        val image: Image?,
        val releaseDate: String?,
    )

    private data class EpisodeInfo(
        val id: Int? = null,
        val episode: Int,
        val title: String? = null,
        val titleUa: String? = null,
        val aired: String? = null,
        val filler: Boolean? = null,
        val recap: Boolean? = null,
    )

    private fun fixMovieExtractorLink(link: ExtractorLink, sourceName: String): ExtractorLink {
        val cleanQuality = when {
            link.url.contains("/1080/") -> 1080
            link.url.contains("/720/") -> 720
            link.url.contains("/480/") -> 480
            link.url.contains("/360/") -> 360
            else -> when (link.quality) {
                in 900..1150 -> 1080
                in 600..899 -> 720
                in 400..599 -> 480
                in 240..399 -> 360
                else -> link.quality
            }
        }

        return ExtractorLink(
            source = link.source,
            name = sourceName,
            url = link.url,
            referer = link.referer,
            quality = cleanQuality,
            type = link.type,
            headers = link.headers,
            extractorData = link.extractorData
        )
    }

    @Synchronized
    private fun ensurePosterProxy() {
        if (posterProxyPort != 0) return

        val serverSocket = java.net.ServerSocket(0)
        posterProxyPort = serverSocket.localPort

        Thread {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()

                    Thread {
                        try {
                            val input = client.getInputStream()
                            val reader = input.bufferedReader()
                            val line = reader.readLine() ?: return@Thread
                            
                            val key = line.substringAfter("?").substringBefore(" ").trim()
                            val originalUrl = posterSources[key]
                            val out = client.getOutputStream()

                            if (originalUrl.isNullOrEmpty()) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                out.flush()
                                return@Thread
                            }

                            var body = posterCache[originalUrl]

                            if (body == null) {
                                val fetched = fetchPosterBytes(originalUrl)

                                if (fetched.isNotEmpty()) {
                                    posterCache[originalUrl] = fetched
                                    body = fetched
                                }
                            }

                            val finalBody = body ?: ByteArray(0)

                            if (finalBody.isEmpty()) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val contentType = when {
                                    originalUrl.contains(".png", true) -> "image/png"
                                    originalUrl.contains(".jpg", true) -> "image/jpeg"
                                    originalUrl.contains(".jpeg", true) -> "image/jpeg"
                                    originalUrl.contains(".webp", true) -> "image/webp"
                                    else -> "application/octet-stream"
                                }

                                val response = buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Type: $contentType\r\n")
                                    append("Content-Length: ${finalBody.size}\r\n")
                                    append("Cache-Control: public, max-age=86400\r\n")
                                    append("Access-Control-Allow-Origin: *\r\n")
                                    append("Connection: close\r\n")
                                    append("\r\n")
                                }

                                out.write(response.toByteArray())
                                out.write(finalBody)
                            }

                            out.flush()
                        } catch (e: Exception) {
                            println("Proxy error: ${e.message}")
                        } finally {
                            try {
                                client.close()
                            } catch (_: Exception) {
                            }
                        }
                    }.also { it.isDaemon = true }.start()
                } catch (e: Exception) {
                    println("Server error: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun getMoonCookieHeader(): String? {
        moonCookieHeader?.let { return it }

        return try {
            val request = okhttp3.Request.Builder()
                .url("https://moonanime.art/")
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val response = posterHttpClient.newCall(request).execute()
            val setCookies = response.headers.toMultimap()["set-cookie"] ?: emptyList()
            response.close()

            val cookiePairs = setCookies.mapNotNull { cookie ->
                val value = cookie.substringBefore(";").trim()
                if (value.isNotEmpty()) value else null
            }

            val header = cookiePairs.joinToString("; ")

            if (header.isNotBlank()) {
                moonCookieHeader = header
                header
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchPosterBytes(originalUrl: String): ByteArray {
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
                originalUrl.contains("s.moonanime.art") || 
                originalUrl.contains("moonanime.art") || 
                originalUrl.contains("mooncdn.") -> {
                    requestBuilder.header("Referer", "https://moonanime.art/")
                    requestBuilder.header("Origin", "https://moonanime.art")
                }
                originalUrl.contains("ashdi.vip") -> {
                    requestBuilder.header("Referer", "https://ashdi.vip/")
                    requestBuilder.header("Origin", "https://ashdi.vip")
                    requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    requestBuilder.header("Sec-Fetch-Dest", "document")
                    requestBuilder.header("Sec-Fetch-Mode", "navigate")
                    requestBuilder.header("Sec-Fetch-Site", "none")
                    requestBuilder.header("Cookie", "_ga=GA1.1.1899212404.1785951203; _gid=GA1.2.1071268545.1786650637")
                }
            }

            getMoonCookieHeader()?.let { cookie ->
                if (originalUrl.contains("moon")) {
                    requestBuilder.header("Cookie", cookie)
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

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                )
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

    private suspend fun fetchJsonWithRetry(url: String, retries: Int = 3): String? {
        repeat(retries) {
            val result = fetchJsonOrNull(url)
            if (result != null) return result
        }

        return null
    }

    private suspend fun resolveAnimeApiUrl(animeId: Int): String {
        val initial = fetchJsonOrNull("$apiUrl/$animeId") ?: return "$apiUrl/$animeId"

        return try {
            val redirect = AppUtils.parseJson<RedirectResponse>(initial)

            if (redirect?.moved == true && !redirect.slug.isNullOrEmpty()) {
                "$apiUrl/${redirect.slug}"
            } else {
                "$apiUrl/$animeId"
            }
        } catch (e: Exception) {
            "$apiUrl/$animeId"
        }
    }

    private suspend fun fetchEpisodeInfoMap(animeId: Int): Map<Int, String> {
        val slugJson = fetchJsonOrNull("$apiUrl/$animeId") ?: return emptyMap()

        return try {
            val redirect = AppUtils.parseJson<RedirectResponse>(slugJson)

            val slugOrId = if (redirect?.moved == true && !redirect.slug.isNullOrEmpty()) {
                redirect.slug!!
            } else {
                animeId.toString()
            }

            val infoJson = fetchJsonOrNull("$mainUrl/api/anime/$slugOrId/episodes-info") ?: return emptyMap()
            val list = AppUtils.parseJson<List<EpisodeInfo>>(infoJson)

            list.associate { ep ->
                ep.episode to (ep.titleUa?.takeIf { it.isNotBlank() } ?: ep.title?.takeIf { it.isNotBlank() } ?: "")
            }.filter { it.value.isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun getAshdiPoster(videoUrl: String?): String? {
        if (videoUrl.isNullOrEmpty()) return null
        if (!videoUrl.contains("ashdi.vip")) return null

        val url = if (videoUrl.contains("?")) {
            videoUrl
        } else {
            "$videoUrl?player=animeon.club"
        }

        val html = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Cookie" to "_ga=GA1.1.1899212404.1785951203; _gid=GA1.2.1071268545.1786650637",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none"
                ),
                cacheTime = 0
            ).text
        } catch (e: Exception) {
            ""
        }

        if (html.isNotEmpty() && !html.contains("недоступний")) {
            val posterPatterns = listOf(
                Regex("""poster\s*:\s*["']([^"']+)["']"""),
                Regex("""((?:https?:)?//[^"'\s]+screen\.jpg)"""),
                Regex("""((?:https?:)?//[^"'\s]+\.ashdi\.vip[^"'\s]*(?:screen|poster)[^"'\s]*)"""),
                Regex("""((?:https?:)?//[^"'\s]+ashdi\.vip/content/[^"'\s]+\.(?:jpg|jpeg|png|webp))""")
            )

            for (pattern in posterPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val posterUrl = match.groupValues[1]
                    val result = if (posterUrl.startsWith("http")) posterUrl else "https:$posterUrl"
                    return result
                }
            }
        }

        return null
    }

    private suspend fun getMoonPoster(iframeUrl: String): String? {
        if (!iframeUrl.contains("/iframe/")) return null

        val cleanUrl = if (iframeUrl.contains("player=")) {
            iframeUrl
        } else {
            "$iframeUrl${if (iframeUrl.contains("?")) "&" else "?"}player=animeon.club"
        }

        return try {
            val html = app.get(
                cleanUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "https://animeon.club/",
                    "X-Requested-With" to "mark.via.gp",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-User" to "?1",
                    "Sec-Fetch-Dest" to "document",
                    "Upgrade-Insecure-Requests" to "1"
                ),
                cacheTime = 0
            ).text

            if (html.isEmpty()) {
                null
            } else {
                val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
                var posterUrl: String? = null

                for (match in atobRegex.findAll(html)) {
                    val decoded = moonOuterDecode(match.groupValues[1])

                    if (!decoded.contains("poster")) continue

                    posterUrl = Regex("""poster\s*:\s*["'](https?://[^"']+)["']""")
                        .find(decoded)?.groupValues?.get(1)

                    if (posterUrl != null) break

                    val xorKey = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
                        .find(decoded)?.groupValues?.get(1) ?: continue

                    val posterEnc = Regex("""poster\s*:\s*_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                        .find(decoded)?.groupValues?.get(1) ?: continue

                    val result = moonDecrypt(posterEnc, xorKey)

                    if (result.startsWith("http")) {
                        posterUrl = result
                        break
                    }
                }

                if (posterUrl == null) {
                    null
                } else {
                    ensurePosterProxy()

                    val key = java.util.UUID.randomUUID().toString().replace("-", "")
                    posterSources[key] = posterUrl

                    "http://127.0.0.1:$posterProxyPort/poster?$key"
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveMoonContent(contentUrl: String): String? {
        return try {
            val cookieResponse = app.get(
                "https://moonanime.art/",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9",
                ),
                cacheTime = 0
            )

            val cookies = cookieResponse.cookies

            val response = app.get(
                contentUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "*/*",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "https://moonanime.art/",
                    "Origin" to "https://moonanime.art",
                    "Sec-Fetch-Site" to "same-site",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty",
                ),
                cookies = cookies,
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

    private suspend fun getEpisodeVideoUrl(episodeId: Int): String? {
        val url = "$mainUrl/api/player/$episodeId/episode"
        val json = fetchJsonOrNull(url) ?: return null
        
        return try {
            val response = AppUtils.parseJson<EpisodeVideoResponse>(json)
            response.videoUrl ?: response.fileUrl
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())

            val currentDate = java.text.SimpleDateFormat(
                "EEE MMM dd yyyy",
                java.util.Locale.ENGLISH
            ).format(java.util.Date())

            val jsonText = fetchJsonOrNull("${request.data}$currentDate?withView=false")
                ?: return newHomePageResponse(request.name, emptyList())

            val parsedJSON = AppUtils.parseJson<List<LocalResult>>(jsonText)

            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }

        if (request.data.contains("seasons") && page != 1) {
            return newHomePageResponse(emptyList())
        }

        val jsonText = fetchJsonOrNull(
            if (request.data.contains("%d")) request.data.format(page) else request.data
        ) ?: return newHomePageResponse(request.name, emptyList())

        return if (!request.data.contains("seasons")) {
            val parsedJSON = AppUtils.parseJson<SafeNewAnimeModel>(jsonText)

            newHomePageResponse(request.name, parsedJSON.results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        } else {
            val parsedJSON = AppUtils.parseJson<List<LocalResult>>(jsonText)

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
        val realUrl = resolveAnimeApiUrl(id)
        val jsonText = fetchJsonOrNull(realUrl) ?: return null

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

        val realApiUrl = resolveAnimeApiUrl(animeId)

        val jsonText = fetchJsonOrNull(realApiUrl)
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

        val episodeInfoMap = fetchEpisodeInfoMap(animeId)

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")

        if (translationsJson != null) {
            try {
                val translations = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations
                val episodeSources = mutableMapOf<Int, MutableList<EpisodeSource>>()

                for (translation in translations) {
                    val translationId = translation.translation.id

                    for (player in translation.player) {
                        val collected = mutableListOf<FundubEpisode>()
                        val seenIDs = mutableSetOf<Int>()

                        val baseUrl =
                            "$mainUrl/api/player/$animeId/episodes?take=100&playerId=${player.id}&translationId=$translationId&includeAlternative=true"

                        val epJsonMinus1 = fetchJsonOrNull("$baseUrl&skip=-1")

                        if (epJsonMinus1 != null) {
                            val eps = try {
                                AppUtils.parseJson<SafePlayerEpisodes>(epJsonMinus1).episodes
                            } catch (e: Exception) {
                                null
                            }

                            eps?.filter { it.episode <= 0 && seenIDs.add(it.id) }?.let {
                                collected.addAll(it)
                            }
                        }

                        val maxSkip = if (player.episodesCount > 0) {
                            (player.episodesCount / 100 + 1) * 100
                        } else {
                            11000
                        }

                        var skip = 0

                        while (skip <= maxSkip) {
                            val epJson = fetchJsonOrNull("$baseUrl&skip=$skip") ?: break

                            val eps = try {
                                AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes
                            } catch (e: Exception) {
                                null
                            }

                            if (eps.isNullOrEmpty()) break

                            val newEps = eps.filter { seenIDs.add(it.id) }
                            collected.addAll(newEps)

                            if (eps.size < 100) break

                            skip += 100
                        }

                        for (ep in collected) {
                            episodeSources.getOrPut(ep.episode) { mutableListOf() }.add(
                                EpisodeSource(
                                    translationName = translation.translation.name,
                                    playerName = player.name,
                                    episodeId = ep.id,
                                    apiPoster = ep.poster
                                )
                            )
                        }
                    }
                }

                episodeSources.keys.sorted().forEach { epNum ->
                    val sources = episodeSources[epNum] ?: return@forEach

                    var epPoster: String? = null

                    epPoster = sources.firstNotNullOfOrNull { s ->
                        s.apiPoster?.takeIf { 
                            it.isNotEmpty() && !it.contains("mooncdn.") 
                        }
                    }

                    if (epPoster.isNullOrEmpty()) {
                        val firstSource = sources.firstOrNull()
                        if (firstSource != null) {
                            val videoUrl = getEpisodeVideoUrl(firstSource.episodeId)
                            if (!videoUrl.isNullOrEmpty()) {
                                if (videoUrl.contains("moonanime.art")) {
                                    epPoster = getMoonPoster(videoUrl)
                                } else if (videoUrl.contains("ashdi.vip")) {
                                    epPoster = getAshdiPoster(videoUrl)
                                }
                            }
                        }
                    }

                    if (epPoster != null && epPoster.contains("mooncdn.")) {
                        epPoster = null
                    }

                    val dataJson = org.json.JSONArray().also { arr ->
                        sources.forEach { s ->
                            arr.put(org.json.JSONObject().apply {
                                put("translationName", s.translationName)
                                put("playerName", s.playerName)
                                put("episodeId", s.episodeId)
                            })
                        }
                    }.toString()

                    val episodeName = episodeInfoMap[epNum]?.takeIf { it.isNotBlank() }

                    episodes.add(
                        newEpisode(dataJson).apply {
                            this.name = episodeName
                            this.episode = epNum
                            this.posterUrl = epPoster
                        }
                    )
                }
            } catch (e: Exception) {
            }
        }

        val franchise = buildFranchise(animeId)

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
                this.posterUrl = posterUrl
                this.engName = animeJSON.titleEn
                this.tags = genres
                this.plot = animeJSON.description

                addTrailer(animeJSON.trailer)

                this.showStatus = showStatus
                this.duration = animeJSON.episodeTime?.let { extractIntFromString(it) }
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

                this.duration = animeJSON.episodeTime?.let { extractIntFromString(it) }
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score = Score.from10(animeJSON.rating)

                addMalId(animeJSON.malId)

                this.recommendations = franchise
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

        val sources: List<EpisodeSource> = try {
            AppUtils.parseJson<List<EpisodeSource>>(data)
        } catch (e: Exception) {
            return false
        }

        if (sources.isEmpty()) return false

        var foundAny = false

        val moonVideoHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://moonanime.art/",
            "Origin" to "https://moonanime.art",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Dest" to "video",
            "X-Requested-With" to "mark.via.gp"
        )

        for (source in sources) {
            val sourceName = "${source.translationName} (${source.playerName})"
            val isAshdi = source.playerName.contains("Ashdi", ignoreCase = true)
            
            val videoUrl = getEpisodeVideoUrl(source.episodeId)
            
            if (videoUrl.isNullOrEmpty()) continue

            try {
                if (isAshdi) {
                    if (videoUrl.contains("ashdi.vip")) {
                        processAshdiIframe(videoUrl, sourceName, isMovie = false, callback)
                        foundAny = true
                    } else if (videoUrl.contains(".m3u8")) {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = videoUrl,
                            referer = "https://ashdi.vip"
                        )

                        val filtered = streams.dropLast(1)

                        if (filtered.isNotEmpty()) {
                            filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        } else {
                            streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        }

                        foundAny = true
                    }
                } else {
                    if (videoUrl.contains("moonanime.art")) {
                        if (videoUrl.contains("m3u8")) {
                            val streams = M3u8Helper.generateM3u8(
                                source = sourceName,
                                streamUrl = videoUrl,
                                referer = "https://moonanime.art/",
                                headers = moonVideoHeaders
                            )

                            val filtered = streams.dropLast(1)

                            if (filtered.isNotEmpty()) {
                                filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                            } else {
                                streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                            }

                            foundAny = true
                        } else {
                            val (rawFile, subUrl) = getMoonFile(videoUrl)

                            if (rawFile.isNotEmpty()) {
                                invokeSubtitles(subUrl, subtitleCallback)
                                processMoonRawFile(rawFile, sourceName, isMovie = false, callback)
                                foundAny = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        return foundAny
    }

    private suspend fun loadMovieLinks(
        animeId: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false

        var foundAny = false

        val moonVideoHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://moonanime.art/",
            "Origin" to "https://moonanime.art",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Dest" to "video",
            "X-Requested-With" to "mark.via.gp"
        )

        try {
            val translations = AppUtils.parseJson<SafeTranslationsResponse>(translationsJson).translations

            for (translation in translations) {
                val translationId = translation.translation.id

                for (player in translation.player) {
                    val collected = mutableListOf<FundubEpisode>()
                    val seenIDs = mutableSetOf<Int>()

                    val baseUrl =
                        "$mainUrl/api/player/$animeId/episodes?take=100&playerId=${player.id}&translationId=$translationId&includeAlternative=true"

                    val epJsonMinus1 = fetchJsonWithRetry("$baseUrl&skip=-1")

                    if (epJsonMinus1 != null) {
                        val eps = try {
                            AppUtils.parseJson<SafePlayerEpisodes>(epJsonMinus1).episodes
                        } catch (e: Exception) {
                            null
                        }

                        eps?.filter { it.episode <= 0 && seenIDs.add(it.id) }?.let {
                            collected.addAll(it)
                        }
                    }

                    val maxSkip = if (player.episodesCount > 0) {
                        (player.episodesCount / 100 + 1) * 100
                    } else {
                        11000
                    }

                    var skip = 0

                    while (skip <= maxSkip) {
                        val epJson = fetchJsonWithRetry("$baseUrl&skip=$skip") ?: break

                        val eps = try {
                            AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes
                        } catch (e: Exception) {
                            null
                        }

                        if (eps.isNullOrEmpty()) break

                        val newEps = eps.filter { seenIDs.add(it.id) }
                        collected.addAll(newEps)

                        if (eps.size < 100) break

                        skip += 100
                    }

                    val sourceName = "${translation.translation.name} (${player.name})"
                    val isAshdi = player.name.contains("Ashdi", ignoreCase = true)

                    if (collected.isEmpty()) {
                        val directJson = fetchJsonOrNull("$mainUrl/api/player/${player.id}/${translation.translation.id}")

                        if (directJson != null) {
                            try {
                                val directSource = AppUtils.parseJson<DirectPlayerResponse>(directJson)
                                val videoUrl = directSource.videoUrl
                                val fileUrl = directSource.fileUrl

                                if (!videoUrl.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
                                    val finalUrl = videoUrl ?: fileUrl!!
                                    
                                    if (isAshdi) {
                                        if (finalUrl.contains("ashdi.vip")) {
                                            processAshdiIframe(finalUrl, sourceName, isMovie = true, callback)
                                            foundAny = true
                                        } else if (finalUrl.contains(".m3u8")) {
                                            val streams = M3u8Helper.generateM3u8(
                                                source = sourceName,
                                                streamUrl = finalUrl,
                                                referer = "https://ashdi.vip"
                                            )

                                            val filtered = streams.dropLast(1)

                                            if (filtered.isNotEmpty()) {
                                                filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                            } else {
                                                streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                            }

                                            foundAny = true
                                        }
                                    } else {
                                        if (finalUrl.contains("moonanime.art")) {
                                            if (finalUrl.contains("m3u8")) {
                                                val streams = M3u8Helper.generateM3u8(
                                                    source = sourceName,
                                                    streamUrl = finalUrl,
                                                    referer = "https://moonanime.art/",
                                                    headers = moonVideoHeaders
                                                )

                                                val filtered = streams.dropLast(1)

                                                if (filtered.isNotEmpty()) {
                                                    filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                                } else {
                                                    streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                                }

                                                foundAny = true
                                            } else {
                                                val (rawFile, subUrl) = getMoonFile(finalUrl)

                                                if (rawFile.isNotEmpty()) {
                                                    invokeSubtitles(subUrl, subtitleCallback)
                                                    processMoonRawFile(rawFile, sourceName, isMovie = true, callback)
                                                    foundAny = true
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }

                        continue
                    }

                    for (ep in collected) {
                        val videoUrl = getEpisodeVideoUrl(ep.id)
                        
                        if (videoUrl.isNullOrEmpty()) continue

                        try {
                            if (isAshdi) {
                                if (videoUrl.contains("ashdi.vip")) {
                                    processAshdiIframe(videoUrl, sourceName, isMovie = true, callback)
                                    foundAny = true
                                } else if (videoUrl.contains(".m3u8")) {
                                    val streams = M3u8Helper.generateM3u8(
                                        source = sourceName,
                                        streamUrl = videoUrl,
                                        referer = "https://ashdi.vip"
                                    )

                                    val filtered = streams.dropLast(1)

                                    if (filtered.isNotEmpty()) {
                                        filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                    } else {
                                        streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                    }

                                    foundAny = true
                                }
                            } else {
                                if (videoUrl.contains("moonanime.art")) {
                                    if (videoUrl.contains("m3u8")) {
                                        val streams = M3u8Helper.generateM3u8(
                                            source = sourceName,
                                            streamUrl = videoUrl,
                                            referer = "https://moonanime.art/",
                                            headers = moonVideoHeaders
                                        )

                                        val filtered = streams.dropLast(1)

                                        if (filtered.isNotEmpty()) {
                                            filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                        } else {
                                            streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                        }

                                        foundAny = true
                                    } else {
                                        val (rawFile, subUrl) = getMoonFile(videoUrl)

                                        if (rawFile.isNotEmpty()) {
                                            invokeSubtitles(subUrl, subtitleCallback)
                                            processMoonRawFile(rawFile, sourceName, isMovie = true, callback)
                                            foundAny = true
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }

        return foundAny
    }

    private suspend fun processMoonRawFile(
        rawFile: String,
        sourceName: String,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        val moonVideoHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.8,en;q=0.7",
            "Referer" to "https://moonanime.art/",
            "Origin" to "https://moonanime.art",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Dest" to "video",
            "X-Requested-With" to "mark.via.gp"
        )

        if (rawFile.startsWith("[")) {
            val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")

            qualityRegex.findAll(rawFile).forEach { match ->
                val qualityStr = match.groupValues[1]
                val qUrl = match.groupValues[2]

                val qualityInt = qualityStr.replace("p", "").toIntOrNull()
                    ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value

                when {
                    qUrl.contains(".m3u8") -> {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = qUrl,
                            referer = "https://moonanime.art/",
                            headers = moonVideoHeaders
                        )

                        val filtered = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams

                        finalStreams.forEach {
                            callback(fixMovieExtractorLink(it, sourceName))
                        }
                    }

                    qUrl.contains("s.moonanime.art") || qUrl.contains("moonanime.art/content") -> {
                        val finalUrl = resolveMoonContent(qUrl)

                        if (!finalUrl.isNullOrEmpty()) {
                            val link = ExtractorLink(
                                source = name,
                                name = sourceName,
                                url = finalUrl,
                                referer = "https://moonanime.art/",
                                quality = qualityInt,
                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                headers = moonVideoHeaders
                            )

                            callback(fixMovieExtractorLink(link, sourceName))
                        }
                    }

                    else -> {
                        val link = ExtractorLink(
                            source = name,
                            name = sourceName,
                            url = qUrl,
                            referer = "https://moonanime.art/",
                            quality = qualityInt,
                            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                            headers = moonVideoHeaders
                        )

                        callback(fixMovieExtractorLink(link, sourceName))
                    }
                }
            }
        } else if (rawFile.contains(".m3u8")) {
            val streams = M3u8Helper.generateM3u8(
                source = sourceName,
                streamUrl = rawFile,
                referer = "https://moonanime.art/",
                headers = moonVideoHeaders
            )

            val filtered = streams.dropLast(1)
            val finalStreams = if (filtered.isNotEmpty()) filtered else streams

            finalStreams.forEach {
                callback(fixMovieExtractorLink(it, sourceName))
            }
        } else if (rawFile.contains("s.moonanime.art") || rawFile.contains("moonanime.art/content")) {
            val finalUrl = resolveMoonContent(rawFile)

            if (!finalUrl.isNullOrEmpty()) {
                val link = ExtractorLink(
                    source = name,
                    name = sourceName,
                    url = finalUrl,
                    referer = "https://moonanime.art/",
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                    headers = moonVideoHeaders
                )

                callback(fixMovieExtractorLink(link, sourceName))
            }
        } else {
            val link = ExtractorLink(
                source = name,
                name = sourceName,
                url = rawFile,
                referer = "https://moonanime.art/",
                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                headers = moonVideoHeaders
            )

            callback(fixMovieExtractorLink(link, sourceName))
        }
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

            val url = if (cleanUrl.contains("?")) {
                cleanUrl
            } else {
                "$cleanUrl?player=animeon.club"
            }

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

            if (fileIndex != -1) {
                val urlStart = fileIndex + 6
                val urlEnd = html.indexOf('\'', urlStart)

                if (urlEnd != -1) {
                    val masterUrl = html.substring(urlStart, urlEnd)

                    if (masterUrl.isNotEmpty() && masterUrl.endsWith(".m3u8")) {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = masterUrl,
                            referer = "https://ashdi.vip/"
                        )

                        val filtered = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams

                        finalStreams.forEach { link ->
                            callback(fixMovieExtractorLink(link, sourceName))
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val cleanEncoded = encoded.replace("\\s".toRegex(), "")
            val decoded = android.util.Base64.decode(cleanEncoded, android.util.Base64.DEFAULT)
            val decryptedBytes = ByteArray(decoded.size)

            for (i in decoded.indices) {
                decryptedBytes[i] = ((decoded[i].toInt() and 0xFF) xor key[i % key.length].code).toByte()
            }

            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun moonOuterDecode(base64Blob: String): String {
        return try {
            val raw = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)

            if (raw.size < 33) return ""

            val state0 = raw[0].toInt() and 0xFF
            val key = raw.sliceArray(1 until 33)
            val data = raw.sliceArray(33 until raw.size)
            val result = ByteArray(data.size)

            var state = state0

            for (i in data.indices) {
                val d = data[i].toInt() and 0xFF
                val k = key[i % 32].toInt() and 0xFF

                result[i] = (d xor k xor state).toByte()
                state = (d + k) and 0xFF
            }

            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun getMoonFile(iframeUrl: String): Pair<String, String?> {
        val cleanUrl = if (iframeUrl.contains("player=")) {
            iframeUrl
        } else {
            "$iframeUrl${if (iframeUrl.contains("?")) "&" else "?"}player=animeon.club"
        }

        val html = try {
            app.get(
                cleanUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "https://animeon.club/",
                    "X-Requested-With" to "mark.via.gp",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-User" to "?1",
                    "Sec-Fetch-Dest" to "document",
                    "Upgrade-Insecure-Requests" to "1"
                ),
                cacheTime = 0
            ).text
        } catch (e: Exception) {
            ""
        }

        if (html.isNotEmpty()) {
            val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
            var decodedJs = ""

            for (m in atobRegex.findAll(html)) {
                val d = moonOuterDecode(m.groupValues[1])

                if (d.contains("_0xd") || d.contains("file")) {
                    decodedJs = d
                    break
                }
            }

            if (decodedJs.isNotEmpty()) {
                val keyRegex = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
                val xorKey = keyRegex.find(decodedJs)?.groupValues?.get(1)

                var subtitleUrl: String? = null

                if (!xorKey.isNullOrEmpty()) {
                    val subtitleEncRegex = Regex("""subtitle\s*:\s*_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                    val subtitleEncMatch = subtitleEncRegex.find(decodedJs)?.groupValues?.get(1)

                    if (!subtitleEncMatch.isNullOrEmpty()) {
                        val subtitleDecoded = moonDecrypt(subtitleEncMatch, xorKey)
                        val subtitleEntries = mutableListOf<Pair<String, String>>()

                        val subtitleEntryRegex = Regex("""\[([^\]]+)\](https?://[^\[,]+)""")
                        val entryMatches = subtitleEntryRegex.findAll(subtitleDecoded).toList()

                        if (entryMatches.isNotEmpty()) {
                            entryMatches.forEach { m2 ->
                                subtitleEntries.add(
                                    Pair(
                                        m2.groupValues[1],
                                        m2.groupValues[2].trim(',', ' ')
                                    )
                                )
                            }
                        } else if (subtitleDecoded.startsWith("http")) {
                            subtitleEntries.add(Pair("UA", subtitleDecoded.trim()))
                        }

                        if (subtitleEntries.isNotEmpty()) {
                            subtitleUrl = subtitleEntries.joinToString("|||") { "${it.first}::${it.second}" }
                        }
                    }

                    val encodedRegex = Regex("""_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                    val encMatches = encodedRegex.findAll(decodedJs).toList()

                    val allDecoded = mutableListOf<String>()

                    for (match in encMatches) {
                        val decoded = moonDecrypt(match.groupValues[1], xorKey)

                        if (decoded.isNotEmpty()) {
                            allDecoded.add(decoded)
                        }
                    }

                    for (decoded in allDecoded) {
                        val isVideoOrPlaylist = decoded.contains(".m3u8") ||
                                decoded.contains(".mp4") ||
                                decoded.contains(".webm") ||
                                decoded.startsWith("[")

                        val isMoonDomain = decoded.contains("mooncdn") ||
                                decoded.contains("moonanime.art/content") ||
                                decoded.contains("s.moonanime.art")

                        val isStaticAsset = decoded.contains(
                            Regex("""\.(jpg|jpeg|png|vtt|srt|txt)(\?|$)""", RegexOption.IGNORE_CASE)
                        )

                        if ((isVideoOrPlaylist || isMoonDomain) && !isStaticAsset) {
                            return Pair(decoded, subtitleUrl)
                        }
                    }
                }

                val contentUrlRegex = Regex("""(https?://s\.moonanime\.art/content/[^\s"'`]+)""")
                val contentMatch = contentUrlRegex.find(decodedJs)?.groupValues?.get(1)

                if (!contentMatch.isNullOrEmpty() && !contentMatch.contains(Regex("""\.(jpg|jpeg|png)$"""))) {
                    val resolved = resolveMoonContent(contentMatch)

                    if (!resolved.isNullOrEmpty()) {
                        return Pair(resolved, subtitleUrl)
                    }
                }
            }
        }

        val hashRegex = Regex("""/iframe/([a-zA-Z0-9]+)/?""")
        val hash = hashRegex.find(cleanUrl)?.groupValues?.get(1)

        if (!hash.isNullOrEmpty()) {
            val qualityResults = mutableListOf<String>()

            for (quality in listOf(1080, 720, 480, 360)) {
                val contentUrl = "https://s.moonanime.art/content/v/$hash/$quality/"
                val resolved = resolveMoonContent(contentUrl)

                if (!resolved.isNullOrEmpty()) {
                    qualityResults.add("[${quality}p]$resolved")
                }
            }

            if (qualityResults.isNotEmpty()) {
                val result = qualityResults.joinToString(".")
                return Pair(result, null)
            }
        }

        return Pair("", null)
    }

    private var subtitleProxyPort: Int = 0
    private val subtitleCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun ensureSubtitleProxy() {
        if (subtitleProxyPort != 0) return

        val serverSocket = java.net.ServerSocket(0)
        subtitleProxyPort = serverSocket.localPort

        Thread {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()

                    Thread {
                        try {
                            val line = client.getInputStream().bufferedReader().readLine() ?: return@Thread
                            val key = line.substringAfter("?").substringBefore(" ")
                            val body = subtitleCache[key]
                            val out = client.getOutputStream()

                            if (body != null) {
                                out.write(
                                    (
                                        "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/vtt; charset=utf-8\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Access-Control-Allow-Origin: *\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray()
                                )
                                out.write(body)
                            } else {
                                out.write(
                                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                            }

                            out.flush()
                            client.close()
                        } catch (e: Exception) {
                        }
                    }.also { it.isDaemon = true }.start()
                } catch (e: Exception) {
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private suspend fun invokeSubtitles(subUrl: String?, subtitleCallback: (SubtitleFile) -> Unit) {
        if (subUrl == null) {
            return
        }

        ensureSubtitleProxy()

        subUrl.split("|||").forEach { entry ->
            val parts = entry.split("::", limit = 2)

            if (parts.size == 2) {
                val lang = parts[0]
                val url = parts[1]

                try {
                    val bytes = app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "https://moonanime.art/",
                            "Origin" to "https://moonanime.art",
                            "Accept" to "*/*",
                            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                        ),
                        cacheTime = 0
                    ).body.bytes()

                    val key = java.util.UUID.randomUUID().toString().replace("-", "")
                    subtitleCache[key] = bytes

                    val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"

                    subtitleCallback.invoke(newSubtitleFile(lang, proxyUrl))
                } catch (e: Exception) {
                    subtitleCallback.invoke(newSubtitleFile(lang, url))
                }
            }
        }
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null

        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }

        return value.value.toIntOrNull()
    }
}