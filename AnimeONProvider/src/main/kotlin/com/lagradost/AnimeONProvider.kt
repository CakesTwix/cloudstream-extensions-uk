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

            val newRequest = when {
                url.contains("moonanime") -> {
                    val base = moonBaseFromUrl(url)
                    request.newBuilder()
                        .header("Referer", "$base/")
                        .header("Origin", base)
                        .header("User-Agent", userAgent)
                        .build()
                }
                url.contains("fenixplay.xyz") || url.contains("cdn.fenixplay.xyz") -> {
                    request.newBuilder()
                        .header("Referer", "https://fenixplay.xyz/")
                        .header("Origin", "https://fenixplay.xyz")
                        .header("User-Agent", userAgent)
                        .build()
                }
                else -> request
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

    private val moonBaseDomains = listOf("https://moonanime.art", "https://moonanime.net")

    private fun moonOrigin(url: String): String {
        return try {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            "https://moonanime.art"
        }
    }

    private fun moonBaseFromUrl(url: String): String {
        return moonOrigin(url).replace("://s.moonanime", "://moonanime")
    }

    private fun moonVideoHeaders(refererUrl: String): Map<String, String> {
        val base = moonBaseFromUrl(refererUrl)
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$base/",
            "Origin" to base,
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Dest" to "video",
            "X-Requested-With" to "mark.via.gp"
        )
    }

    private var posterProxyPort: Int = 0
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var moonCookieHeader: String? = null
    private var lastFenixPlaylist: FenixPlaylist? = null
    private var lastFenixMediaAccess: FenixMediaAccess? = null

    private val episodePosterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private data class PosterFetchTask(
        val episodeIds: List<Int>,
        val animeId: Int,
        val epNum: Int
    )

    private val posterFetchTasks = java.util.concurrent.ConcurrentHashMap<String, PosterFetchTask>()

    private data class CollectedEpisodes(
        val translationName: String,
        val playerName: String,
        val episodes: List<FundubEpisode>
    )

    private data class SubToAdd(val lang: String, val url: String, val priority: Int)

    private val posterHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .build()
            chain.proceed(request)
        }
        .build()

    private val htmlHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
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
        "$mainUrl/api/stats/anime/popular/" to "Популярні аніме",
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

    private data class PopularAnime(
        val id: Int,
        val slug: String? = null,
        val titleUa: String,
        val titleEn: String? = null,
        val type: String? = null,
        val image: Image? = null
    )

    private data class PopularAnimeItem(
        val views: Int? = null,
        val anime: PopularAnime
    )

    private data class PopularAnimeResponse(
        val results: List<PopularAnimeItem>
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
        val episodeNum: Int? = null,
        val apiPoster: String? = null
    )

    private data class DirectPlayerResponse(
        val videoUrl: String? = null,
        val fileUrl: String? = null,
        val selection: FenixSelection? = null
    )

    private data class FenixSelection(
        val episode: Int? = null,
        val edition: String? = null,
        val track: String? = null
    )

    private data class EpisodeVideoResponse(
        val videoUrl: String? = null,
        val fileUrl: String? = null,
        val selection: FenixSelection? = null
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

    private data class FenixSubtitleRendition(
        val url: String? = null,
        val styled_url: String? = null,
        val fonts_url: String? = null,
    )

    private data class FenixSubtitle(
        val label: String? = null,
        val language: String? = null,
        val url: String? = null,
        val styled_url: String? = null,
        val fonts_url: String? = null,
        val kind: String? = null,
        val voice_studio_id: String? = null,
        val voice_studio_name: String? = null,
        val is_default: Boolean? = null,
        val offset_ms: Int? = null,
        val renditions: Map<String, FenixSubtitleRendition>? = null,
    )

    private data class FenixMediaAccess(
        val origin: String? = null,
        val token: String? = null
    )

    private data class FenixPlayback(
        val version: Int? = 1,
        val token: String? = null,
    )

    private data class FenixPlaybackTrack(
        val id: String? = null,
        val label: String? = null,
        val language: String? = null,
        val track_type: String? = null,
        val hls_url: String? = null,
        val cast_avc_url: String? = null,
        val is_default: Boolean? = null,
        val voice_studio_id: String? = null,
    )

    private data class FenixPlaybackEpisode(
        val id: String? = null,
        val number: Int = 0,
        val edition: String? = null,
        val title: String? = null,
        val thumbnail_url: String? = null,
        val hls_master_url: String? = null,
        val cast_avc_url: String? = null,
        val video_segment_format: String? = null,
        val tracks: List<FenixPlaybackTrack>? = null,
        val subtitles: List<FenixSubtitle>? = null,
    )

    private data class FenixPlaybackEpisodeResponse(
        val episode: FenixPlaybackEpisode? = null,
        val token: String? = null,
    )

    private data class FenixPlaylist(
        val slug: String? = null,
        val playback: FenixPlayback? = null,
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

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun buildEpisodeDataJson(sources: List<EpisodeSource>): String {
        val sb = StringBuilder("[")
        sources.forEachIndexed { index, s ->
            if (index > 0) sb.append(",")
            sb.append("{\"translationName\":\"${escapeJson(s.translationName)}\",")
            sb.append("\"playerName\":\"${escapeJson(s.playerName)}\",")
            sb.append("\"episodeId\":${s.episodeId},")
            sb.append("\"episodeNum\":${s.episodeNum ?: 0}}")
        }
        sb.append("]")
        return sb.toString()
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
                            val out = client.getOutputStream()

                            var body = posterCache[key]

                            if (body == null) {
                                val task = posterFetchTasks[key]
                                if (task != null) {
                                    body = fetchEpisodePosterBytes(task.episodeIds)
                                    if (body != null && body.isNotEmpty()) {
                                        posterCache[key] = body
                                        episodePosterCache["${task.animeId}:${task.epNum}"] =
                                            "http://127.0.0.1:$posterProxyPort/poster?$key"
                                    }
                                }
                            }

                            val finalBody = body ?: ByteArray(0)

                            if (finalBody.isEmpty()) {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val contentType = when {
                                    finalBody.size > 8 && finalBody[0] == 0x89.toByte() && finalBody[1] == 0x50.toByte() -> "image/png"
                                    finalBody.size > 2 && finalBody[0] == 0xFF.toByte() && finalBody[1] == 0xD8.toByte() -> "image/jpeg"
                                    finalBody.size > 4 && finalBody[0] == 0x52.toByte() && finalBody[1] == 0x49.toByte() -> "image/webp"
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
                        } finally {
                            try {
                                client.close()
                            } catch (_: Exception) {
                            }
                        }
                    }.also { it.isDaemon = true }.start()
                } catch (e: Exception) {
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun getMoonCookieHeader(): String? {
        moonCookieHeader?.let { return it }

        for (base in moonBaseDomains) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("$base/")
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
                    return header
                }
            } catch (e: Exception) {
            }
        }

        return null
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
                originalUrl.contains("moonanime") ||
                originalUrl.contains("mooncdn.") -> {
                    val base = moonBaseFromUrl(originalUrl)
                    requestBuilder.header("Referer", "$base/")
                    requestBuilder.header("Origin", base)
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
                originalUrl.contains("fenixplay.xyz") -> {
                    requestBuilder.header("Referer", "https://fenixplay.xyz/")
                    requestBuilder.header("Origin", "https://fenixplay.xyz")
                }
            }

            getMoonCookieHeader()?.let { cookie ->
                if (originalUrl.contains("moonanime") || originalUrl.contains("mooncdn")) {
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

    private suspend fun fetchJsonWithRetry(url: String, retries: Int = 3): String? {
        repeat(retries) {
            val result = fetchJsonOrNull(url)
            if (result != null) return result
        }
        return null
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

    private fun httpGetText(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String> = emptyMap()
    ): String? {
        return try {
            val builder = okhttp3.Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (cookies.isNotEmpty()) {
                builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            val response = htmlHttpClient.newCall(builder.build()).execute()
            val body = if (response.isSuccessful) response.body?.string() ?: "" else ""
            response.close()
            body
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchEpisodeVideoUrlSync(episodeId: Int): String? {
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
            resp.videoUrl ?: resp.fileUrl
        } catch (e: Exception) {
            null
        }
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
        if (!iframeUrl.contains("/iframe/")) {
            return null
        }

        val cleanUrl = if (iframeUrl.contains("player=")) {
            iframeUrl
        } else {
            "$iframeUrl${if (iframeUrl.contains("?")) "&" else "?"}player=animeon.club"
        }

        val html = httpGetText(
            cleanUrl,
            mapOf(
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to "https://animeon.club/",
                "X-Requested-With" to "mark.via.gp",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Dest" to "document",
                "Upgrade-Insecure-Requests" to "1"
            )
        ) ?: return null

        val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
        var posterUrl: String? = null

        for (match in atobRegex.findAll(html)) {
            val decoded = moonOuterDecode(match.groupValues[1])
            if (!decoded.contains("poster") && !decoded.contains("k=")) continue

            posterUrl = Regex("""poster\s*:\s*["'](https?://[^"']+)["']""")
                .find(decoded)?.groupValues?.get(1)

            if (posterUrl != null) break

            val xorKey = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
                .find(decoded)?.groupValues?.get(1) ?: continue

            val callRegex = Regex("""(\w+)\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
            var foundInCalls = false

            for (callMatch in callRegex.findAll(decoded)) {
                val result = moonDecrypt(callMatch.groupValues[2], xorKey)
                if (result.startsWith("http") && (result.contains("poster") || result.contains(".webp") || result.contains(".jpg") || result.contains(".png"))) {
                    posterUrl = result
                    foundInCalls = true
                    break
                }
            }

            if (foundInCalls) break
        }

        if (posterUrl.isNullOrEmpty() || posterUrl.contains("mooncdn.")) {
            val patterns = listOf(
                Regex("""((?:https?:)?//[^"'\s\\]+/poster\.(?:webp|jpg|jpeg|png)(?:\?[^"'\s\\]*)?)"""),
                Regex("""poster["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""((?:https?:)?//[^"'\s]+screen\.jpg)"""),
                Regex("""((?:https?:)?//[^"'\s]+\.ashdi\.vip[^"'\s]*(?:screen|poster)[^"'\s]*)""")
            )

            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val extracted = match.groupValues[1]
                    val finalUrl = if (extracted.startsWith("//")) "https:$extracted" else extracted
                    if (finalUrl.isNotBlank() && !finalUrl.contains("mooncdn.")) {
                        posterUrl = finalUrl
                        break
                    }
                }
            }
        }

        if (posterUrl.isNullOrEmpty() || posterUrl.contains("mooncdn.")) {
            return null
        }

        return posterUrl
    }

    private fun fenixExtractCode(embedUrl: String): String? {
        return embedUrl.substringAfter("/embed/", "").substringBefore("?").ifBlank { null }
    }

    private fun fenixExtractEpisodeNumber(embedUrl: String): Int? {
        return Regex("""[?&]ep=(\d+)""").find(embedUrl)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun fenixExtractEdition(embedUrl: String): String {
        return Regex("""[?&]edition=([^&]+)""").find(embedUrl)?.groupValues?.get(1) ?: "tv"
    }

    private fun isSignedFenixCode(code: String): Boolean {
        return Regex("""^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""").matches(code)
    }

    private fun fenixOrigin(embedUrl: String): String {
        return try {
            val uri = java.net.URI(embedUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://fenixplay.xyz"
        }
    }

    private fun extractFenixMediaAccess(html: String): FenixMediaAccess? {
        val match = Regex(
            """window\.FENIX_MEDIA_ACCESS\s*=\s*(\{.*?\});""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: return null
        return try {
            AppUtils.parseJson<FenixMediaAccess>(match.groupValues[1])
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchFenixPlaylist(embedUrl: String): FenixPlaylist? {
        val code = fenixExtractCode(embedUrl) ?: return null
        val origin = fenixOrigin(embedUrl)

        val html = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                cacheTime = 0
            ).text
        } catch (e: Exception) {
            ""
        }

        lastFenixMediaAccess = extractFenixMediaAccess(html)

        val inlineRegex = Regex("""window\.FENIX_PLAYLIST\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val inlineMatch = inlineRegex.find(html)
        if (inlineMatch != null) {
            try {
                val p = AppUtils.parseJson<FenixPlaylist>(inlineMatch.groupValues[1])
                lastFenixPlaylist = p
                if (!p.slug.isNullOrEmpty() || p.playback != null) {
                    return p
                }
            } catch (e: Exception) {
            }
        }

        val apiBase = Regex("""window\.FENIX_API_BASE\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        val finalApiBase = apiBase ?: origin

        val apiPath = if (isSignedFenixCode(code))
            "$finalApiBase/api/playlist-code/$code"
        else
            "$finalApiBase/api/playlist/$code"

        return try {
            val json = app.get(
                apiPath,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl,
                    "Accept" to "application/json, */*"
                ),
                cacheTime = 0
            ).text

            val playlist = AppUtils.parseJson<FenixPlaylist>(json)
            lastFenixPlaylist = playlist
            playlist
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFenixPlaylistSync(embedUrl: String): FenixPlaylist? {
        val code = fenixExtractCode(embedUrl) ?: return null
        val origin = fenixOrigin(embedUrl)

        val html = httpGetText(
            embedUrl,
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ) ?: ""

        lastFenixMediaAccess = extractFenixMediaAccess(html)

        val inlineRegex = Regex("""window\.FENIX_PLAYLIST\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        inlineRegex.find(html)?.let { m ->
            try {
                val p = AppUtils.parseJson<FenixPlaylist>(m.groupValues[1])
                if (!p.slug.isNullOrEmpty() || p.playback != null) {
                    lastFenixPlaylist = p
                    return p
                }
            } catch (e: Exception) {
            }
        }

        val apiPath = if (isSignedFenixCode(code))
            "$origin/api/playlist-code/$code"
        else
            "$origin/api/playlist/$code"

        val json = httpGetText(
            apiPath,
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to embedUrl,
                "Accept" to "application/json, */*"
            )
        ) ?: return null

        return try {
            val playlist = AppUtils.parseJson<FenixPlaylist>(json)
            lastFenixPlaylist = playlist
            playlist
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchFenixEpisodePlayback(
        slug: String,
        playbackToken: String,
        edition: String,
        number: Int
    ): FenixPlaybackEpisodeResponse? {
        val url = "https://fenixplay.xyz/api/playback/" +
            java.net.URLEncoder.encode(slug, "UTF-8") + "/episodes/" +
            java.net.URLEncoder.encode(edition, "UTF-8") + "/" + number

        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "X-Fenix-Playback" to playbackToken,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "https://fenixplay.xyz/"
                ),
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
        number: Int
    ): FenixPlaybackEpisodeResponse? {
        val url = "https://fenixplay.xyz/api/playback/" +
            java.net.URLEncoder.encode(slug, "UTF-8") + "/episodes/" +
            java.net.URLEncoder.encode(edition, "UTF-8") + "/" + number

        val json = httpGetText(
            url,
            mapOf(
                "User-Agent" to userAgent,
                "X-Fenix-Playback" to playbackToken,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to "https://fenixplay.xyz/"
            )
        ) ?: return null

        return try {
            AppUtils.parseJson<FenixPlaybackEpisodeResponse>(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchEpisodePosterBytes(episodeIds: List<Int>): ByteArray? {
        if (episodeIds.isEmpty()) return null

        for (episodeId in episodeIds) {
            val bytes = fetchSingleEpisodePosterBytes(episodeId)
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }

        return null
    }

    private fun fetchSingleEpisodePosterBytes(episodeId: Int): ByteArray? {
        val videoUrl = fetchEpisodeVideoUrlSync(episodeId) ?: return null

        if (videoUrl.contains("fenixplay.xyz")) {
            if (lastFenixPlaylist == null) {
                fetchFenixPlaylistSync(videoUrl)
            }

            val slug = lastFenixPlaylist?.slug
            val playbackToken = lastFenixPlaylist?.playback?.token
            val epNum = fenixExtractEpisodeNumber(videoUrl) ?: 1
            val edition = fenixExtractEdition(videoUrl)

            if (slug.isNullOrEmpty() || playbackToken.isNullOrEmpty()) return null

            val details = fetchFenixEpisodePlaybackSync(slug, playbackToken, edition, epNum)
            val thumb = details?.episode?.thumbnail_url

            if (!thumb.isNullOrBlank()) {
                val bytes = fetchPosterBytes(thumb)
                if (bytes.isNotEmpty()) return bytes
            }

            return null
        }

        val rawPosterUrl = when {
            videoUrl.contains("ashdi.vip") -> getAshdiRawPosterUrl(videoUrl)
            videoUrl.contains("moonanime") -> getMoonRawPosterUrl(videoUrl)
            else -> null
        } ?: return null

        val bytes = fetchPosterBytes(rawPosterUrl)
        if (bytes.isEmpty()) return null

        return bytes
    }

    private suspend fun resolveMoonContent(contentUrl: String): String? {
        return try {
            val allCookies = mutableMapOf<String, String>()
            for (base in moonBaseDomains) {
                try {
                    val r = app.get(
                        "$base/",
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to "uk-UA,uk;q=0.9",
                        ),
                        cacheTime = 0
                    )
                    allCookies.putAll(r.cookies)
                } catch (e: Exception) {
                }
            }

            val base = moonBaseFromUrl(contentUrl)

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
                cookies = allCookies,
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
        return getEpisodeVideoInfo(episodeId)?.first
    }

    private suspend fun getEpisodeVideoInfo(episodeId: Int): Pair<String?, String?>? {
        val url = "$mainUrl/api/player/$episodeId/episode"
        val json = fetchJsonOrNull(url) ?: return null

        return try {
            val response = AppUtils.parseJson<EpisodeVideoResponse>(json)
            val videoUrl = response.videoUrl ?: response.fileUrl
            val track = response.selection?.track
            videoUrl to track
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
        val realUrl = "$apiUrl/$slug"
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

        val slug = resolveAnimeSlug(animeId)
        val realApiUrl = "$apiUrl/$slug"

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

        val episodeInfoMap = fetchEpisodeInfoMap(slug)

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsUrl = "$mainUrl/api/player/$animeId/translations"
        val translationsJson = fetchJsonOrNull(translationsUrl)

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

                            for (includeAlt in listOf("true", "false")) {
                                val tName = translation.translation.name
                                val pName = player.name

                                futures.add(episodeExecutor.submit(java.util.concurrent.Callable<CollectedEpisodes> {
                                    val collected = mutableListOf<FundubEpisode>()
                                    val seenIDs = mutableSetOf<Int>()

                                    val epJsonMinus1 = fetchJsonOrNullSync("$baseUrl&skip=-1&includeAlternative=$includeAlt")
                                    if (epJsonMinus1 != null) {
                                        try {
                                            val eps = AppUtils.parseJson<SafePlayerEpisodes>(epJsonMinus1).episodes
                                            eps.filter { it.episode <= 0 && seenIDs.add(it.id) }.let { collected.addAll(it) }
                                        } catch (e: Exception) {
                                        }
                                    }

                                    var skip = 0
                                    while (true) {
                                        val epJson = fetchJsonOrNullSync("$baseUrl&skip=$skip&includeAlternative=$includeAlt") ?: break
                                        try {
                                            val eps = AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes
                                            if (eps.isNullOrEmpty()) break
                                            collected.addAll(eps.filter { seenIDs.add(it.id) })
                                            if (eps.size < 1000) break
                                        } catch (e: Exception) {
                                            break
                                        }
                                        skip += 1000
                                    }

                                    CollectedEpisodes(tName, pName, collected)
                                }))
                            }
                        }
                    }

                    for (future in futures) {
                        try {
                            val result = future.get(15, java.util.concurrent.TimeUnit.SECONDS) ?: continue
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

                ensurePosterProxy()

                val posterPrefetchExecutor = java.util.concurrent.Executors.newFixedThreadPool(
                    (episodeSources.size / 2).coerceIn(4, 16)
                )

                episodeSources.keys.sorted().forEach { epNum ->
                    val sources = episodeSources[epNum] ?: return@forEach

                    var epPoster: String? = sources.firstNotNullOfOrNull { s ->
                        s.apiPoster?.takeIf { it.isNotEmpty() && !it.contains("mooncdn.") }
                    }

                    if (epPoster == null) {
                        val cachedPoster = episodePosterCache["$animeId:$epNum"]
                        if (cachedPoster != null && !cachedPoster.contains("mooncdn.")) {
                            epPoster = cachedPoster
                        } else {
                            val orderedSources = sources.sortedWith(
                                compareBy<EpisodeSource> {
                                    when {
                                        it.playerName.contains("Ashdi", true) -> 0
                                        it.playerName.contains("Moon", true) -> 1
                                        else -> 2
                                    }
                                }
                            )

                            val orderedEpisodeIds = orderedSources.map { it.episodeId }.distinct()
                            val episodeId = orderedEpisodeIds.firstOrNull()

                            if (episodeId != null) {
                                val key = java.util.UUID.randomUUID().toString().replace("-", "")
                                posterFetchTasks[key] = PosterFetchTask(orderedEpisodeIds, animeId, epNum)
                                epPoster = "http://127.0.0.1:$posterProxyPort/poster?$key"

                                val prefetchKey = key
                                val prefetchEpisodeIds = orderedEpisodeIds
                                val prefetchAnimeId = animeId
                                val prefetchEpNum = epNum

                                posterPrefetchExecutor.submit {
                                    if (!posterCache.containsKey(prefetchKey)) {
                                        val bytes = fetchEpisodePosterBytes(prefetchEpisodeIds)
                                        if (bytes != null && bytes.isNotEmpty()) {
                                            posterCache[prefetchKey] = bytes
                                            episodePosterCache["$prefetchAnimeId:$prefetchEpNum"] =
                                                "http://127.0.0.1:$posterProxyPort/poster?$prefetchKey"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val dataJson = buildEpisodeDataJson(sources)
                    val episodeName = episodeInfoMap[epNum]?.takeIf { it.isNotBlank() }

                    episodes.add(
                        newEpisode(dataJson).apply {
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

        val seenLinks = mutableSetOf<String>()
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            val cleanUrl = link.url.substringBefore("?")
            val key = "${link.name}|${link.quality}|$cleanUrl"
            if (seenLinks.add(key)) {
                callback(link)
            }
        }

        for (source in sources) {
            val sourceName = "${source.translationName} (${source.playerName})"
            val isAshdi = source.playerName.contains("Ashdi", ignoreCase = true)
            val isFenix = source.playerName.contains("Fenix", ignoreCase = true)

            val videoUrl = getEpisodeVideoUrl(source.episodeId)
            if (videoUrl.isNullOrEmpty()) continue

            try {
                if (isFenix && videoUrl.contains("fenixplay.xyz")) {
                    if (lastFenixPlaylist == null) {
                        fetchFenixPlaylist(videoUrl)
                    }

                    val slug = lastFenixPlaylist?.slug
                    val playbackToken = lastFenixPlaylist?.playback?.token
                    val epNum = fenixExtractEpisodeNumber(videoUrl) ?: source.episodeNum
                    val edition = fenixExtractEdition(videoUrl)

                    if (!slug.isNullOrEmpty() && !playbackToken.isNullOrEmpty() && epNum != null) {
                        val details = fetchFenixEpisodePlayback(slug, playbackToken, edition, epNum)
                        val ep = details?.episode

                        if (ep != null) {
                            val mediaToken = lastFenixMediaAccess?.token ?: details?.token
                            val fenixReferer = "https://fenixplay.xyz/"
                            val fenixHeaders2 = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to fenixReferer,
                                "Origin" to "https://fenixplay.xyz",
                                "Accept" to "*/*",
                                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                            )

                            fun addToken(u: String): String {
                                if (mediaToken.isNullOrEmpty() || u.contains("token=")) return u
                                return u + (if (u.contains("?")) "&" else "?") + "token=$mediaToken"
                            }

                            ep.tracks?.forEach { track ->
                                val hlsUrl = track.hls_url
                                if (hlsUrl.isNullOrEmpty()) return@forEach
                                val label = track.label ?: track.language ?: "Fenix"
                                val trackName = "$label · Fenix"

                                safeCallback(ExtractorLink(
                                    source = name,
                                    name = trackName,
                                    url = addToken(hlsUrl),
                                    referer = fenixReferer,
                                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                    headers = fenixHeaders2
                                ))
                            }

                            if (ep.tracks.isNullOrEmpty() && !ep.hls_master_url.isNullOrEmpty()) {
                                safeCallback(ExtractorLink(
                                    source = name,
                                    name = sourceName,
                                    url = addToken(ep.hls_master_url),
                                    referer = fenixReferer,
                                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                    headers = fenixHeaders2
                                ))
                            }

                            emitFenixSubtitles(ep, subtitleCallback, fenixHeaders2, ::addToken)

                            foundAny = true
                        }
                    }
                    continue
                }

                if (isAshdi) {
                    if (videoUrl.contains("ashdi.vip")) {
                        processAshdiIframe(videoUrl, sourceName, isMovie = false, safeCallback)
                        foundAny = true
                    } else if (videoUrl.contains(".m3u8")) {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = videoUrl,
                            referer = "https://ashdi.vip"
                        )
                        val filtered = streams.dropLast(1)
                        if (filtered.isNotEmpty()) {
                            filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                        } else {
                            streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                        }
                        foundAny = true
                    }
                } else {
                    if (videoUrl.contains("moonanime")) {
                        if (videoUrl.contains("m3u8")) {
                            val streams = M3u8Helper.generateM3u8(
                                source = sourceName,
                                streamUrl = videoUrl,
                                referer = "${moonBaseFromUrl(videoUrl)}/",
                                headers = moonVideoHeaders(videoUrl)
                            )
                            val filtered = streams.dropLast(1)
                            if (filtered.isNotEmpty()) {
                                filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                            } else {
                                streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                            }
                            foundAny = true
                        } else {
                            val (rawFile, subUrl) = getMoonFile(videoUrl)
                            if (rawFile.isNotEmpty()) {
                                invokeSubtitles(subUrl, subtitleCallback)
                                processMoonRawFile(rawFile, sourceName, isMovie = false, safeCallback)
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
        val translationsUrl = "$mainUrl/api/player/$animeId/translations"
        val translationsJson = fetchJsonOrNull(translationsUrl) ?: return false

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
                    val collected = mutableListOf<FundubEpisode>()
                    val seenIDs = mutableSetOf<Int>()

                    val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=1000&playerId=${player.id}&translationId=$translationId"

                    for (includeAlt in listOf("true", "false")) {
                        val epJsonMinus1 = fetchJsonWithRetry("$baseUrl&skip=-1&includeAlternative=$includeAlt")

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

                        var skip = 0
                        while (true) {
                            val epJson = fetchJsonWithRetry("$baseUrl&skip=$skip&includeAlternative=$includeAlt") ?: break
                            val eps = try {
                                AppUtils.parseJson<SafePlayerEpisodes>(epJson).episodes
                            } catch (e: Exception) {
                                null
                            }
                            if (eps.isNullOrEmpty()) break
                            val newEps = eps.filter { seenIDs.add(it.id) }
                            collected.addAll(newEps)
                            if (eps.size < 1000) break
                            skip += 1000
                        }
                    }

                    val sourceName = "${translation.translation.name} (${player.name})"
                    val isAshdi = player.name.contains("Ashdi", ignoreCase = true)
                    val isFenix = player.name.contains("Fenix", ignoreCase = true)

                    if (collected.isEmpty()) {
                        val directUrl = "$mainUrl/api/player/${player.id}/${translation.translation.id}"
                        val directJson = fetchJsonOrNull(directUrl)

                        if (directJson != null) {
                            try {
                                val directSource = AppUtils.parseJson<DirectPlayerResponse>(directJson)
                                val videoUrl = directSource.videoUrl
                                val fileUrl = directSource.fileUrl

                                if (!videoUrl.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
                                    val finalUrl = videoUrl ?: fileUrl!!

                                    if (isFenix && finalUrl.contains("fenixplay.xyz")) {
                                        if (lastFenixPlaylist == null) {
                                            fetchFenixPlaylist(finalUrl)
                                        }

                                        val slug = lastFenixPlaylist?.slug
                                        val playbackToken = lastFenixPlaylist?.playback?.token
                                        val epNum = fenixExtractEpisodeNumber(finalUrl) ?: 1
                                        val edition = fenixExtractEdition(finalUrl)

                                        if (!slug.isNullOrEmpty() && !playbackToken.isNullOrEmpty() && epNum != null) {
                                            val details = fetchFenixEpisodePlayback(slug, playbackToken, edition, epNum)
                                            val ep = details?.episode

                                            if (ep != null) {
                                                val mediaToken = lastFenixMediaAccess?.token ?: details?.token
                                                val fenixReferer = "https://fenixplay.xyz/"
                                                val fenixHeaders2 = mapOf(
                                                    "User-Agent" to userAgent,
                                                    "Referer" to fenixReferer,
                                                    "Origin" to "https://fenixplay.xyz",
                                                    "Accept" to "*/*",
                                                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                                                )

                                                fun addToken(u: String): String {
                                                    if (mediaToken.isNullOrEmpty() || u.contains("token=")) return u
                                                    return u + (if (u.contains("?")) "&" else "?") + "token=$mediaToken"
                                                }

                                                ep.tracks?.forEach { track ->
                                                    val hlsUrl = track.hls_url
                                                    if (hlsUrl.isNullOrEmpty()) return@forEach
                                                    val label = track.label ?: track.language ?: "Fenix"
                                                    val trackName = "$label · Fenix"

                                                    safeCallback(ExtractorLink(
                                                        source = name,
                                                        name = trackName,
                                                        url = addToken(hlsUrl),
                                                        referer = fenixReferer,
                                                        quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                                        headers = fenixHeaders2
                                                    ))
                                                }

                                                if (ep.tracks.isNullOrEmpty() && !ep.hls_master_url.isNullOrEmpty()) {
                                                    safeCallback(ExtractorLink(
                                                        source = name,
                                                        name = sourceName,
                                                        url = addToken(ep.hls_master_url),
                                                        referer = fenixReferer,
                                                        quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                                        headers = fenixHeaders2
                                                    ))
                                                }

                                                emitFenixSubtitles(ep, subtitleCallback, fenixHeaders2, ::addToken)

                                                foundAny = true
                                            }
                                        }
                                        continue
                                    }

                                    if (isAshdi) {
                                        if (finalUrl.contains("ashdi.vip")) {
                                            processAshdiIframe(finalUrl, sourceName, isMovie = true, safeCallback)
                                            foundAny = true
                                        } else if (finalUrl.contains(".m3u8")) {
                                            val streams = M3u8Helper.generateM3u8(
                                                source = sourceName,
                                                streamUrl = finalUrl,
                                                referer = "https://ashdi.vip"
                                            )
                                            val filtered = streams.dropLast(1)
                                            if (filtered.isNotEmpty()) {
                                                filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                            } else {
                                                streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                            }
                                            foundAny = true
                                        }
                                    } else {
                                        if (finalUrl.contains("moonanime")) {
                                            if (finalUrl.contains("m3u8")) {
                                                val streams = M3u8Helper.generateM3u8(
                                                    source = sourceName,
                                                    streamUrl = finalUrl,
                                                    referer = "${moonBaseFromUrl(finalUrl)}/",
                                                    headers = moonVideoHeaders(finalUrl)
                                                )
                                                val filtered = streams.dropLast(1)
                                                if (filtered.isNotEmpty()) {
                                                    filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                                } else {
                                                    streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                                }
                                                foundAny = true
                                            } else {
                                                val (rawFile, subUrl) = getMoonFile(finalUrl)
                                                if (rawFile.isNotEmpty()) {
                                                    invokeSubtitles(subUrl, subtitleCallback)
                                                    processMoonRawFile(rawFile, sourceName, isMovie = true, safeCallback)
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
                            if (isFenix && videoUrl.contains("fenixplay.xyz")) {
                                if (lastFenixPlaylist == null) {
                                    fetchFenixPlaylist(videoUrl)
                                }

                                val slug = lastFenixPlaylist?.slug
                                val playbackToken = lastFenixPlaylist?.playback?.token
                                val epNum = fenixExtractEpisodeNumber(videoUrl) ?: ep.episode
                                val edition = fenixExtractEdition(videoUrl)

                                if (!slug.isNullOrEmpty() && !playbackToken.isNullOrEmpty() && epNum != null) {
                                    val details = fetchFenixEpisodePlayback(slug, playbackToken, edition, epNum)
                                    val epDetails = details?.episode

                                    if (epDetails != null) {
                                        val mediaToken = lastFenixMediaAccess?.token ?: details?.token
                                        val fenixReferer = "https://fenixplay.xyz/"
                                        val fenixHeaders2 = mapOf(
                                            "User-Agent" to userAgent,
                                            "Referer" to fenixReferer,
                                            "Origin" to "https://fenixplay.xyz",
                                            "Accept" to "*/*",
                                            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
                                        )

                                        fun addToken(u: String): String {
                                            if (mediaToken.isNullOrEmpty() || u.contains("token=")) return u
                                            return u + (if (u.contains("?")) "&" else "?") + "token=$mediaToken"
                                        }

                                        epDetails.tracks?.forEach { track ->
                                            val hlsUrl = track.hls_url
                                            if (hlsUrl.isNullOrEmpty()) return@forEach
                                            val label = track.label ?: track.language ?: "Fenix"
                                            val trackName = "$label · Fenix"

                                            safeCallback(ExtractorLink(
                                                source = name,
                                                name = trackName,
                                                url = addToken(hlsUrl),
                                                referer = fenixReferer,
                                                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                                headers = fenixHeaders2
                                            ))
                                        }

                                        if (epDetails.tracks.isNullOrEmpty() && !epDetails.hls_master_url.isNullOrEmpty()) {
                                            safeCallback(ExtractorLink(
                                                source = name,
                                                name = sourceName,
                                                url = addToken(epDetails.hls_master_url),
                                                referer = fenixReferer,
                                                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                                headers = fenixHeaders2
                                            ))
                                        }

                                        emitFenixSubtitles(epDetails, subtitleCallback, fenixHeaders2, ::addToken)

                                        foundAny = true
                                    }
                                }
                                continue
                            }

                            if (isAshdi) {
                                if (videoUrl.contains("ashdi.vip")) {
                                    processAshdiIframe(videoUrl, sourceName, isMovie = true, safeCallback)
                                    foundAny = true
                                } else if (videoUrl.contains(".m3u8")) {
                                    val streams = M3u8Helper.generateM3u8(
                                        source = sourceName,
                                        streamUrl = videoUrl,
                                        referer = "https://ashdi.vip"
                                    )
                                    val filtered = streams.dropLast(1)
                                    if (filtered.isNotEmpty()) {
                                        filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                    } else {
                                        streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                    }
                                    foundAny = true
                                }
                            } else {
                                if (videoUrl.contains("moonanime")) {
                                    if (videoUrl.contains("m3u8")) {
                                        val streams = M3u8Helper.generateM3u8(
                                            source = sourceName,
                                            streamUrl = videoUrl,
                                            referer = "${moonBaseFromUrl(videoUrl)}/",
                                            headers = moonVideoHeaders(videoUrl)
                                        )
                                        val filtered = streams.dropLast(1)
                                        if (filtered.isNotEmpty()) {
                                            filtered.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                        } else {
                                            streams.forEach { safeCallback(fixMovieExtractorLink(it, sourceName)) }
                                        }
                                        foundAny = true
                                    } else {
                                        val (rawFile, subUrl) = getMoonFile(videoUrl)
                                        if (rawFile.isNotEmpty()) {
                                            invokeSubtitles(subUrl, subtitleCallback)
                                            processMoonRawFile(rawFile, sourceName, isMovie = true, safeCallback)
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

    private fun subtitleExtFromUrl(url: String): String {
        val path = url.substringBefore("?").substringAfterLast("/")

        return when {
            path.endsWith(".ass", true) -> ".ass"
            path.endsWith(".ssa", true) -> ".ass"
            path.endsWith(".vtt", true) -> ".vtt"
            path.endsWith(".srt", true) -> ".srt"
            else -> ".vtt"
        }
    }

    private fun subtitleContentType(ext: String): String {
        return when (ext) {
            ".ass" -> "text/x-ssa"
            ".srt" -> "application/x-subrip"
            else -> "text/vtt"
        }
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
        ensureSubtitleProxy()

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
                val displayLang = "$baseLang$kindLabel$studioLabel"
                val priority = if (sub.is_default == true) 0 else 1

                subsToAdd.add(SubToAdd(displayLang, u, priority))
            }
        }

        val seenSubs = mutableSetOf<String>()

        subsToAdd.sortedBy { it.priority }.forEach { s ->
            val cleanUrl = s.url.substringBefore("?")
            if (!seenSubs.add("${s.lang}|$cleanUrl")) return@forEach

            val tokenUrl = addToken(s.url)
            var bytes = fetchSubtitleBytes(tokenUrl, headers)

            if (bytes.isEmpty() && tokenUrl != s.url) {
                bytes = fetchSubtitleBytes(s.url, headers)
            }

            if (bytes.isEmpty()) {
                subtitleCallback.invoke(newSubtitleFile(s.lang, s.url))
                return@forEach
            }

            val ext = subtitleExtFromUrl(s.url)
            val key = java.util.UUID.randomUUID().toString().replace("-", "") + ext

            subtitleCache[key] = bytes
            subtitleCacheType[key] = subtitleContentType(ext)

            val proxyUrl = "http://127.0.0.1:$subtitleProxyPort/sub?$key"
            subtitleCallback.invoke(newSubtitleFile(s.lang, proxyUrl))
        }
    }

    private suspend fun processMoonRawFile(
        rawFile: String,
        sourceName: String,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        if (rawFile.startsWith("[")) {
            val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")
            val all = qualityRegex.findAll(rawFile).toList()

            all.forEach { match ->
                val qualityStr = match.groupValues[1]
                val qUrl = match.groupValues[2]

                val qualityInt = qualityStr.replace("p", "").toIntOrNull()
                    ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value

                val headers = moonVideoHeaders(qUrl)

                when {
                    qUrl.contains(".m3u8") -> {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = qUrl,
                            referer = "${moonBaseFromUrl(qUrl)}/",
                            headers = headers
                        )
                        val filtered = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams
                        finalStreams.forEach {
                            callback(fixMovieExtractorLink(it, sourceName))
                        }
                    }
                    qUrl.contains("moonanime") -> {
                        val finalUrl = resolveMoonContent(qUrl)
                        if (!finalUrl.isNullOrEmpty()) {
                            val link = ExtractorLink(
                                source = name,
                                name = sourceName,
                                url = finalUrl,
                                referer = "${moonBaseFromUrl(qUrl)}/",
                                quality = qualityInt,
                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                headers = headers
                            )
                            callback(fixMovieExtractorLink(link, sourceName))
                        }
                    }
                    else -> {
                        val link = ExtractorLink(
                            source = name,
                            name = sourceName,
                            url = qUrl,
                            referer = "${moonBaseFromUrl(qUrl)}/",
                            quality = qualityInt,
                            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                            headers = headers
                        )
                        callback(fixMovieExtractorLink(link, sourceName))
                    }
                }
            }
        } else if (rawFile.contains(".m3u8")) {
            val headers = moonVideoHeaders(rawFile)
            val streams = M3u8Helper.generateM3u8(
                source = sourceName,
                streamUrl = rawFile,
                referer = "${moonBaseFromUrl(rawFile)}/",
                headers = headers
            )
            val filtered = streams.dropLast(1)
            val finalStreams = if (filtered.isNotEmpty()) filtered else streams
            finalStreams.forEach {
                callback(fixMovieExtractorLink(it, sourceName))
            }
        } else if (rawFile.contains("moonanime")) {
            val finalUrl = resolveMoonContent(rawFile)
            if (!finalUrl.isNullOrEmpty()) {
                val link = ExtractorLink(
                    source = name,
                    name = sourceName,
                    url = finalUrl,
                    referer = "${moonBaseFromUrl(rawFile)}/",
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                    headers = moonVideoHeaders(rawFile)
                )
                callback(fixMovieExtractorLink(link, sourceName))
            }
        } else {
            val link = ExtractorLink(
                source = name,
                name = sourceName,
                url = rawFile,
                referer = "${moonBaseFromUrl(rawFile)}/",
                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                headers = moonVideoHeaders(rawFile)
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
                    val subtitleEncRegex = Regex("""(?:subtitle|rawSubtitle)\s*[:=]\s*\w+\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
                    val subtitleEncMatch = subtitleEncRegex.find(decodedJs)?.groupValues?.get(1)

                    if (!subtitleEncMatch.isNullOrEmpty()) {
                        val subtitleDecoded = moonDecrypt(subtitleEncMatch, xorKey)
                        val subtitleEntries = mutableListOf<Pair<String, String>>()

                        val subtitleEntryRegex = Regex("""\[([^\]]+)\](https?://[^\[,]+)""")
                        val entryMatches = subtitleEntryRegex.findAll(subtitleDecoded).toList()

                        if (entryMatches.isNotEmpty()) {
                            entryMatches.forEach { m2 ->
                                subtitleEntries.add(Pair(m2.groupValues[1], m2.groupValues[2].trim(',', ' ')))
                            }
                        } else if (subtitleDecoded.startsWith("http")) {
                            subtitleEntries.add(Pair("UA", subtitleDecoded.trim()))
                        }

                        if (subtitleEntries.isNotEmpty()) {
                            subtitleUrl = subtitleEntries.joinToString("|||") { "${it.first}::${it.second}" }
                        }
                    }

                    val encodedRegex = Regex("""(\w+)\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
                    val encMatches = encodedRegex.findAll(decodedJs).toList()
                    val allDecoded = mutableListOf<String>()

                    for (match in encMatches) {
                        val decoded = moonDecrypt(match.groupValues[2], xorKey)
                        if (decoded.isNotEmpty()) {
                            allDecoded.add(decoded)
                        }
                    }

                    for (decoded in allDecoded) {
                        val isVideoOrPlaylist = decoded.contains(".m3u8") || decoded.contains(".mp4") || decoded.contains(".webm") || decoded.startsWith("[")
                        val isMoonDomain = decoded.contains("mooncdn") || decoded.contains("moonanime")
                        val isStaticAsset = decoded.contains(Regex("""\.(jpg|jpeg|png|webp|avif|vtt|srt|txt)(\?|$)""", RegexOption.IGNORE_CASE))

                        if ((isVideoOrPlaylist || isMoonDomain) && !isStaticAsset) {
                            return Pair(decoded, subtitleUrl)
                        }
                    }
                }

                val contentUrlRegex = Regex("""(https?://s\.moonanime\.(?:net|art)/content/[^\s"'`]+)""")
                val contentMatch = contentUrlRegex.find(decodedJs)?.groupValues?.get(1)

                if (contentMatch != null && contentMatch.isNotEmpty() && !contentMatch.contains(Regex("""\.(jpg|jpeg|png)$"""))) {
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
            val base = moonBaseFromUrl(cleanUrl)
            val cdn = base.replace("://moonanime", "://s.moonanime")
            val qualityResults = mutableListOf<String>()

            for (quality in listOf(1080, 720, 480, 360)) {
                val contentUrl = "$cdn/content/v/$hash/$quality/"
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
    private val subtitleCacheType = java.util.concurrent.ConcurrentHashMap<String, String>()

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
                            val key = line.substringAfter("?").substringBefore(" ").trim()
                            val body = subtitleCache[key]
                            val out = client.getOutputStream()

                            if (body != null) {
                                val contentType = subtitleCacheType[key] ?: "text/vtt"

                                out.write(
                                    (
                                        "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: $contentType; charset=utf-8\r\n" +
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
        if (subUrl == null) return
        ensureSubtitleProxy()

        subUrl.split("|||").forEach { entry ->
            val parts = entry.split("::", limit = 2)

            if (parts.size == 2) {
                val lang = parts[0]
                val url = parts[1]
                val base = moonBaseFromUrl(url)

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

                    val ext = subtitleExtFromUrl(url)
                    val key = java.util.UUID.randomUUID().toString().replace("-", "") + ext
                    subtitleCache[key] = bytes
                    subtitleCacheType[key] = subtitleContentType(ext)

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