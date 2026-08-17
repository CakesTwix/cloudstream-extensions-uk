package com.lagradost

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log.d
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.model.EpisodeSource
import com.lagradost.model.MovieItem
import com.lagradost.model.MovieResponse
import com.lagradost.model.NuxtResolver
import com.lagradost.model.PageData
import com.lagradost.model.SeriesData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KinostrainProvider : MainAPI() {
    private val movieSelector = "div.grid > a"
    private val movieSelectorWithPage = "div.grid > article"
    private val titleSelector = "h3.text-foreground"
    private val posterUrlSelector = "img"
    private val mapper = JsonMapper.builder()
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .addModule(KotlinModule.Builder().build()).build()

    // Basic Info
    override var mainUrl = "https://kinostrain.com"
    override var name = "Kinostrain"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/trends" to "В тренді",
        "$mainUrl/trends/movies" to "В тренді фільми",
        "$mainUrl/trends/serials" to "В тренді серіали",
        "$mainUrl/trends/cartoon-movies" to "В тренді мультфільми",
        "$mainUrl/trends/cartoon-series" to "В тренді мультсеріали",
        "$mainUrl/trends/anime" to "В тренді аніме",
        "$mainUrl/?page=" to "Все",
        "$mainUrl/movies?page=" to "Фільми",
        "$mainUrl/serials?page=" to "Серіали",
        "$mainUrl/cartoon-movies?page=" to "Мультфільми",
        "$mainUrl/cartoon-series?page=" to "Мультсеріали",
        "$mainUrl/anime?page=" to "Аніме",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val containsPage = request.data.contains("page=")
        if (page > 1 && !containsPage) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (request.data.endsWith("=")) "${request.data}$page" else request.data
        val document = app.get(url).document
        val currentSelector = if (containsPage) movieSelectorWithPage else movieSelector
        val items = document.select(currentSelector)

        if (items.isEmpty()) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val mainPage = if (containsPage) {
            items.mapNotNull { it.toSearchResponseWithPage() }
                .filter { !it.posterUrl.isNullOrEmpty() }
        } else {
            items.mapNotNull { it.toSearchResponse() }
                .filter { !it.posterUrl.isNullOrEmpty() }
        }
        val hasNextPage = containsPage && mainPage.size >= 10
        return newHomePageResponse(request.name, mainPage, hasNext = hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://api.kinostrain.com/api/search?q=$query&limit=10"
        val response = app.get(url).text
        val readValue = mapper.readValue<MovieResponse>(response)

        return readValue.data.map { data ->
            val title = data.name
            val itemUrl = getSearchItemUrl(data)
//            d("DEBUG search", "itemUrl$itemUrl")
            val posterUrl = data.posterUrl
            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun getSearchItemUrl(data: MovieItem): String {
        return if (data.type == "movie") {
            "$mainUrl/${data.type}-${data.slug}"
        } else {
            "$mainUrl/${data.slug}/season-${data.firstReadySeason?.number}"
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val movieType = getMovieType(url)
        val pageData = getPageData(url)

//        d("DEBUG load", "pageData:$pageData")
        if (movieType == TvType.TvSeries) {
            val episodes = getEpisodes(url, pageData.source)
//            d("DEBUG load", "episodes:$episodes")
            return newAnimeLoadResponse(pageData.title, url, TvType.TvSeries) {
                this.posterUrl = pageData.poster
                this.engName = pageData.engTitle
                this.year = pageData.year
                this.plot = pageData.description
                this.tags = pageData.tags
                this.contentRating = pageData.rating
                addActors(pageData.actors)
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        }

        val movieLinks = getMovieData(pageData.source)
        return newMovieLoadResponse(pageData.title, url, TvType.Movie, movieLinks) {
            this.posterUrl = pageData.poster
            this.name = "${pageData.title} (${pageData.engTitle})"
            this.year = pageData.year
            this.plot = pageData.description
            this.tags = pageData.tags
            this.contentRating = pageData.rating
            addActors(pageData.actors)
            addTrailer(pageData.trailer)
        }
    }

    private fun getMovieData(document: Document): String {
        val script = document.selectFirst("script#__NUXT_DATA__")?.data()
        if (script.isNullOrEmpty()) {
            return ""
        }

        val jsonArray = JSONArray(script)
        val resolver = NuxtResolver(jsonArray)

        var contentIndex = -1
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            val keys = item.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("content-")) {
                    contentIndex = item.getInt(key)
                    break
                }
            }
            if (contentIndex != -1) break
        }

        if (contentIndex == -1) return ""

        var content = resolver.getObject(contentIndex) ?: return ""
        val wrappedContent = resolver.resolveObject(content, "data")
        if (wrappedContent != null) {
            content = wrappedContent
        }
        val seasonsArray = resolver.resolveArray(content, "seasons") ?: return ""
        val movieSources = mutableListOf<EpisodeSource>()

        for (i in 0 until seasonsArray.length()) {
            val seasonIdx = seasonsArray.getInt(i)
            val seasonObj = resolver.getObject(seasonIdx) ?: continue
            val playerData = resolver.resolveObject(seasonObj, "playerData") ?: continue

            // For movies, providers are either nested under key "1" or directly in playerData
            val epPlayerData = resolver.resolveObject(playerData, "1") ?: playerData

            val pKeys = epPlayerData.keys()
            while (pKeys.hasNext()) {
                val pKey = pKeys.next()
                val sourcesArray = resolver.resolveArray(epPlayerData, pKey) ?: continue
                for (k in 0 until sourcesArray.length()) {
                    val srcIdx = sourcesArray.getInt(k)
                    val srcObj = resolver.getObject(srcIdx) ?: continue
                    val name = resolver.resolveString(srcObj, "name") ?: pKey
                    val link = resolver.resolveString(srcObj, "link") ?: continue
                    movieSources.add(EpisodeSource(name, link))
                }
            }
        }

        return mapper.writeValueAsString(movieSources)
    }

    private suspend fun getEpisodes(url: String, document: Document): List<Episode> =
        coroutineScope {
            val seasonLinks = document.select("div.seasons-grid a.season-item")

            if (seasonLinks.isEmpty()) {
                return@coroutineScope parseNuxtEpisodes(url, document)
            }

//        d("DEBUG getEpisodes", "Found ${seasonLinks.size} season links")

            seasonLinks.map { element ->
                async {
                    val seasonUrl = fixUrl(element.attr("href"))
//                d("DEBUG getEpisodes", "Fetching season page: $seasonUrl")
                    val seasonDoc = if (seasonUrl == url) document else app.get(seasonUrl).document
                    parseNuxtEpisodes(url, seasonDoc)
                }
            }.awaitAll().flatten()
        }

    private fun parseNuxtEpisodes(url: String, document: Document): List<Episode> {
        val script = document.selectFirst("script#__NUXT_DATA__")?.data()
        if (script.isNullOrEmpty()) {
            return emptyList()
        }

        val jsonArray = JSONArray(script)
        val resolver = NuxtResolver(jsonArray)

        var contentIndex = -1
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            val keys = item.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("content-")) {
                    contentIndex = item.getInt(key)
                    break
                }
            }
            if (contentIndex != -1) break
        }

        if (contentIndex == -1) return emptyList()

        var content = resolver.getObject(contentIndex) ?: return emptyList()
        val wrappedContent = resolver.resolveObject(content, "data")
        if (wrappedContent != null) {
            content = wrappedContent
        }

        val seasonsArray = resolver.resolveArray(content, "seasons") ?: return emptyList()
        val allEpisodes = mutableListOf<Episode>()

        for (i in 0 until seasonsArray.length()) {
            val seasonIdx = seasonsArray.getInt(i)
            val seasonObj = resolver.getObject(seasonIdx) ?: continue

            // Only parse seasons that actually contain episode data in this document
            val episodesArray = resolver.resolveArray(seasonObj, "episodes") ?: continue

            val seasonNumber = resolver.resolveInt(seasonObj, "number") ?: (i + 1)
            val playerData = resolver.resolveObject(seasonObj, "playerData") ?: continue

            // Extract episode posters/frames if available
            val framesArray = resolver.resolveArray(seasonObj, "frames")
            val frameMap = mutableMapOf<Int, String>()
            if (framesArray != null) {
                for (k in 0 until framesArray.length()) {
                    val frameIdx = framesArray.getInt(k)
                    val frameObj = resolver.getObject(frameIdx) ?: continue
                    val epNum = resolver.resolveInt(frameObj, "episodeNumber") ?: continue
                    val frameUrl = resolver.resolveString(frameObj, "url") ?: continue
                    frameMap[epNum] = frameUrl
                }
            }

            for (j in 0 until episodesArray.length()) {
                val epIdx = episodesArray.getInt(j)
                val epObj = resolver.getObject(epIdx) ?: continue
                val epNumber = resolver.resolveInt(epObj, "number") ?: (j + 1)
                val epName = resolver.resolveString(epObj, "name") ?: "Серія $epNumber"

                val epPlayerData = resolver.resolveObject(playerData, epNumber.toString())
                if (epPlayerData != null) {
                    val episodeSources = mutableListOf<EpisodeSource>()
                    val pKeys = epPlayerData.keys()
                    while (pKeys.hasNext()) {
                        val pKey = pKeys.next()
                        val sourcesArray = resolver.resolveArray(epPlayerData, pKey) ?: continue
                        for (k in 0 until sourcesArray.length()) {
                            val srcIdx = sourcesArray.getInt(k)
                            val srcObj = resolver.getObject(srcIdx) ?: continue
                            val name = resolver.resolveString(srcObj, "name") ?: pKey
                            val link = resolver.resolveString(srcObj, "link") ?: continue
                            episodeSources.add(EpisodeSource(name, link))
                        }
                    }

                    if (episodeSources.isNotEmpty()) {
                        allEpisodes.add(newEpisode(url) {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = frameMap[epNumber]
                            this.data = mapper.writeValueAsString(episodeSources)
                        })
                    }
                }
            }
        }
        return allEpisodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
