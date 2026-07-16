package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home =
            document
                .select("div.owl-item, div.movie-item")
                .filterNot { el ->
                    el.select("a.movie-title, a.full-movie").attr("href").contains(Regex(blackUrls))
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
        val document = app.get(this.attr("href")).document
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
                )
            )
                .document

        return document
            .select("div.movie-item.short-item")
            .filterNot { el ->
                el.select("a.movie-title, a.full-movie").attr("href").contains(Regex(blackUrls))
            }
            .map { it.toSearchResponse() }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Parse info
        val title = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val engTitle = document.selectFirst("h1 span.solototle")?.text()?.trim().toString()
        val poster = fixUrl(document.selectFirst("div.film-poster img")?.attr("src").toString())

        var tags = emptyList<String>()
        var year = 2023
        var actors = emptyList<String>()
        var rating = "0"

        document.select(".fi-item-s, .fi-item").forEach { metadata ->
            with(metadata.select(".fi-label").text()) {
                when {
                    contains("Рік виходу:") -> year = metadata.select(".fi-desc").text().toInt()
                    contains("Жанр:") -> tags = metadata.select(".fi-desc").text().split(" , ")
                    contains("Актори:") -> actors = metadata.select(".fi-desc").text().split(", ")
                    contains("") -> {
                        if (!metadata.select(".fi-label").select("img").isEmpty()){
                            rating = metadata.select(".fi-desc").text().substringBefore("/")
                        }
                    }
                }
                // Log.d("CakesTwix-Debug", metadata.select(".fi-desc").text().substringBefore("/"))
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
        val trailer = document.selectFirst("iframe#pre")?.attr("data-src")

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
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
                )
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
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                addEpisodes(DubStatus.None, episodes.distinctBy { it.name })
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
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
        val dataList = data.split(",")

        // 1. Визначаємо URL для запиту та назву епізоду (якщо є)
        val (requestUrl, targetEpisode) = if (dataList.size == 1) {
            val id = data.split("/").last().split("-").first()
            "$mainUrl/engine/ajax/playlists.php?news_id=$id&xfield=playlist&time=${Date().time}" to null
        } else {
            dataList[0] to dataList[1]
        }

        // 2. Робимо запит до API
        val responseGet = app.get(requestUrl, headers = mapOf(
            "Referer" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
        )).parsedSafe<Responses>()

        if (responseGet?.success == true && responseGet.response != null) {
            // Логіка для серіалів
            val document = Jsoup.parse(responseGet.response!!)
            val selector = if (targetEpisode != null) {
                "div.playlists-videos li:contains($targetEpisode)"
            } else {
                "div.playlists-videos li"
            }

            document.select(selector).forEach { eps ->
                // Якщо шукаємо конкретну серію, перевіряємо точний збіг тексту
                if (targetEpisode != null && eps.text() != targetEpisode) return@forEach

                var href = eps.attr("data-file")
                if (href.isNotEmpty() && !href.contains("https://")) {
                    href = "https:$href"
                }
                val dub = eps.attr("data-voice")

                extractPlayerJs(href, dub, callback, subtitleCallback)
            }
        } else {
            // Логіка для фільмів (або якщо AJAX не повернув успіх)
            val filmDoc = app.get(dataList[0]).document
            val iframeUrl = filmDoc.selectFirst("iframe#pre")?.attr("src")

            if (iframeUrl != null) {
                val title = filmDoc.selectFirst("h1 span.solototle")?.text()?.trim() ?: "Movie"
                extractPlayerJs(iframeUrl, title, callback, subtitleCallback)
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
        // Збираємо вміст усіх <script> (ashdi/tortuga більше не мають "var player = new Playerjs({")
        val scriptData = fetchBypass(url, referer = "$mainUrl/")
            .select("script").joinToString("\n") { it.data() }

        // Беремо .m3u8 (а якщо такого немає — перший file:)
        val m3uLink = fileRegex.findAll(scriptData).map { it.groupValues[1] }
            .firstOrNull { it.contains(".m3u8") }
            ?: fileRegex.find(scriptData)?.groups?.get(1)?.value ?: ""

        if (m3uLink.isNotEmpty()) {
            val playerReferer = if (url.contains("tortuga")) "https://tortuga.wtf/" else "https://ashdi.vip/"
            val streams = M3u8Helper.generateM3u8(
                source = sourceName,
                streamUrl = videoProxy?.wrap(m3uLink) ?: m3uLink,
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
