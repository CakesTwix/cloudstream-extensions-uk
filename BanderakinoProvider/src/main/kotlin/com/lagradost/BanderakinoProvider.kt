package com.lagradost

import android.util.Log
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.model.PlayerJson
import com.lagradost.model.SchemaTvSeason
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class BanderakinoProvider : MainAPI() {

    private val episodeRequestUrl = "https://banderakino.online/episodes?season_id="
    private val movieSelector = "div.movies-grid"
    private val titleSelector = "div.movie-info > h3 > a"
    private val videoUrlSelector = "a"
    private val posterUrlSelector = "div.poster_wraper img"
    private val mapper = JsonMapper.builder()
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .addModule(KotlinModule.Builder().build()).build()

    // Basic Info
    override var mainUrl = "https://banderakino.online"
    override var name = "Banderakino"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
    )

    // Sections
    override val mainPage = mainPageOf(
//        "$mainUrl/" to "Новинки",
        "$mainUrl/serialy?page=" to "Серіали",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
//        d("getMainPage", "page:$page request:${request.data}")
        val url = request.data + page
//        d("url", url)
        val document = app.get(url).document
        val first = document.select(movieSelector)
            .firstOrNull()

//        Log.d("getMainPage", "first:$first")
        return if (first == null) {
            newHomePageResponse(request.name, emptyList())
        } else {
            val mainPage = first.children().map {
                it.toSearchResponse()
            }.filter { !it.posterUrl.isNullOrEmpty() }
//            d("getMainPage", "mainPage:$mainPage")
            newHomePageResponse(request.name, mainPage)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
//        d("search", "query:$query")
        val url = "$mainUrl/search?q=$query"
//        d("DEBUG search", "url:$url")
        return app.get(url).document.select(movieSelector).map {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
//        d("DEBUG load", "url:$url")
        val document = app.get(url).document
//        Log.d("DEBUG load", "document:$document")

        // Collect all seasons at the start.
        // `null` means there is no selector, so reuse the already-loaded document.
        val seasons: List<Pair<Int, String?>> = document
            .select(".season-selector a.season-btn[href]")
            .mapNotNull { element ->
                val seasonNumber = """\d+""".toRegex()
                    .find(element.text())
                    ?.value
                    ?.toIntOrNull()

                seasonNumber?.let {
                    it to fixUrl(element.attr("href"))
                }
            }
            .distinctBy { it.second }
            .ifEmpty {
                listOf(1 to null)
            }

//        d("DEBUG load", "seasons:$seasons")

        val parseSchemaJson = parseSchemaJson(document)
        val title = getPageTitle(document)
        val engTitle = getPageEngTitle(document)
        val posterUrl = getPagePosterUrl(document)
        val year = getYear(document)
        val description = parseSchemaJson?.description
        val tags = parseSchemaJson?.genre
        val actors = parseSchemaJson?.actor?.mapNotNull { it.name }
        val dubName = getDubName(document)
        val ratingValue = getRating(document)
        val episodes = mutableListOf<Episode>()

        for ((seasonNumber, seasonUrl) in seasons) {
            // If no .season-selector exists, use the original document directly.
            val seasonDocument = seasonUrl?.let {
//                d("Debug load", "it: $it")
                app.get(it).document
            } ?: document

            episodes += getSeasonEpisodes(
                seasonDocument = seasonDocument,
                parentUrl = url,
                seasonNumber = seasonNumber,
                dubName = dubName
            )
        }

//        d("load", "seasons: ${seasons.map { it.first }}")
//        d("load", "episodes: $episodes")


        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.engName = engTitle
            this.year = year
            this.plot = description
            this.tags = tags
            this.contentRating = ratingValue
            addActors(actors)
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
//        d("DEBUG loadLinks", "Data: $data")

        val parts = data.split(",", limit = 4)

        val episodeUrl = parts.getOrNull(0).orEmpty()
        val savedM3uUrl = parts.getOrNull(1).orEmpty()
        val subtitleUrl = parts.getOrNull(2).orEmpty()
        val dubName = parts.getOrNull(3).orEmpty()

        // Use M3U URL saved during load().
        // If absent, open the episode page and extract it now.
        val streamUrl = savedM3uUrl.takeIf { it.isNotBlank() }
            ?: episodeUrl.takeIf { it.isNotBlank() }?.let { getM3url(it) }

        if (streamUrl.isNullOrBlank()) {
            //Log.d("DEBUG loadLinks", "Could not get M3U URL for: $episodeUrl")
            return false
        }

        if (subtitleUrl.isNotBlank()) {
            subtitleCallback(
                SubtitleFile(
                    lang = "Українська",
                    url = subtitleUrl
                )
            )
        }


        M3u8Helper.generateM3u8(
            source = dubName,
            streamUrl = streamUrl,
            referer = mainUrl
        ).dropLast(1).forEach(callback)

        return true
    }

    private fun getRating(document: Document): String {
        val imdbIcon = document.selectFirst(".icon--mask--imdb")

// Safely navigate to the parent and get its text content
        val imdbRating = imdbIcon?.parent()?.text()?.trim()
        Log.d("DEBUG IMDb Rating", "Rating: $imdbRating")
        return imdbRating.orEmpty()
    }


    private suspend fun getSeasonEpisodes(
        seasonDocument: Document,
        parentUrl: String,
        seasonNumber: Int,
        dubName: String,
    ): List<Episode> = coroutineScope {
        val seasonId = seasonDocument
            .selectFirst("div[data-season-id]")
            ?.attr("data-season-id")
            ?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope emptyList()

        val rawResponseText = app.get("$episodeRequestUrl$seasonId").text
        val seriesUrlsTitles = getSeriesUrlsTitles(rawResponseText)

        // Four concurrent episode-page requests at most.
        val requestLimiter = Semaphore(4)

        seriesUrlsTitles.mapIndexed { index, (episodeTitle, episodeUrl) ->
            async {
                requestLimiter.withPermit {
                    val rawText = app.get(episodeUrl).text
                    val playerJS = getPlayerJS(rawText)

                    val episodeM3uUrl = playerJS.file.orEmpty()
                    val posterUrlValue = playerJS.poster
                    val subtitle = playerJS.subtitle.orEmpty()

                    newEpisode(parentUrl) {
                        posterUrl = posterUrlValue
                        name = episodeTitle.ifBlank { "Серія ${index + 1}" }
                        season = seasonNumber
                        episode = index + 1
                        data = "$episodeUrl,$episodeM3uUrl,$subtitle,$dubName"
                    }
                }
            }
        }.awaitAll()
    }

    private fun getPlayerJS(rawResponseText: String): PlayerJson {
        val getJsonData =
            "{" + rawResponseText.substringAfterLast("player = new Playerjs({")
                .substringBefore(");")
        val readValue = mapper.readValue<PlayerJson>(getJsonData)
        return readValue
    }


    private fun getDubName(document: Document): String {
        val liElement = document.selectFirst("li:contains(Озвучення:)")

        return if (liElement != null) {
            // 2. Remove the internal <span> tag completely from the element tree
            liElement.select("span").remove()

            // 3. Get the cleaned text and trim any remaining whitespaces
            val dubbingText = liElement.text().trim()

//            println("Extracted text: $dubbingText") // Output: Українськи
            dubbingText
        } else {
            println("Element not found")
            ""
        }
    }

    suspend fun getM3url(episodeUrl: String): String {
        val rawText = app.get(episodeUrl).text

        val m3uIndex = rawText.indexOf(".m3u")

        val start = rawText.lastIndexOf('\'', m3uIndex)
        val end = rawText.indexOf('\'', m3uIndex)

        return rawText.substring(start + 1, end)
    }


    fun getSeriesUrlsTitles(rawText: String): List<Pair<String, String>> {
        // 1. Matches \u0421\u0435\u0440\u0456\u044f (Серія) followed by digits
        val textRegex = """\\u0421\\u0435\\u0440\\u0456\\u044f\s*(\d+)""".toRegex()
        val dataSourceRegex = Regex("""data-source=\\?"(.*?)\\?"""")

        // Extract and sanitize URLs
        val urls = dataSourceRegex.findAll(rawText)
            .map { it.groupValues[1].replace("\\/", "/") }
            .map { url ->
                when {
                    // If it already starts with http/https, leave it or fix double schemes
                    url.startsWith("https:https:") -> url.substringAfter("https:")
                    url.startsWith("http:http:") -> url.substringAfter("http:")
                    url.startsWith("https:") || url.startsWith("http:") -> url
                    // If it's a protocol-relative URL (e.g., //ashdi.vip/...)
                    url.startsWith("//") -> "https:$url"
                    // Fallback for everything else
                    else -> "https://$url".replace("https:///", "https://")
                }
            }
            .toList()

        // Extract titles
        val titles = textRegex.findAll(rawText)
            .map { "Серія ${it.groupValues[1]}" }
            .toList()

        // Safety check: Avoid crashes if lists are uneven due to bad server HTML
        if (titles.size != urls.size) {
            Log.w(
                "CloudStreamDebug",
                "Mismatch detected! Titles: ${titles.size}, URLs: ${urls.size}"
            )
        }

        return titles.zip(urls)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse {
//        //Log.d("toSearchResponse", "$this")
        val title = this.select(titleSelector).text()
        val url = this.select(videoUrlSelector).attr("href")
        val attr = this.select(posterUrlSelector).attr("data-src")
        val posterUrl = if (attr.isNotEmpty()) {
            mainUrl + attr
        } else {
            ""
        }

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
//            //Log.d("newMovieSearchResponse","posterUrl:$posterUrl")
        }
    }


    private fun parseSchemaJson(document: Document): SchemaTvSeason? {
        return try {
            // Target the specific script tag inside the #body container
            val jsonText =
                document.selectFirst("div#body script[type=application/ld+json]")?.html()?.trim()

            if (!jsonText.isNullOrEmpty()) {
                // Parse the string into your Kotlin data class structure using Cloudstream's native parser
                parseJson<SchemaTvSeason>(jsonText)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    private fun getYear(document: Document): Int {
        return document.selectFirst("div.inline-list.only_desktop ul li")?.text()?.trim()?.toInt()
            ?: 0
    }

    private fun getPagePosterUrl(document: Document) =
        fixUrl(document.selectFirst("link[as=image][rel=preload]")?.attr("href") ?: "")

    private fun getPageEngTitle(document: Document) =
        document.selectFirst("span.name__original.only_desktop")?.text()?.trim()

    private fun getPageTitle(document: Document) = document.select("title").text()

}