//        d("DEBUG loadLinks", "Data: $data")

        if (data.startsWith("[")) {
            val sources = mapper.readValue<List<EpisodeSource>>(data)
            for (source in sources) {
                if (source.link.contains("ashdi.vip")) {
                    val response = app.get(source.link, referer = mainUrl).text
                    val file =
                        Regex("file\\s*:\\s*['\"](.*?)['\"]").find(response)?.groupValues?.get(1)
                    val subtitle =
                        Regex("subtitle\\s*:\\s*['\"](.*?)['\"]").find(response)?.groupValues?.get(1)

                    if (file != null) {
                        M3u8Helper.generateM3u8(
                            source = source.name,
                            streamUrl = file,
                            referer = source.link
                        ).forEach(callback)
                    }

                    subtitle?.split(",")?.forEachIndexed { index, sub ->
                        val subName =
                            sub.substringAfter("[").substringBefore("]", "sub$index")
                        val subUrl = sub.substringAfter("]")
                        if (subUrl.isNotBlank()) {
                            d("DEBUG loadLinks", "subUrl: $subUrl subName: $subName")
                            subtitleCallback.invoke(SubtitleFile(subName, subUrl))
                        }
                    }
                } else if (source.link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = source.name,
                        streamUrl = source.link,
                        referer = mainUrl
                    ).forEach(callback)
                } else {
                    callback(
                        newExtractorLink(
                            source.name,
                            source.name,
                            source.link,
                        )
                    )
                }
            }
            return true
        }

        // Legacy handling
        runCatching {
            val seriesList = mapper.readValue<List<SeriesData>>(data)
            seriesList.forEach { seriesData ->
                val savedM3uUrl = seriesData.video.orEmpty()
                val dubName = seriesData.voiceType ?: this.name
                if (savedM3uUrl.isNotBlank()) {
                    M3u8Helper.generateM3u8(
                        source = dubName,
                        streamUrl = savedM3uUrl,
                        referer = mainUrl
                    ).forEach(callback)
                }
            }
        }
        return true
    }


    private suspend fun getPageData(movieUrl: String): PageData {
        val document = app.get(movieUrl).document
        return PageData(
            title = getPageTitle(document),
            poster = getPagePosterUrl(document),
            description = getDescription(document),
            year = getYear(document),
            tags = getGenres(document),
            actors = getActors(document),
            rating = getRating(document),
            engTitle = getPageEngTitle(document),
            trailer = getTrailer(document),
            source = document
        )
    }

    private fun getMovieType(url: String): TvType =
        if (url.contains("/movie-")) TvType.Movie else TvType.TvSeries

    private fun getTrailer(document: Document): String {
        val jsonLd = getJsonLd(document) ?: return ""
        return runCatching {
            val jsonObject = jsonLd.getJSONObject("trailer")
            val embedUrl = jsonObject.getString("embedUrl")
            "https://www.youtube.com/watch?v=${embedUrl.substringAfter("embed/")}"
        }.getOrDefault("")
    }

    private fun getActors(document: Document): List<String> {
        val jsonLd = getJsonLd(document) ?: return emptyList()
        return runCatching {
            val jsonArray = jsonLd.getJSONArray("actor")
            (0 until jsonArray.length()).map { index ->
                jsonArray.getJSONObject(index).getJSONObject("actor").getString("name")
            }
        }.getOrDefault(emptyList())
    }

    private fun getGenres(document: Document): List<String> {
        val jsonLd = getJsonLd(document) ?: return emptyList()
        return runCatching {
            val jsonArray = jsonLd.getJSONArray("genre")
            List(jsonArray.length()) { jsonArray.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun getJsonLd(document: Document): JSONObject? {
        val scriptData =
            document.selectFirst("script[type=application/ld+json]")?.data()?.trim() ?: return null
        return runCatching { JSONObject(scriptData) }.getOrNull()
    }

    private fun getDescription(document: Document): String {
        val jsonLd = getJsonLd(document) ?: return ""
        return jsonLd.optString("description", "")
    }

    private fun getRating(document: Document): String {
        val jsonLd = getJsonLd(document) ?: return ""
        return jsonLd.optJSONObject("aggregateRating")?.optString("ratingValue", "") ?: ""
    }

    private fun Element.toSearchResponse(): MovieSearchResponse {
        val title = this.select(titleSelector).text()
        val url = fixUrl(this.attr("href"))
        val posterUrl = this.select(posterUrlSelector).attr("src")
        return newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private fun Element.toSearchResponseWithPage(): MovieSearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.text()
        val url = fixUrl(a.attr("href"))
        val posterUrl = this.select("img").attr("src")
        return newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private fun getYear(document: Document): Int =
        document.selectFirst("div:contains(IMDb) + div")?.text()?.toIntOrNull() ?: 0

    private fun getPagePosterUrl(document: Document): String =
        getJsonLd(document)?.optString("image", "") ?: ""

    private fun getPageEngTitle(document: Document): String =
        getJsonLd(document)?.optString("alternateName", "") ?: ""

    private fun getPageTitle(document: Document): String =
        getJsonLd(document)?.optString("name", "") ?: ""
}
