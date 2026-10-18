package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class UaKinoCXProvider : MainAPI() {

    override var mainUrl = "https://uakino.cx"
    override var name = "UaKinoCX"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/cinema/page/" to "Фільми",
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/caartons/page/" to "Мультфільми",
        "$mainUrl/anime/page/" to "Аніме",
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url, headers = defaultHeaders).document
        val home = document.select(".grid-items__item, .item").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("a.item__title, .item__title") ?: return null
        val title = titleElement.text().trim()
        if (title.isBlank()) return null
        val href = fixUrl(titleElement.attr("href").ifBlank { this.selectFirst("a")?.attr("href") ?: "" })
        val posterUrl = fixUrlNull(this.selectFirst(".item__img img, img")?.attr("src"))
        val label = this.selectFirst(".item__label:not(.age)")?.text()?.trim().orEmpty()

        val isSeries = label.contains("сезон", ignoreCase = true) || href.contains("/series/")
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (isSeries) {
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                addDubStatus(isDub = true)
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val document = app.get(searchUrl, headers = defaultHeaders).document

        return document.select(".grid-items__item, .item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst(".page__header h1, h1")?.let { h1 ->
            val clone = h1.clone()
            clone.select("small").remove()
            clone.text().trim()
        } ?: document.selectFirst("h1")?.text()?.substringBefore("(")?.trim().orEmpty()

        val engTitle = document.selectFirst("[itemprop=alternativeHeadline]")?.text()?.trim()
        val poster = fixUrlNull(document.selectFirst(".page__img img, .page__poster img")?.attr("src"))
        val description = document.selectFirst(".page__text .full-text, .page__text")?.text()?.trim()

        val year = document.selectFirst("[itemprop=dateCreated]")?.text()?.toIntOrNull()
            ?: document.selectFirst(".page__header h1 small, h1 small")?.text()?.let {
                Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value?.toIntOrNull()
            }

        val rating = document.selectFirst(".page__ratingscore-ring")?.text()?.trim()
        val contentRating = document.selectFirst(".page__poster .item__label.age, .item__label.age")?.text()?.trim()

        var country: String? = null
        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()

        document.select(".page__list li").forEach { item ->
            val label = item.selectFirst("span")?.text()?.trim().orEmpty()
            val text = item.text().substringAfter(":").trim()
            when {
                label.contains("Країна", ignoreCase = true) -> country = text
                label.contains("Жанр", ignoreCase = true) -> {
                    text.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tags.add(it) }
                }
                label.contains("Актори", ignoreCase = true) -> {
                    text.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { actors.add(it) }
                }
            }
        }

        val plot = if (!country.isNullOrBlank()) "<b>Країна: $country.</b> $description" else description
        val trailer = extractUaKinoCXTrailer(document)
        val playerUrls = extractUaKinoCXPlayerUrls(document)

        val headerText = document.selectFirst(".page__header h1, h1")?.text().orEmpty()
        val isSeries = headerText.contains("сезон", ignoreCase = true) ||
            document.title().contains("серіал", ignoreCase = true) ||
            tags.any { it.contains("серіал", ignoreCase = true) } ||
            url.contains("/series/")

        val tvType = when {
            tags.any { it.contains("аніме", ignoreCase = true) } -> TvType.Anime
            tags.any { it.contains("мульт", ignoreCase = true) } -> TvType.Cartoon
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        // Recommendations from sidebar / related
        val recommendations = document.select(".page__fr .fr, .item").mapNotNull {
            val recTitle = it.selectFirst("a.fr__title, a.item__title")?.text()?.trim() ?: return@mapNotNull null
            val recHref = fixUrl(it.selectFirst("a.fr__title, a.item__title")?.attr("href").orEmpty())
            val recPoster = fixUrlNull(it.selectFirst("img")?.attr("src"))
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        if (tvType != TvType.Movie) {
            val episodes = mutableListOf<Episode>()
            val hdvbuaUrl = playerUrls.firstOrNull { it.contains("hdvbua.pro", ignoreCase = true) }

            if (!hdvbuaUrl.isNullOrBlank()) {
                val playerDoc = app.get(hdvbuaUrl, headers = mapOf("Referer" to "$mainUrl/")).document
                val scriptHtml = playerDoc.select("script").html()
                val rawFile = extractHdvbuaFile(scriptHtml)

                if (!rawFile.isNullOrBlank()) {
                    val parsedJson = tryParseJson<List<PlayerJson>>(rawFile)
                    if (parsedJson != null) {
                        val flattened = flattenPlayerJson(parsedJson)
                        flattened.groupBy { (it.seasonNumber ?: 1) to (it.episodeNumber ?: 1) }
                            .forEach { (key, dubs) ->
                                val first = dubs.first()
                                val dataStr = "${first.seasonName}, ${first.episodeName}, $hdvbuaUrl"
                                episodes.add(
                                    newEpisode(dataStr) {
                                        this.name = first.episodeName
                                        this.season = key.first
                                        this.episode = key.second
                                        this.posterUrl = first.poster
                                        this.data = dataStr
                                    }
                                )
                            }
                    }
                }
            }

            val comingSoon = isUaKinoCXComingSoon(hasPlayableMedia = episodes.isNotEmpty())
            val response = newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.engName = engTitle
                this.year = year
                this.plot = plot
                this.tags = tags
                this.contentRating = contentRating
                this.score = Score.from10(rating)
                addEpisodes(DubStatus.Dubbed, episodes.sortedWith(compareBy({ it.season }, { it.episode })))
                addActors(actors)
                this.recommendations = recommendations
                trailer?.let { addTrailer(it) }
            }
            if (comingSoon) response.comingSoon = true
            return response
        } else {
            val comingSoon = isUaKinoCXComingSoon(hasPlayableMedia = playerUrls.isNotEmpty())
            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.name = title
                this.year = year
                this.plot = plot
                this.tags = tags
                this.contentRating = contentRating
                this.score = Score.from10(rating)
                this.comingSoon = comingSoon
                addActors(actors)
                this.recommendations = recommendations
                trailer?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            foundLinks = true
            callback(link)
        }

        val dataList = data.split(", ")

        if (dataList.size >= 3) {
            val targetSeason = dataList[0].trim()
            val targetEpisode = dataList[1].trim()
            val playerUrl = dataList[2].trim()

            val playerDoc = app.get(playerUrl, headers = mapOf("Referer" to "$mainUrl/")).document
            val scriptHtml = playerDoc.select("script").html()
            val rawFile = extractHdvbuaFile(scriptHtml) ?: return false

            val parsedJson = tryParseJson<List<PlayerJson>>(rawFile) ?: return false
            val flattened = flattenPlayerJson(parsedJson)

            flattened.filter {
                it.seasonName.equals(targetSeason, ignoreCase = true) &&
                    it.episodeName.equals(targetEpisode, ignoreCase = true) &&
                    it.file.isNotBlank()
            }.forEach { dub ->
                M3u8Helper.generateM3u8(
                    source = dub.dubName ?: name,
                    streamUrl = dub.file,
                    referer = "https://hdvbua.pro/",
                ).forEach(wrappedCallback)

                parseUaKinoCXSubtitle(dub.subtitle)?.let { sub ->
                    subtitleCallback.invoke(newSubtitleFile(sub.language, sub.url))
                }
            }

            return foundLinks
        }

        // Movie handling
        val movieDoc = app.get(data, headers = defaultHeaders).document
        val playerUrls = extractUaKinoCXPlayerUrls(movieDoc)
        if (playerUrls.isEmpty()) return false

        for (playerUrl in playerUrls) {
            if (playerUrl.contains("hdvbua.pro", ignoreCase = true)) {
                try {
                    val playerDoc = app.get(playerUrl, headers = mapOf("Referer" to "$mainUrl/")).document
                    val scriptHtml = playerDoc.select("script").html()
                    val rawFile = extractHdvbuaFile(scriptHtml)

                    if (!rawFile.isNullOrBlank()) {
                        val parsedJson = tryParseJson<List<PlayerJson>>(rawFile)
                        if (parsedJson != null) {
                            val flattened = flattenPlayerJson(parsedJson)
                            flattened.filter { it.file.isNotBlank() }.forEach { dub ->
                                M3u8Helper.generateM3u8(
                                    source = dub.dubName ?: dub.episodeName.ifBlank { name },
                                    streamUrl = dub.file,
                                    referer = "https://hdvbua.pro/",
                                ).forEach(wrappedCallback)

                                parseUaKinoCXSubtitle(dub.subtitle)?.let { sub ->
                                    subtitleCallback.invoke(newSubtitleFile(sub.language, sub.url))
                                }
                            }
                        } else if (rawFile.startsWith("http://") || rawFile.startsWith("https://")) {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = rawFile,
                                referer = "https://hdvbua.pro/",
                            ).forEach(wrappedCallback)
                        }
                    }
                } catch (e: Throwable) {
                    // Ignore and try next player
                }
            } else if (playerUrl.contains("ortified.ws", ignoreCase = true)) {
                try {
                    val ortifiedDoc = app.get(playerUrl, headers = mapOf("Referer" to "$mainUrl/")).document
                    val scriptHtml = ortifiedDoc.select("script").html()
                    val hlsUrl = extractOrtifiedHlsUrl(scriptHtml)

                    if (!hlsUrl.isNullOrBlank()) {
                        M3u8Helper.generateM3u8(
                            source = "Ortified",
                            streamUrl = hlsUrl,
                            referer = "https://api.ortified.ws/",
                        ).forEach(wrappedCallback)
                    }
                } catch (e: Throwable) {
                    // Ignore and try next player
                }
            } else {
                loadExtractor(playerUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
            }
        }

        return foundLinks
    }
}
