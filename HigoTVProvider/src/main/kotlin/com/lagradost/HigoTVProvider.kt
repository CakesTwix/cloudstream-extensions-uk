package com.lagradost

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.PlayerJson
import org.jsoup.nodes.Element


class HigoTVProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://higotv.fun"
    override var name = "HigoTV"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val searchUrl = "https://higotv.fun/search/doSearch"

    // Sections
    override val mainPage =
        mainPageOf(
            "vsevishlo" to "Виходить, Вийшло",
        )

    // Main Page
    private val animeSelector = "div.poster"
    private val titleSelector = ".greed-item__title_p"
    // private val engTitleSelector = "div.th-title-oname.truncate"
    private val hrefSelector = "a:contains(Перейти)"
    private val posterSelector = ".poster > img"

    // Load info
    // private val titleLoadSelector = ".page__subcol-main h1"
    // private val genresSelector = "li span:contains(Жанр:) a"
    // private val yearSelector = "a[href*=https://uaserials.pro/year/]"
    // private val playerSelector = "iframe"
    private val descriptionSelector = ".anim-txt-all"
    private val ratingSelector = ".rt-tb"

    private val listPlayer = object : TypeToken<List<PlayerJson>>() {}.type

    private val TAG = "$name-Debug"

    private val gson = GsonBuilder()
        .setLenient()
        .create()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document

        val home = document.select(animeSelector).map { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.selectFirst(titleSelector)!!.text().replace("Назва: ", "")
        // val engTitle = this.selectFirst(engTitleSelector)?.text()?.trim().toString()
        val href = this.selectFirst(hrefSelector)?.attr("href").toString()
        val posterUrl = fixUrl(this.select(posterSelector).attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            // this.otherName = engTitle
            this.posterUrl = posterUrl
            addDubStatus(isDub = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(url = searchUrl, data = mapOf("keyword" to query)).document

        return document.select(animeSelector).map { it.toSearchResponse() }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Parse info
        val title = document.select(".anime-op__txt").text()
        val engTitle = document.select(".anime-eng__txt").text()
        val poster = document.select(".main-poster").attr("src")
        val tags = document.select(".span-menu-ogg a").map { it.text() }

        val tvType =
            with(document.select(".tup-anim").text()) {
                when {
                    contains("TV SHORT") -> TvType.OVA
                    contains("OVA") -> TvType.OVA
                    contains("Фільм") -> TvType.Movie
                    else -> TvType.Anime
                }
            }
        val description = document.selectFirst(descriptionSelector)?.text()?.trim()
        val rating =
            extractIntFromString(document.select(ratingSelector).text()).toString().toRatingInt()

        val recommendations =
            document.select("div.owl-item").map {
                val title = it.select(".popular-item-title").text().trim()
                // val engTitle = this.selectFirst(engTitleSelector)?.text()?.trim().toString()
                val href = it.select("a.popular-item-img").attr("href").toString()
                val posterUrl = fixUrl(it.select(".img-fit img").attr("src"))

                newAnimeSearchResponse(title, href, tvType) {
                    this.otherName = engTitle
                    this.posterUrl = posterUrl
                    addDubStatus(isDub = true)
                }
            }

        // Parse episodes
        val episodes = mutableListOf<Episode>()

        document.select("iframe").forEach {
            if(it.attr("src").isNotEmpty()){
                val playerDocument = app.get(it.attr("src"),
                        headers = mapOf(
                                "Host" to "moonanime.art",
                                "Accept" to "*/*",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
                                "accept-language" to "en-US,en;q=0.5"
                        )).document
                val parsedJSON = gson.fromJson<List<PlayerJson>>(
                    playerDocument.select("script[type*=text/javascript]").html().substringAfter("file: '").substringBefore("',"), listPlayer
                )

                parsedJSON?.forEach {
                        episodes.add(
                            Episode(
                                "${url}, ${it.title}",
                                it.title,
                                episode = it.title!!.replace("Серія ", "").toIntOrNull(),
                                posterUrl = it.poster
                            )
                        )
                    }
                }
        }

        // Log.d("CakesTwix-Debug", playerRawJson)

        // Parse Episodes as Series
        return if (tvType != TvType.Movie) {

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.engName = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        } else { // Parse as Movie.

            newMovieLoadResponse(title, url, tvType, "${url}, 1 Серія") {
                this.posterUrl = poster
                this.name = engTitle
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serial) [url, episode name]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Log.d(TAG, "loadLinks: $data")
        val dataList = data.split(", ")

        val document = app.get(dataList[0]).document

        document.select("iframe").forEach {
            if(it.attr("src").isNotEmpty()){
                val playerDocument = app.get(it.attr("src"),
                        headers = mapOf(
                                "Host" to "moonanime.art",
                                "Accept" to "*/*",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
                                "accept-language" to "en-US,en;q=0.5"
                        )).document
                // Log.d("CakesTwix-Debug", playerDocument.select("script[type*=text/javascript]").html().substringAfter("file:'").substringBefore("',"))
                val parsedJSON = gson.fromJson<List<PlayerJson>>(
                    playerDocument.select("script[type*=text/javascript]").html().substringAfter("file: '").substringBefore("',"), listPlayer
                )

                parsedJSON?.forEach {
                    if(it.title != dataList[1]) return@forEach
                    M3u8Helper.generateM3u8(
                        source = it.title,
                        streamUrl = it.file!!,
                        referer = "https://moonanime.art"
                    ).forEach(callback)
                }
            }
        }

        return true
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }

        return value.value.toIntOrNull()
    }
}
