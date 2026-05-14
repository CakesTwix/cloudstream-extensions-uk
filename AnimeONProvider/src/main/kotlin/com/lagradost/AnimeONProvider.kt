package com.lagradost

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.models.*

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
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    private val listResults = object : TypeToken<List<Results>>() {}.type

    private data class SearchApiResponse(
        @SerializedName("results") val results: List<Result>,
        @SerializedName("totalCount") val totalCount: Int? = null,
    )

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to userAgent
            )).text
            if (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("[")) null
            else response
        } catch (e: Exception) { null }
    }

    private suspend fun getAshdiPoster(videoUrl: String?): String? {
        if (videoUrl.isNullOrEmpty()) return null
        if (!videoUrl.contains("ashdi.vip")) return null
        val url = if (videoUrl.contains("?")) videoUrl else "$videoUrl?player=animeon.club"
        return try {
            val html = app.get(url, headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/"
            )).text
            val posterRegex = Regex("""poster:\s*["'](https?://[^"']+)["']""")
            val match = posterRegex.find(html)?.groupValues?.get(1) ?: return null
            "https://" + match.removePrefix("http://").removePrefix("https://")
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())
            val currentDate = java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.ENGLISH).format(java.util.Date())
            val jsonText = fetchJsonOrNull("${request.data}$currentDate?withView=false") ?: return newHomePageResponse(request.name, emptyList())
            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)
            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
        if (request.data.contains("seasons") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(if (request.data.contains("%d")) request.data.format(page) else request.data) ?: return newHomePageResponse(request.name, emptyList())
        return if (!request.data.contains("seasons")) {
            val parsedJSON = Gson().fromJson(jsonText, NewAnimeModel::class.java)
            newHomePageResponse(request.name, parsedJSON.results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        } else {
            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)
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
        val url = "$searchApi${query}"
        val jsonText = fetchJsonOrNull(url) ?: return emptyList()
        return try {
            val response = Gson().fromJson(jsonText, SearchApiResponse::class.java)
            response.results.map { result ->
                newAnimeSearchResponse(result.titleUa, "anime/${result.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(result.image.preview)
                    addDubStatus(isDub = true, result.episodes)
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun searchById(id: Int): SearchResponse? {
        val jsonText = fetchJsonOrNull("$apiUrl/$id") ?: return null
        val anime = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { return null }
        return newAnimeSearchResponse(anime.titleUa, "anime/${anime.id}", TvType.Anime) {
            this.posterUrl = posterApi.format(anime.image.preview)
            addDubStatus(isDub = true, anime.episodes)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toInt()
        val jsonText = fetchJsonOrNull("$apiUrl/$animeId") ?: throw Exception("Failed to load")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)
        val showStatus = if (animeJSON.status.contains("ongoing")) ShowStatus.Ongoing else ShowStatus.Completed
        val tvType = with(animeJSON.type) {
            when {
                contains("tv") -> TvType.Anime
                contains("OVA") || contains("ONA") || contains("Спеціальний випуск") -> TvType.OVA
                contains("movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")
        if (translationsJson != null) {
            try {
                val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
                val seenEpisodes = mutableSetOf<Int>()
                for (translation in translations) {
                    val translationId = translation.translation.id
                    for (player in translation.player) {
                        val collected = mutableListOf<FundubEpisode>()
                        for (offset in 0..11000 step 100) {
                            val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=$offset&playerId=${player.id}&translationId=$translationId"
                            val epJson = fetchJsonOrNull(epUrl) ?: break
                            val eps = try { Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes } catch (e: Exception) { null }
                            if (eps.isNullOrEmpty()) break
                            collected.addAll(eps)
                            if (eps.size < 100) break
                        }
                        for (ep in collected) {
                            if (seenEpisodes.add(ep.episode)) {
                                val posterUrl = if (!ep.poster.isNullOrEmpty()) ep.poster else getAshdiPoster(ep.videoUrl)
                                episodes.add(newEpisode("$animeId, ${ep.episode}, ${ep.id}") {
                                    this.name = "Епізод ${ep.episode}"
                                    this.posterUrl = posterUrl
                                    this.episode = ep.episode
                                    this.data = "$animeId, ${ep.episode}, ${ep.id}"
                                })
                            }
                        }
                    }
                }
                episodes.sortBy { it.episode }
            } catch (e: Exception) { }
        }
        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
                this.posterUrl = posterApi.format(animeJSON.image.preview)
                this.engName = animeJSON.titleEn
                this.tags = animeJSON.genres.map { it.nameUa }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.showStatus = showStatus
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate.toIntOrNull()
                this.score = Score.from10(animeJSON.rating)
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId.toIntOrNull())
            }
        } else {
            val backgroundImage = if (animeJSON.backgroundImage.isNullOrBlank()) posterApi.format(animeJSON.image.preview) else animeJSON.backgroundImage
            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType, "$animeId") {
                this.posterUrl = posterApi.format(animeJSON.image.preview)
                this.tags = animeJSON.genres.map { it.nameUa }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score = Score.from10(animeJSON.rating)
                addMalId(animeJSON.malId.toIntOrNull())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        if (dataList.size < 2) return false

        val animeId = dataList[0]
        val targetEpisode = dataList[1].toIntOrNull() ?: return false
        val episodeId = dataList.getOrNull(2)?.toIntOrNull()

        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false
        val translations = try {
            Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
        } catch (e: Exception) { return false }

        translations.forEach { item ->
            val translationId = item.translation.id
            for (player in item.player) {

                // Отримуємо videoUrl через прямий запит, якщо є episodeId (для резервного варіанту)
                val realVideoUrl = if (episodeId != null) {
                    try {
                        val epDetailJson = fetchJsonOrNull("$mainUrl/api/player/$episodeId/episode")
                        if (epDetailJson != null) {
                            Gson().fromJson(epDetailJson, FundubEpisode::class.java).videoUrl
                        } else null
                    } catch (e: Exception) { null }
                } else null

                var episode: FundubEpisode? = null
                for (offset in 0..11000 step 100) {
                    val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=$offset&playerId=${player.id}&translationId=$translationId"
                    val epJson = fetchJsonOrNull(epUrl) ?: break
                    val parsed = try {
                        Gson().fromJson(epJson, PlayerEpisodes::class.java)
                    } catch (e: Exception) { null } ?: continue
                    val eps = parsed.episodes ?: emptyList()
                    if (eps.isEmpty()) break
                    episode = eps.firstOrNull { it.episode == targetEpisode }
                    if (episode != null) break
                }

                // Спочатку перевіряємо videoUrl (для Ashdi)
                val videoUrl = realVideoUrl ?: episode?.videoUrl
                if (!videoUrl.isNullOrEmpty()) {
                    if (videoUrl.contains("ashdi.vip")) {
    processAshdiIframe(videoUrl, "${item.translation.name} (${player.name})", callback)
    return true
                    }
                    if (videoUrl.contains("moonanime.art")) {
                        if (videoUrl.contains("m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = "${item.translation.name} (${player.name})",
                                streamUrl = videoUrl,
                                referer = "https://moonanime.art/"
                            ).dropLast(1).forEach(callback)
                            return true
                        }
                        val rawFile = getMoonFile(videoUrl)
                        if (rawFile.isNotEmpty()) {
                            val sourceName = "${item.translation.name} (${player.name})"
                            if (rawFile.startsWith("[")) {
                                val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")
                                qualityRegex.findAll(rawFile).forEach { match ->
                                    val quality = match.groupValues[1]
                                    val url = match.groupValues[2]
                                    M3u8Helper.generateM3u8(
                                        source = "$sourceName $quality",
                                        streamUrl = url,
                                        referer = "https://moonanime.art/",
                                        headers = mapOf(
                                            "User-Agent" to userAgent,
                                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                                            "Referer" to "https://animeon.club/"
                                        )
                                    ).dropLast(1).forEach(callback)
                                }
                            } else if (rawFile.contains(".m3u8")) {
                                M3u8Helper.generateM3u8(
                                    source = sourceName,
                                    streamUrl = rawFile,
                                    referer = "https://moonanime.art/",
                                    headers = mapOf(
                                        "User-Agent" to userAgent,
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                        "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                                        "Referer" to "https://animeon.club/"
                                    )
                                ).dropLast(1).forEach(callback)
                            }
                            return true
                        }
                    }
                }

                // Якщо videoUrl не підійшов, пробуємо fileUrl (для інших плеєрів або як запасний)
                val fileUrl = episode?.fileUrl
                if (!fileUrl.isNullOrEmpty()) {
                    M3u8Helper.generateM3u8(
                        source = "${item.translation.name} (${player.name})",
                        streamUrl = fileUrl,
                        referer = "https://ashdi.vip"
                    ).dropLast(1).forEach(callback)
                    return true
                }
            }
        }

        return false
    }

    private suspend fun processAshdiIframe(iframeUrl: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
    try {
        val url = if (iframeUrl.contains("?")) iframeUrl else "$iframeUrl?player=animeon.club"
        val html = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val fileRegex = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
        fileRegex.find(html)?.groupValues?.get(1)?.let { m3u8 ->
            M3u8Helper.generateM3u8(
                source = sourceName,   // використовуємо передане ім'я озвучення
                streamUrl = m3u8,
                referer = "https://ashdi.vip/"
            ).dropLast(1).forEach(callback)
        }
    } catch (e: Exception) { }
    }
    

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val result = StringBuilder()
            for (i in decoded.indices) {
                result.append((decoded[i].toInt() and 0xFF xor key[i % key.length].code).toChar())
            }
            result.toString()
        } catch (e: Exception) { "" }
    }

    private fun moonOuterDecode(base64Blob: String): String {
        return try {
            val raw = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)
            if (raw.size < 32) return ""
            val key = raw.sliceArray(0 until 32)
            val data = raw.sliceArray(32 until raw.size)
            val result = StringBuilder()
            for (i in data.indices) {
                result.append(((data[i].toInt() and 0xFF) xor (key[i % 32].toInt() and 0xFF)).toChar())
            }
            result.toString()
        } catch (e: Exception) { "" }
    }

    private suspend fun getMoonFile(iframeUrl: String): String {
        val html = app.get(iframeUrl, headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://animeon.club/"
        )).text

        val fileRegex = Regex("""file:\s*_0xd\(["']([^"']+)["']\)""")
        val directMatch = fileRegex.find(html)?.groupValues?.get(1)
        if (directMatch != null) {
            val result = moonDecrypt(directMatch)
            if (result.isNotEmpty()) return result
        }

        val atobRegex = Regex("""atob\(["']([^"']+)["']\)""")
        val atobMatch = atobRegex.find(html)?.groupValues?.get(1) ?: return ""
        val decodedJs = moonOuterDecode(atobMatch)
        if (decodedJs.isEmpty()) return ""

        val innerMatch = fileRegex.find(decodedJs)?.groupValues?.get(1) ?: return ""
        return moonDecrypt(innerMatch)
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") return value.value.drop(1).toIntOrNull()
        return value.value.toIntOrNull()
    }
} 
 