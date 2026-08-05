package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.net.URL
import java.util.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class UakinoProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://uakino.best"
    override var name = "Uakino"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Sections
    override val mainPage =
        mainPageOf(
            "$mainUrl/filmy/page/" to "Фільми",
            "$mainUrl/seriesss/page/" to "Серіали",
            "$mainUrl/seriesss/doramy/page/" to "Дорами",
            "$mainUrl/cartoon/page/" to "Мультфільми",
            "$mainUrl/cartoon/cartoonseries/page/" to "Мультсеріали",
            "$mainUrl/animeukr/page/" to "Аніме",
        )

    val blackUrls = "(/news/)|(/franchise/)"
    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()
    val subsRegex = "subtitle\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()

    private fun Document.isDetailPage(): Boolean =
        selectFirst("h1 span.solototle, div.film-poster, div.playlists-ajax") != null

    private val UA = "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.7778.215 Mobile Safari/537.36"
    private fun headers(referer: String = mainUrl) = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer,
    )
    private val ajaxHeaders = mapOf(
        "Referer" to mainUrl,
        "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to UA,
    )

    private suspend fun fetchDetail(url: String): Document? =
        app.get(url, headers = headers()).document.takeIf { it.isDetailPage() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, headers = headers()).document
        val home =
            document
                .select("div.owl-item, div.movie-item")
                .filterNot { el ->
                    val href = el.select("a.movie-title, a.full-movie").attr("href")
                    val genre = el.select(".fi-label:contains(Жанр:) + .deck-value").text()
                    href.contains(Regex(blackUrls)) ||
                            (request.name == "Серіали" && (genre.contains("Дорами") || genre.contains("Мультсеріали"))) ||
                            (request.name == "Мультфільми" && href.contains("/cartoonseries/"))
                }
                .map {
                    // Log.d("CakesTwix-Debug", it.select("a.movie-title, a.full-movie").attr("href"))
                    it.toSearchResponse()
                }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title =
            this.selectFirst("a.movie-title, div.full-movie-title")?.text()?.trim().toString()
        val href = this.selectFirst("a.movie-title, a.full-movie")?.attr("href").toString()
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private suspend fun Element.getSeasonInfo(): SearchResponse {
        // Log.d("CakesTwix-Debug", "getSeasonInfo: ${this.attr("href")}")
        val document = app.get(this.attr("href"), headers = headers()).document
        val title = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val poster = mainUrl + document.selectFirst("div.film-poster img")?.attr("src").toString()

        return newMovieSearchResponse(title, this.attr("href"), TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document =
            app.post(
                url = "$mainUrl/ua/",
                data =
                    mapOf(
                        "do" to "search",
                        "subaction" to "search",
                        "story" to query.replace(" ", "+")
                    ),
                headers = headers()
            ).document

        return document
            .select("div.movie-item.short-item")
            .filterNot { el ->
                el.select("a.movie-title, a.full-movie").attr("href").contains(Regex(blackUrls))
            }
            .map { it.toSearchResponse() }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document =
            fetchDetail(url)
                ?: throw Exception("Не вдалося завантажити сторінку: $url")

        // Parse info
        val title = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val engTitle = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val poster = fixUrl(document.selectFirst("div.film-poster img")?.attr("src").toString())

        var tags = emptyList<String>()
        var year = 2023
        var actors = emptyList<String>()
        var rating = "0"
        var contentRating: String? = null
        var countries: String? = null

        document.select(".fi-item-s, .fi-item").forEach { metadata ->
            with(metadata.select(".fi-label").text()) {
                when {
                    contains("Рік виходу:") -> {
                        year = parseUakinoYear(metadata.select(".fi-desc").text(), year)
                    }
                    contains("Жанр:") -> tags = metadata.select(".fi-desc").text().split(" , ")
                    contains("Актори:") -> actors = metadata.select(".fi-desc").text().split(", ")
                    contains("Вік. рейтинг:") -> contentRating = metadata.select(".fi-desc").text().trim()
                    contains("Країна:") -> countries = metadata.select(".fi-desc").text().trim()
                    contains("") -> {
                        if (!metadata.select(".fi-label").select("img").isEmpty()){
                            rating = metadata.select(".fi-desc").text().substringBefore("/")
                        }
                    }
                }
            }
        }

        // reversed need for check "Мультсеріали"
        // tags: Мультфільми , Мультсеріали
        // It's Cartoon, not Movie
        var tvType =
            with(tags.reversed()) {
                when {
                    contains("Повнометражне аніме") -> TvType.AnimeMovie
                    contains("Мультсеріали") -> TvType.Cartoon
                    contains("Мультфільми") -> TvType.Movie
                    contains("Багатосерійне аніме") -> TvType.Anime
                    contains("Дорами") -> TvType.AsianDrama
                    else -> TvType.Others
            }
        }
        // Log.d("CakesTwix-Debug", tvType.toString())
        if (tvType == TvType.Others) {
            tvType =
                if (url.contains(Regex("(/anime-series)|(/seriesss)|(/cartoonseries)")))
                    TvType.TvSeries
                else TvType.Movie
        }

        val description = document.selectFirst("div[itemprop=description]")?.text()?.trim()
        val plot = if (!countries.isNullOrBlank()) "<b>Країна: $countries.</b> $description" else description
        val trailer = extractUakinoTrailer(document)

        // Add seasons to recommendations
        val recommendations =
            document.select(".seasons li a").map { it.getSeasonInfo() }.toMutableList()
        // Other recommendations
        recommendations += document.select(".related-item").map { it.toSearchResponse() }

        // Return to app
        // Parse Episodes as Series
        return if (tvType != TvType.Movie && tvType != TvType.AnimeMovie) {
            val id = document.selectFirst("div.playlists-ajax")?.attr("data-news_id")
                ?: url.split("/").last().split("-").first()
            val episodes =
                app.get(
                    "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}",
                    headers = ajaxHeaders
                )
                    .parsedSafe<Responses>()
                    ?.response
                    .let {
                        Jsoup.parse(it.toString()).select("div.playlists-videos li").mapNotNull {
                                eps ->
                            val href =
                                "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}"
                            val name = eps.text().trim() // Серія 1
                            if (href.isNotEmpty()) {
                                newEpisode("$href,$name") {
                                    this.name = name
                                    this.data = "$href,$name"
                                }
                            } else {
                                null
                            }
                        }
                    }
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.engName = engTitle
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.contentRating = contentRating
                addActors(actors)
                addEpisodes(DubStatus.None, episodes.distinctBy { it.name })
                this.recommendations = recommendations
                trailer?.let { addTrailer(it) }
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.contentRating = contentRating
                addActors(actors)
                this.recommendations = recommendations
                trailer?.let { addTrailer(it) }
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedData = parseUakinoEpisodeData(data)

        // 1. Визначаємо URL для запиту та назву епізоду (якщо є)
        val (requestUrl, targetEpisode) = if (parsedData.episodeName == null) {
            val id = data.split("/").last().split("-").first()
            "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}" to null
        } else {
            parsedData.requestUrl to parsedData.episodeName
        }
        if (requestUrl.isBlank()) return false

        // 2. Робимо запит до API
        val responseGet = app.get(requestUrl, headers = ajaxHeaders).parsedSafe<Responses>()

        if (responseGet?.success == true) {
            // Логіка для серіалів
            val document = Jsoup.parse(responseGet.response)
            val selector = if (targetEpisode != null) {
                "div.playlists-videos li:contains($targetEpisode)"
            } else {
                "div.playlists-videos li"
            }

            document.select(selector).forEach { eps ->
                // Якщо шукаємо конкретну серію, перевіряємо точний збіг тексту
                if (targetEpisode != null && eps.text() != targetEpisode) return@forEach

                val href = normalizeUakinoPlayerUrl(eps.attr("data-file").trim())
                val dub = eps.attr("data-voice")

                extractPlayerJs(href, dub, callback, subtitleCallback)
            }
        } else {
            // Логіка для фільмів (або якщо AJAX не повернув успіх)
            // Для фільму requestUrl — це AJAX-запит, який часто відповідає ERR_NOT_DATA.
            // Повторно відкриваємо сторінку фільму, а для серії залишаємо URL плеєра.
            val filmDoc = fetchDetail(resolveUakinoDetailUrl(data, targetEpisode, requestUrl))
            val iframeUrl = filmDoc?.selectFirst("iframe#pre")?.attr("src")

            if (iframeUrl != null) {
                val title = filmDoc.selectFirst("h1 span.solototle")?.text()?.trim() ?: "Movie"
                extractPlayerJs(iframeUrl, title, callback, subtitleCallback)
            } else {
                return false
            }
        }

        return true
    }

    private suspend fun extractPlayerJs(
        url: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (url.isBlank()) return
        val scriptData = app.get(url, headers = headers()).document
            .select("script").joinToString("\n") { it.data() }

        val rawFile = fileRegex.findAll(scriptData).map { it.groupValues[1] }
            .firstOrNull { it.contains(".m3u8") }
            ?: fileRegex.find(scriptData)?.groups?.get(1)?.value ?: ""
        val m3uLink = resolveUakinoStreamUrl(rawFile)

        if (!m3uLink.isNullOrBlank()) {
            val playerReferer = URL(url).let { "${it.protocol}://${it.host}/" }
            val streams = M3u8Helper.generateM3u8(
                source = sourceName,
                streamUrl = m3uLink,
                referer = playerReferer
            )
            val filtered = streams.dropLast(1)
            (if (filtered.isNotEmpty()) filtered else streams).forEach(callback)
        }

        val subtitleUrl = subsRegex.find(scriptData)?.groups?.get(1)?.value ?: ""
        if (subtitleUrl.isNotBlank()) {
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitleUrl.substringAfterLast("[").substringBefore("]"),
                    subtitleUrl.substringAfter("]")
                )
            )
        }
    }

    data class Responses(
        val success: Boolean?,
        val response: String,
    )
}

