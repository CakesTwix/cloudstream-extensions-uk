package com.lagradost

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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

    private data class RedirectResponse(
        @SerializedName("moved") val moved: Boolean? = null,
        @SerializedName("redirectTo") val redirectTo: String? = null,
        @SerializedName("slug") val slug: String? = null,
    )

    private data class EpisodeSource(
        val translationName: String,
        val playerName: String,
        val videoUrl: String?,
        val fileUrl: String?,
    )

    private data class DirectPlayerResponse(
        @SerializedName("videoUrl") val videoUrl: String? = null,
        @SerializedName("fileUrl") val fileUrl: String? = null,
    )

    private data class FranchiseItem(
        @SerializedName("id") val id: Int,
        @SerializedName("slug") val slug: String?,
        @SerializedName("titleUa") val titleUa: String,
        @SerializedName("type") val type: String?,
        @SerializedName("image") val image: Image?,
        @SerializedName("releaseDate") val releaseDate: String?,
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

    private suspend fun buildFranchise(animeId: Int): List<SearchResponse> {
        val json = fetchJsonOrNull("$mainUrl/api/franchise/full/$animeId") ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FranchiseItem>>() {}.type
            val items = Gson().fromJson<List<FranchiseItem>>(json, type)
            items.filter { it.id != animeId }.map { item ->
                newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                    this.posterUrl = item.image?.preview?.let { posterApi.format(it) }
                }
            }
        } catch (e: Exception) { emptyList() }
    }

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
            val redirect = Gson().fromJson(initial, RedirectResponse::class.java)
            if (redirect?.moved == true && !redirect.slug.isNullOrEmpty()) {
                "$apiUrl/${redirect.slug}"
            } else {
                "$apiUrl/$animeId"
            }
        } catch (e: Exception) {
            "$apiUrl/$animeId"
        }
    }

    private suspend fun getAshdiPoster(videoUrl: String?): String? {
        if (videoUrl.isNullOrEmpty()) return null
        if (!videoUrl.contains("ashdi.vip")) return null
        val url = if (videoUrl.contains("?")) videoUrl else "$videoUrl?player=animeon.club"
        return try {
            val html = app.get(url, headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/"
            ), cacheTime = 0).text

            val posterRegex = Regex("""poster:\s*["']((?:https?:)?//[^"']+)["']""")
            val raw = posterRegex.find(html)?.groupValues?.get(1)
            if (!raw.isNullOrEmpty()) {
                return if (raw.startsWith("http")) raw else "https:$raw"
            }

            val screenRegex = Regex("""((?:https?:)?//[^"'\s]+screen\.jpg)""")
            val screenMatch = screenRegex.find(html)?.groupValues?.get(1) ?: return null
            if (screenMatch.startsWith("http")) screenMatch else "https:$screenMatch"
        } catch (e: Exception) { null }
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
                    "User-Agent"      to userAgent,
                    "Accept"          to "*/*",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer"         to "https://moonanime.art/",
                    "Origin"          to "https://moonanime.art",
                    "Sec-Fetch-Site"  to "same-site",
                    "Sec-Fetch-Mode"  to "cors",
                    "Sec-Fetch-Dest"  to "empty",
                ),
                cookies = cookies,
                allowRedirects = false,
                cacheTime = 0
            )

            val location = response.headers["location"] ?: response.headers["Location"]
            if (!location.isNullOrEmpty()) return location

            val body = response.text.trim()
            if (body.startsWith("http")) body else null
        } catch (e: Exception) {
            null
        }
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
        val realUrl = resolveAnimeApiUrl(id)
        val jsonText = fetchJsonOrNull(realUrl) ?: return null
        val anime = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { return null }
        return newAnimeSearchResponse(anime.titleUa, "anime/${anime.id}", TvType.Anime) {
            this.posterUrl = posterApi.format(anime.image.preview)
            addDubStatus(isDub = true, anime.episodes)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toIntOrNull()
            ?: throw Exception("Invalid anime ID in URL: $url")

        val realApiUrl = resolveAnimeApiUrl(animeId)
        val jsonText = fetchJsonOrNull(realApiUrl) ?: throw Exception("Failed to load anime $animeId")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)
            ?: throw Exception("Failed to parse anime $animeId")

        val posterUrl = animeJSON.image?.preview?.let { posterApi.format(it) } ?: ""
        val genres = animeJSON.genres?.map { it.nameUa } ?: emptyList()

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
                val episodeSources = mutableMapOf<Int, MutableList<EpisodeSource>>()
                val episodePosters = mutableMapOf<Int, String?>()

                for (translation in translations) {
                    val translationId = translation.translation.id
                    for (player in translation.player) {
                        val collected = mutableListOf<FundubEpisode>()
                        val seenIds = mutableSetOf<Int>()
                        val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=100&playerId=${player.id}&translationId=$translationId"

                        val epJsonMinus1 = fetchJsonOrNull("$baseUrl&skip=-1")
                        if (epJsonMinus1 != null) {
                            val eps = try { Gson().fromJson(epJsonMinus1, PlayerEpisodes::class.java).episodes } catch (e: Exception) { null }
                            eps?.filter { it.episode <= 0 && seenIds.add(it.id) }?.let { collected.addAll(it) }
                        }

                        val maxSkip = if (player.episodesCount > 0)
                            (player.episodesCount / 100 + 1) * 100
                        else 11000
                        var skip = 0
                        while (skip <= maxSkip) {
                            val epJson = fetchJsonOrNull("$baseUrl&skip=$skip") ?: break
                            val eps = try { Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes } catch (e: Exception) { null }
                            if (eps.isNullOrEmpty()) break
                            val newEps = eps.filter { seenIds.add(it.id) }
                            collected.addAll(newEps)
                            if (eps.size < 100) break
                            skip += 100
                        }

                        for (ep in collected) {
                            episodeSources.getOrPut(ep.episode) { mutableListOf() }.add(
                                EpisodeSource(
                                    translationName = translation.translation.name,
                                    playerName = player.name,
                                    videoUrl = ep.videoUrl,
                                    fileUrl = ep.fileUrl,
                                )
                            )
                            if (!ep.poster.isNullOrEmpty() && !episodePosters.containsKey(ep.episode)) {
                                val restricted = ep.poster.contains("mooncdn.net")
                                if (!restricted) {
                                    episodePosters[ep.episode] = ep.poster
                                }
                            }
                        }
                    }
                }

                episodeSources.keys.sorted().forEach { epNum ->
                    val sources = episodeSources[epNum] ?: return@forEach
                    var epPoster: String? = episodePosters[epNum]

                    if (epPoster.isNullOrEmpty()) {
                        val ashdiSource = sources.firstOrNull {
                            it.playerName.contains("Ashdi", ignoreCase = true) && !it.videoUrl.isNullOrEmpty()
                        }
                        if (ashdiSource != null) epPoster = getAshdiPoster(ashdiSource.videoUrl!!)
                    }

                    val dataJson = Gson().toJson(sources)
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Епізод $epNum"
                        this.posterUrl = epPoster
                        this.episode = epNum
                        this.data = dataJson
                    })
                }
            } catch (e: Exception) { }
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
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.score = Score.from10(animeJSON.rating)
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId.toIntOrNull())
                this.recommendations = franchise
            }
        } else {
            val backgroundImage = if (animeJSON.backgroundImage.isNullOrBlank()) posterUrl else animeJSON.backgroundImage
            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType, animeId.toString()) {
                this.posterUrl = posterUrl
                this.tags = genres
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score = Score.from10(animeJSON.rating)
                addMalId(animeJSON.malId.toIntOrNull())
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
            return loadMovieLinks(animeId, callback)
        }

        val sourceType = object : TypeToken<List<EpisodeSource>>() {}.type
        val sources: List<EpisodeSource> = try {
            Gson().fromJson(data, sourceType)
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
            val fileUrl = source.fileUrl
            val videoUrl = source.videoUrl

            try {
                if (isAshdi) {
                    if (!videoUrl.isNullOrEmpty() && videoUrl.contains("ashdi.vip")) {
                        processAshdiIframe(videoUrl, sourceName, isMovie = false, callback)
                        foundAny = true
                    } else if (!fileUrl.isNullOrEmpty()) {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = fileUrl,
                            referer = "https://ashdi.vip"
                        )
                        val filtered = streams.dropLast(1)
                        if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        foundAny = true
                    }
                } else {
                    if (!fileUrl.isNullOrEmpty()) {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = fileUrl,
                            referer = "https://moonanime.art/",
                            headers = moonVideoHeaders
                        )
                        val filtered = streams.dropLast(1)
                        if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                        foundAny = true
                    } else if (!videoUrl.isNullOrEmpty() && videoUrl.contains("moonanime.art")) {
                        if (videoUrl.contains("m3u8")) {
                            val streams = M3u8Helper.generateM3u8(
                                source = sourceName,
                                streamUrl = videoUrl,
                                referer = "https://moonanime.art/",
                                headers = moonVideoHeaders
                            )
                            val filtered = streams.dropLast(1)
                            if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                            else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                            foundAny = true
                        } else {
                            val rawFile = getMoonFile(videoUrl)
                            if (rawFile.isNotEmpty()) {
                                processMoonRawFile(rawFile, sourceName, isMovie = false, callback)
                                foundAny = true
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        return foundAny
    }

    private suspend fun loadMovieLinks(
        animeId: Int,
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
            val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations

            for (translation in translations) {
                val translationId = translation.translation.id
                for (player in translation.player) {
                    val collected = mutableListOf<FundubEpisode>()
                    val seenIds = mutableSetOf<Int>()
                    val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=100&playerId=${player.id}&translationId=$translationId"

                    val epJsonMinus1 = fetchJsonWithRetry("$baseUrl&skip=-1")
                    if (epJsonMinus1 != null) {
                        val eps = try { Gson().fromJson(epJsonMinus1, PlayerEpisodes::class.java).episodes } catch (e: Exception) { null }
                        eps?.filter { it.episode <= 0 && seenIds.add(it.id) }?.let { collected.addAll(it) }
                    }
                    val maxSkip = if (player.episodesCount > 0)
                        (player.episodesCount / 100 + 1) * 100
                    else 11000
                    var skip = 0
                    while (skip <= maxSkip) {
                        val epJson = fetchJsonWithRetry("$baseUrl&skip=$skip") ?: break
                        val eps = try {
                            Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                        } catch (e: Exception) { null }
                        if (eps.isNullOrEmpty()) break
                        val newEps = eps.filter { seenIds.add(it.id) }
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
                                val directSource = Gson().fromJson(directJson, DirectPlayerResponse::class.java)
                                val videoUrl = directSource.videoUrl
                                val fileUrl = directSource.fileUrl
                                if (!videoUrl.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
                                    if (isAshdi) {
                                        if (!videoUrl.isNullOrEmpty() && videoUrl.contains("ashdi.vip")) {
                                            processAshdiIframe(videoUrl, sourceName, isMovie = true, callback)
                                            foundAny = true
                                        } else if (!fileUrl.isNullOrEmpty()) {
                                            val streams = M3u8Helper.generateM3u8(
                                                source = sourceName,
                                                streamUrl = fileUrl,
                                                referer = "https://ashdi.vip"
                                            )
                                            val filtered = streams.dropLast(1)
                                            if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                            else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                            foundAny = true
                                        }
                                    } else {
                                        if (!fileUrl.isNullOrEmpty()) {
                                            val streams = M3u8Helper.generateM3u8(
                                                source = sourceName,
                                                streamUrl = fileUrl,
                                                referer = "https://moonanime.art/",
                                                headers = moonVideoHeaders
                                            )
                                            val filtered = streams.dropLast(1)
                                            if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                            else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                            foundAny = true
                                        } else if (!videoUrl.isNullOrEmpty() && videoUrl.contains("moonanime.art")) {
                                            if (videoUrl.contains("m3u8")) {
                                                val streams = M3u8Helper.generateM3u8(
                                                    source = sourceName,
                                                    streamUrl = videoUrl,
                                                    referer = "https://moonanime.art/",
                                                    headers = moonVideoHeaders
                                                )
                                                val filtered = streams.dropLast(1)
                                                if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                                else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                                foundAny = true
                                            } else {
                                                val rawFile = getMoonFile(videoUrl)
                                                if (rawFile.isNotEmpty()) {
                                                    processMoonRawFile(rawFile, sourceName, isMovie = true, callback)
                                                    foundAny = true
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) { }
                        }
                        continue
                    }

                    for (ep in collected) {
                        try {
                            if (isAshdi) {
                                if (!ep.videoUrl.isNullOrEmpty() && ep.videoUrl.contains("ashdi.vip")) {
                                    processAshdiIframe(ep.videoUrl, sourceName, isMovie = true, callback)
                                    foundAny = true
                                } else if (!ep.fileUrl.isNullOrEmpty()) {
                                    val streams = M3u8Helper.generateM3u8(
                                        source = sourceName,
                                        streamUrl = ep.fileUrl,
                                        referer = "https://ashdi.vip"
                                    )
                                    val filtered = streams.dropLast(1)
                                    if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                    else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                    foundAny = true
                                }
                            } else {
                                if (!ep.fileUrl.isNullOrEmpty()) {
                                    val streams = M3u8Helper.generateM3u8(
                                        source = sourceName,
                                        streamUrl = ep.fileUrl,
                                        referer = "https://moonanime.art/",
                                        headers = moonVideoHeaders
                                    )
                                    val filtered = streams.dropLast(1)
                                    if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                    else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                    foundAny = true
                                } else if (!ep.videoUrl.isNullOrEmpty() && ep.videoUrl.contains("moonanime.art")) {
                                    if (ep.videoUrl.contains("m3u8")) {
                                        val streams = M3u8Helper.generateM3u8(
                                            source = sourceName,
                                            streamUrl = ep.videoUrl,
                                            referer = "https://moonanime.art/",
                                            headers = moonVideoHeaders
                                        )
                                        val filtered = streams.dropLast(1)
                                        if (filtered.isNotEmpty()) filtered.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                        else streams.forEach { callback(fixMovieExtractorLink(it, sourceName)) }
                                        foundAny = true
                                    } else {
                                        val rawFile = getMoonFile(ep.videoUrl)
                                        if (rawFile.isNotEmpty()) {
                                            processMoonRawFile(rawFile, sourceName, isMovie = true, callback)
                                            foundAny = true
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
            }
        } catch (e: Exception) { }

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
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
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
                val qUrl       = match.groupValues[2]
                val qualityInt = qualityStr.replace("p", "").toIntOrNull()
                    ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value

                when {
                    qUrl.contains(".m3u8") -> {
                        val streams = M3u8Helper.generateM3u8(
                            source    = sourceName,
                            streamUrl = qUrl,
                            referer   = "https://moonanime.art/",
                            headers   = moonVideoHeaders
                        )
                        val filtered = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams
                        finalStreams.forEach {
                            // fixMovieExtractorLink виправляє і назву (sourceName) і якість для фільму
                            // для серіалу — лише перейменовуємо на sourceName щоб UI не дублював якість
                            callback(fixMovieExtractorLink(it, sourceName))
                        }
                    }
                    qUrl.contains("s.moonanime.art") || qUrl.contains("moonanime.art/content") -> {
                        val finalUrl = resolveMoonContent(qUrl)
                        if (!finalUrl.isNullOrEmpty()) {
                            val link = ExtractorLink(
                                source  = name,
                                name    = sourceName,
                                url     = finalUrl,
                                referer = "https://moonanime.art/",
                                quality = qualityInt,
                                type    = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                headers = moonVideoHeaders
                            )
                            callback(fixMovieExtractorLink(link, sourceName))
                        }
                    }
                    else -> {
                        val link = ExtractorLink(
                            source  = name,
                            name    = sourceName,
                            url     = qUrl,
                            referer = "https://moonanime.art/",
                            quality = qualityInt,
                            type    = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                            headers = moonVideoHeaders
                        )
                        callback(fixMovieExtractorLink(link, sourceName))
                    }
                }
            }
        } else if (rawFile.contains(".m3u8")) {
            val streams = M3u8Helper.generateM3u8(
                source    = sourceName,
                streamUrl = rawFile,
                referer   = "https://moonanime.art/",
                headers   = moonVideoHeaders
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
                    source  = name,
                    name    = sourceName,
                    url     = finalUrl,
                    referer = "https://moonanime.art/",
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                    type    = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                    headers = moonVideoHeaders
                )
                callback(fixMovieExtractorLink(link, sourceName))
            }
        } else {
            val link = ExtractorLink(
                source  = name,
                name    = sourceName,
                url     = rawFile,
                referer = "https://moonanime.art/",
                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                type    = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                headers = moonVideoHeaders
            )
            callback(fixMovieExtractorLink(link, sourceName))
        }
    }

    private suspend fun processAshdiIframe(iframeUrl: String, sourceName: String, isMovie: Boolean, callback: (ExtractorLink) -> Unit) {
        try {
            val cleanUrl = iframeUrl
                .replace(Regex("""\?season=null\?"""), "?")
                .replace(Regex("""\?season=null$"""), "")
            val url = if (cleanUrl.contains("?")) cleanUrl else "$cleanUrl?player=animeon.club"
            val html = app.get(url, headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
            ), cacheTime = 0).text

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
        } catch (e: Exception) { }
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
        } catch (e: Exception) { "" }
    }

    private fun moonOuterDecode(base64Blob: String): String {
        return try {
            val raw = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)
            if (raw.size < 33) return ""

            val state0 = raw[0].toInt() and 0xFF
            val key = raw.sliceArray(1 until 33)
            val data = raw.sliceArray(33 until raw.size)

            val result = StringBuilder()
            var state = state0
            for (i in data.indices) {
                val d = data[i].toInt() and 0xFF
                val k = key[i % 32].toInt() and 0xFF
                val dec = d xor k xor state
                result.append(dec.toChar())
                state = (d + k) and 0xFF
            }
            result.toString()
        } catch (e: Exception) { "" }
    }

    private suspend fun getMoonFile(iframeUrl: String): String {
        val cleanUrl = iframeUrl
            .replace(Regex("[?&]player=[^&]*"), "")
            .replace("?&", "?")
            .trimEnd('?', '&')

        val html = try {
            app.get(cleanUrl, headers = mapOf(
                "User-Agent"                to userAgent,
                "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language"           to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer"                   to "https://animeon.club/",
                "X-Requested-With"          to "mark.via.gp",
                "Sec-Fetch-Site"            to "none",
                "Sec-Fetch-Mode"            to "navigate",
                "Sec-Fetch-User"            to "?1",
                "Sec-Fetch-Dest"            to "document",
                "Upgrade-Insecure-Requests" to "1"
            ), cacheTime = 0).text
        } catch (e: Exception) { "" }

        if (html.isNotEmpty()) {
            val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
            val atobMatch = atobRegex.find(html)?.groupValues?.get(1)

            if (!atobMatch.isNullOrEmpty()) {
                val decodedJs = moonOuterDecode(atobMatch)
                if (decodedJs.isNotEmpty()) {

                    val keyRegex = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
                    val xorKey = keyRegex.find(decodedJs)?.groupValues?.get(1)

                    if (!xorKey.isNullOrEmpty()) {
                        val encodedRegex = Regex("""_0xd\s*\(\s*["']([^"']+)["']\s*\)""")
                        val matches = encodedRegex.findAll(decodedJs).toList()

                        val allDecoded = mutableListOf<String>()
                        for (match in matches) {
                            val decoded = moonDecrypt(match.groupValues[1], xorKey)
                            if (decoded.isNotEmpty()) {
                                allDecoded.add(decoded)
                            }
                        }

                        for (decoded in allDecoded) {
                            val isVideoOrPlaylist = decoded.contains(".m3u8") || decoded.contains(".mp4") || decoded.contains(".webm") || decoded.startsWith("[")
                            val isMoonDomain = decoded.contains("mooncdn") || decoded.contains("moonanime.art/content") || decoded.contains("s.moonanime.art")
                            val isStaticAsset = decoded.contains(Regex("""\.(jpg|jpeg|png|vtt|srt|txt)(\?|$)""", RegexOption.IGNORE_CASE))

                            if ((isVideoOrPlaylist || isMoonDomain) && !isStaticAsset) {
                                return decoded
                            }
                        }
                    }

                    val contentUrlRegex = Regex("""(https?://s\.moonanime\.art/content/[^\s"'`]+)""")
                    val contentMatch = contentUrlRegex.find(decodedJs)?.groupValues?.get(1)
                    if (!contentMatch.isNullOrEmpty() && !contentMatch.contains(Regex("""\.(jpg|jpeg|png)$"""))) {
                        val resolved = resolveMoonContent(contentMatch)
                        if (!resolved.isNullOrEmpty()) {
                            return resolved
                        }
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
                return qualityResults.joinToString(",")
            }
        }

        return ""
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") return value.value.drop(1).toIntOrNull()
        return value.value.toIntOrNull()
    }
}