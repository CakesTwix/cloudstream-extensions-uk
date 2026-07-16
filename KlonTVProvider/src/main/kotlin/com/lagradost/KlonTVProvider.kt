package com.lagradost

import com.lagradost.models.PlayerJson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.GeneralInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KlonTVProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://klon.fun"
    override var name = "KlonTV"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie,
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/filmy/page/" to "Фільми",
        "$mainUrl/serialy/page/" to "Серіали",
        "$mainUrl/multfilmy/page/" to "Мультфільми",
        "$mainUrl/multserialy/page/" to "Мультсеріали",
        "$mainUrl/anime/page/" to "Аніме",
    )

    // Main Page
    private val animeSelector = ".short-news__slide-item"
    private val titleSelector = ".card-link__style, .text-module__main"
    private val hrefSelector = titleSelector
    private val posterSelector = ".card-poster__img, .cover-image, .owl-carousel .owl-item img"

    // Load info
    private val titleLoadSelector = ".seo-h1__position"
    private val genresSelector = ".table-info__link"
    private val yearSelector = ".table-info__link a"
    private val playerSelector = "div.film-player iframe"
    private val descriptionSelector = ".info-clamp__hid"
    private val recommendationsSelector = ".related-news__small-card"
    // private val ratingSelector = ".pmovie__subrating img"

    val fileRegex = "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()
    val subtitleRegex = "subtitle\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()

    private val bypassClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private inner class VideoProxy(private val client: OkHttpClient) {
        private var server: ServerSocket? = null
        private val URI_RE = Regex("""URI="([^"]+)"""")

        fun start() {
            if (server != null) return
            server = ServerSocket(0)
            Thread {
                while (server?.isClosed == false) {
                    try {
                        val socket = server!!.accept()
                        Thread { handle(socket) }.also { it.isDaemon = true }.start()
                    } catch (_: Exception) {}
                }
            }.also { it.isDaemon = true }.start()
        }

        private fun handle(socket: java.net.Socket) {
            try {
                val line = socket.inputStream.bufferedReader().readLine() ?: return
                val path = line.substringAfter(" ").substringBefore(" ")
                val encoded = path.removePrefix("/?url=")
                val target = URLDecoder.decode(encoded, "UTF-8")

                val req = Request.Builder().url(target)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0")
                    .build()

                val resp = client.newCall(req).execute()
                val ct = resp.header("Content-Type") ?: "application/octet-stream"
                val isM3u8 = ct.contains("mpegurl", true) || target.endsWith(".m3u8")

                val os = socket.outputStream
                os.write("HTTP/1.1 200 OK\r\nContent-Type: $ct\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())

                if (isM3u8) {
                    val body = resp.body?.string() ?: ""
                    os.write(rewriteM3u8(body, target))
                } else {
                    resp.body?.byteStream()?.copyTo(os)
                }

                os.flush()
                resp.close()
                socket.close()
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        private fun rewriteM3u8(content: String, baseUrl: String): ByteArray {
            val base = URL(baseUrl)
            val p = server!!.localPort
            return content.lineSequence().joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    line.startsWith("#") -> URI_RE.replace(line) { m ->
                        val uri = m.groupValues[1]
                        val resolved = if (uri.startsWith("http")) uri else URL(base, uri).toString()
                        "URI=\"http://127.0.0.1:$p/?url=${URLEncoder.encode(resolved, "UTF-8")}\""
                    }
                    else -> {
                        val resolved = if (line.trim().startsWith("http")) line.trim() else URL(base, line.trim()).toString()
                        "http://127.0.0.1:$p/?url=${URLEncoder.encode(resolved, "UTF-8")}"
                    }
                }
            }.toByteArray(Charsets.UTF_8)
        }

        fun wrap(url: String): String {
            val p = server!!.localPort
            return "http://127.0.0.1:$p/?url=${URLEncoder.encode(url, "UTF-8")}"
        }
    }

    private val videoProxy by lazy {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P)
            VideoProxy(bypassClient).also { it.start() }
        else null
    }

    private suspend fun fetchBypass(url: String, referer: String? = null): Document {
        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url)
            referer?.let { requestBuilder.header("Referer", it) }
            requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0")
            val response = bypassClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP ${response.code}: $url")
            }
            val html = response.body?.string() ?: ""
            response.close()
            Jsoup.parse(html)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select(animeSelector).map {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse {
        val title = this.selectFirst(titleSelector)?.text()?.trim().toString()
        val href = this.selectFirst(hrefSelector)?.attr("href").toString()
        val posterUrl = mainUrl + this.selectFirst(posterSelector)?.attr("data-src")
        val status = this.select(".poster__label").text()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub = true)
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            url = mainUrl,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query.replace(" ", "+")
            )
        ).document

        return document.select(animeSelector).map {
            it.toSearchResponse()
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // Parse info
        val titleJson = tryParseJson<GeneralInfo>(
            document.selectFirst("script[type=application/ld+json]")?.html()
        )!!

        // JSON
        val title = titleJson.name
        val poster = titleJson.image
        val rating = titleJson.aggregateRating?.ratingValue.toString()
        val actors = titleJson.actor.map { it.name }

        // HTML
        val tags = document.select(genresSelector).map { it.text() }
        val year = document.selectFirst(yearSelector)?.text()?.toIntOrNull()
        val playerUrl = document.select(playerSelector).attr("data-src")

        var tvType = with(tags) {
            when {
                contains("Серіали") -> TvType.TvSeries
                contains("Фільми") -> TvType.Movie
                contains("Аніме") -> TvType.Anime
                contains("Мультфільми") -> TvType.Movie
                contains("Мультсеріали") -> TvType.TvSeries
                else -> TvType.TvSeries
            }
        }

        // https://klon.fun/filmy/1783-rik-ta-morti.html not movie
        if (playerUrl.contains("/serial/")) {
            tvType = TvType.TvSeries
        }
        val description = Jsoup.parse(titleJson.description).text()

        val recommendations = document.select(recommendationsSelector).map {
            it.toSearchResponse()
        }

        // Return to app
        // Parse Episodes as Series
        return if (tvType != TvType.Movie) {
            val episodes = mutableListOf<Episode>()
            val playerRawJson = fileRegex.find(fetchBypass(playerUrl).select("script").html())?.groupValues?.get(1) ?: ""

            tryParseJson<List<PlayerJson>>(playerRawJson)?.map { dubs -> // Dubs
                for (season in dubs.folder) {                              // Seasons
                    for (episode in season.folder) {                       // Episodes
                        episodes.add(
                            newEpisode("${season.title}|${episode.title}|$playerUrl") {
                                this.name = episode.title
                                this.season = season.title.replace(" Сезон ","").toIntOrNull()
                                this.episode = episode.title.replace("Серія ","").toIntOrNull()
                                this.posterUrl = episode.poster
                                this.data = "${season.title}|${episode.title}|$playerUrl"
                            }
                        )
                    }
                }
            }
            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addEpisodes(DubStatus.Dubbed, episodes)
                addActors(actors)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, tvType, "${title.replace("|", "")}|$playerUrl") {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serisl) [Season, Episode, Player Url] | (Film) [Title, Player Url]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split("|")
        // Its film, parse one m3u8
        if (dataList.size == 2) {
            // TODO: Remove this hack
            val m3u8Url = fileRegex.find(fetchBypass(dataList[1].replace("?multivoice", "")).select("script[type=text/javascript]").html())?.groups?.get(1)?.value.toString()

            M3u8Helper.generateM3u8(
                source = dataList[0],
                streamUrl = videoProxy?.wrap(m3u8Url) ?: m3u8Url,
                referer = "https://tortuga.wtf/"
            ).dropLast(1).forEach(callback)

            val subtitleUrl = subtitleRegex.find(fetchBypass(dataList[1]).select("script").html())?.groupValues?.get(1) ?: ""

            if (subtitleUrl.isBlank()) return true
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitleUrl.substringAfterLast("[").substringBefore("]"),
                    subtitleUrl.substringAfter("]")
                )
            )
            return true
        }

        val playerRawJson = fileRegex.find(fetchBypass(dataList[2]).select("script[type=text/javascript]").html())?.groups?.get(1)?.value.toString()

        tryParseJson<List<PlayerJson>>(playerRawJson)?.forEach { dub ->
            dub.folder.filter { it.title == dataList[0] }
                .flatMap { it.folder }
                .filter { it.title == dataList[1] }
                .map {
                    M3u8Helper.generateM3u8(
                        source = dub.title,
                        streamUrl = videoProxy?.wrap(it.file) ?: it.file,
                        referer = "https://tortuga.wtf/"
                    ).dropLast(1).forEach(callback)

                    if (!it.subtitle.isNullOrBlank()) {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                it.subtitle.substringAfterLast("[").substringBefore("]"),
                                it.subtitle.substringAfter("]")
                            )
                        )
                    }
                }
        }
        return true
    }

}