/**
 * Витягує тільки iframe/посилання з вкладки трейлера.
 * Основний Uakino-плеєр також використовує `iframe#pre`, тому його не можна
 * використовувати як універсальний fallback.
 */
internal fun extractUakinoTrailer(document: Document): String? {
    val trailerElements = document.select("*").filter { element ->
        listOf("id", "class", "data-tab", "data-target", "data-content", "data-tab-content")
            .any { attribute -> element.attr(attribute).contains("trailer", ignoreCase = true) }
    }

    val targetElements = trailerElements.flatMap { element ->
        val target = listOf("data-target", "data-content", "data-tab-content", "href")
            .asSequence()
            .map { element.attr(it).trim().removePrefix("#") }
            .firstOrNull { it.isNotBlank() }

        listOfNotNull(
            element,
            target?.let { document.getElementById(it) },
            target?.let { value ->
                document.select("[data-tab-content=\"$value\"], [data-content=\"$value\"]").firstOrNull()
            },
        )
    }

    fun extractUrl(element: Element): String? = sequence {
        yieldAll(element.select("iframe[src], iframe[data-src]").map {
            it.attr("src").ifBlank { it.attr("data-src") }
        })
        yieldAll(element.select("a[href]").map { it.attr("href") })
    }
        .mapNotNull(::normalizeUakinoTrailerUrl)
        .toList()
        .let { urls -> urls.firstOrNull(::isUakinoYoutubeUrl) ?: urls.firstOrNull() }

    targetElements.asSequence().mapNotNull(::extractUrl).firstOrNull()?.let { return it }

    // На частині сторінок вкладка не має id/data-target, але підпис присутній.
    // У такому разі безпечним fallback є лише YouTube iframe, а не перший iframe.
    if (document.text().contains("Трейлер", ignoreCase = true)) {
        return document.select("iframe[src], iframe[data-src], a[href]")
            .asSequence()
            .map { element ->
                if (element.tagName() == "a") element.attr("href")
                else element.attr("src").ifBlank { element.attr("data-src") }
            }
            .mapNotNull(::normalizeUakinoTrailerUrl)
            .firstOrNull(::isUakinoYoutubeUrl)
    }

    return null
}

private fun normalizeUakinoTrailerUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank() || value.equals("null", ignoreCase = true)) return null
    if (value.startsWith("#") || value.startsWith("javascript:", ignoreCase = true)) return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
        else -> null
    }
}

private fun isUakinoYoutubeUrl(url: String): Boolean =
    url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)
