package com.lagradost

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.AESPlayerDecodedModel
import com.lagradost.models.DecodedJSON
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class UASerialsProProvider : MainAPI() {

    // Basic Info
    override var mainUrl = "https://uaserials.com"
    override var name = "UASerialsPro"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie,
        TvType.Anime
    )

    // Sections
    override val mainPage = mainPageOf(
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/cartoons/page/" to "Мультсеріали",
        "$mainUrl/fcartoon/page/" to "Мультфільми",
        "$mainUrl/anime/page/" to "Аніме",
        "$mainUrl/films/page/" to "Фільми"
    )

    // Main Page
    private val animeSelector = ".short-item"
    private val titleSelector = "div.th-title.truncate"
    private val engTitleSelector = "div.th-title-oname.truncate"
    private val hrefSelector = ".short-item.width-16 .short-img"
    private val posterSelector = ".img-fit img"

    // Load info
    // private val titleLoadSelector = ".page__subcol-main h1"
    // private val genresSelector = "li span:contains(Жанр:) a"
    private val yearSelector = "a[href*=https://uaserials.pro/year/]"
    // private val playerSelector = "iframe"
    private val descriptionSelector = ".full-text"
    private val ratingSelector = ".short-rate-in"

    private val listAESModel = object : TypeToken<List<AESPlayerDecodedModel>>() { }.type
    private val listDecodedJSONModel = object : TypeToken<List<DecodedJSON>>() { }.type

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
        val engTitle = this.selectFirst(engTitleSelector)?.text()?.trim().toString()
        val href = this.selectFirst(hrefSelector)?.attr("href").toString()
        val posterUrl = this.selectFirst(posterSelector)?.attr("data-src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.otherName = engTitle
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

        val title = document.select("span.oname_ua").text().trim().toString()
        val engTitle = document.select(".pmovie__original-title").text()
        val poster = document.selectFirst("div.fimg.img-wide img")?.attr("src")
        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()
        val year = document.select(yearSelector).text().substringAfter(": ").substringBefore("-").toIntOrNull()
        val rating = document.selectFirst(ratingSelector)!!.text()

        document.select(".short-list li").forEach { menu ->
            with(menu){
                when{
                    this.select("span").text() == "Жанр:" -> menu.select("a").map { tags.add(it.text()) }
                    this.select("span").text() == "Актори:" -> menu.select("#text").text().split(", ").map { actors.add(it) }
                }
            }
        }

        val tvType = with(tags){
            when{
                contains("Серіал") -> TvType.TvSeries
                contains("Мультсеріал") -> TvType.Cartoon
                contains("Фільм") -> TvType.Movie
                contains("Мультфільм") -> TvType.Movie
                contains("Аніме") -> TvType.Anime
                else -> TvType.TvSeries
            }
        }
        val description = document.selectFirst(descriptionSelector)?.text()?.trim()
        // val rating = document.select(ratingSelector).next().text().toRatingInt()

        val recommendations = document.select(animeSelector).map {
            it.toSearchResponse()
        }

        val decryptData = cryptojsAESHandler(
            Gson().fromJson(
                document.select("div.fplayer player-control").attr("data-tag1"),
                AesData::class.java,
            ),
            "297796CCB81D2551",
            false
        ).replace("\\", "").substringBeforeLast("]") + "]"

        val seriesJson = Gson().fromJson<List<DecodedJSON>>(decryptData, listDecodedJSONModel)
        val movieJson = Gson().fromJson<List<AESPlayerDecodedModel>>(decryptData, listAESModel)

        // Return to app
        // Parse Episodes as Series
        return if (seriesJson[0].seasons != null) {
            val episodes = mutableListOf<Episode>()

            seriesJson[0].seasons.forEachIndexed { seasonsIndex, season ->
                    season.episodes.forEachIndexed { episodesIndex, episode ->
                        episodes.add(
                            newEpisode("$seasonsIndex, $episodesIndex, $url") {
                                this.name = episode.title
                                this.season = seasonsIndex + 1
                                this.episode = episodesIndex + 1
                                this.data = "$seasonsIndex, $episodesIndex, $url"
                            }
                        )
                    }
                }

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.engName = engTitle
                this.score = Score.from10(rating)
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        } else { // Parse as Movie.
            newMovieLoadResponse(title, url, TvType.Movie, "$title, ${movieJson[0].url}") {
                this.posterUrl = poster
                this.name = engTitle
                this.score = Score.from10(rating)
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // It works when I click to view the series
    override suspend fun loadLinks(
        data: String, // (Serial) [Season Index, Episode Name, url] | (Film) [Title, Player Url]
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        // Movie
        if(dataList.size == 2){
            val html = app.get(dataList[1]).document.select("script").html()
            val m3u8Url = when {
                "file: \"" in html -> html.substringAfter("file: \"").substringBefore("\"")
                "file: '" in html -> html.substringAfter("file: '").substringBefore("'")
                "file:\"" in html -> html.substringAfter("file:\"").substringBefore("\"")
                "file:'" in html -> html.substringAfter("file:'").substringBefore("'")
                else -> return false
            }

            M3u8Helper.generateM3u8(
                source = dataList[0],
                streamUrl = Decoder.decodeAndReverse(m3u8Url)!!.replace("https://", "http://"),
                referer = "https://tortuga.wtf/",
            ).dropLast(1).forEach(callback)
            return true
        }

        // Serials
        val document = app.get(dataList[2]).document
        val decryptData = cryptojsAESHandler(
            Gson().fromJson(
                document.select("div.fplayer player-control").attr("data-tag1"),
                AesData::class.java,
            ),
            "297796CCB81D2551",
            false
        ).replace("\\", "").substringBeforeLast("]") + "]"

        Gson().fromJson<List<DecodedJSON>>(decryptData, listDecodedJSONModel)[0]
            .seasons[dataList[0].toInt()].episodes[dataList[1].toInt()].sounds.forEach { episode ->
            val m3u8Url = app.get(episode.url).document.select("script").html()
                .let {
                    val regex = """file:\s*"(.*?)"""".toRegex()
                    val matchResult = regex.find(it)
                    matchResult?.groups?.get(1)?.value.toString()
                }

                M3u8Helper.generateM3u8(
                    source = episode.title,
                    streamUrl = Decoder.decodeAndReverse(m3u8Url)!!.replace("https://", "http://"),
                    referer = "https://tortuga.wtf/"
                ).dropLast(1).forEach(callback)
            }
        return true
    }

    // THANKS to Hexated!
    // https://github.com/hexated/cloudstream-extensions-hexated/blob/master/OnetwothreeTv/src/main/kotlin/com/hexated/OnetwothreeTv.kt
    private fun cryptojsAESHandler(
        data: AesData,
        pass: String,
        encrypt: Boolean = true
    ): String {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(pass.toCharArray(), data.s.decodeHex(), 999, 256)
        val key = factory.generateSecret(spec)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        return if (!encrypt) {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                IvParameterSpec(data.iv.decodeHex())
            )
            String(cipher.doFinal(base64DecodeArray(data.ct)))
        } else {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                IvParameterSpec(data.iv.decodeHex())
            )
            base64Encode(cipher.doFinal(data.ct.toByteArray()))

        }
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    data class AesData(
        @SerializedName("ciphertext") val ct: String,
        @SerializedName("salt") val s: String,
        @SerializedName("iv") val iv: String,
        @SerializedName("iterations") val iterations: Int = 999,
    )

    object Decoder {

        /**
         * Декодує рядок із формату Base64.
         * @param encodedString Закодований рядок (Base64).
         * @return Декодований рядок (String) або null у разі помилки.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun decodeBase64(encodedString: String): String? {
            try {
                return String(Base64.decode(encodedString.replace("==", "")), Charsets.UTF_8)
            } catch (_: Exception) {
                return String(Base64.decode(encodedString.replace("===", "=")), Charsets.UTF_8)
            }

        }

        /**
         * Реверсує (перевертає) вхідний рядок.
         * @param inputString Рядок для реверсування.
         * @return Реверсований рядок.
         */
        fun reverseText(inputString: String): String {
            return inputString.reversed()
        }

        /**
         * Комбінована функція: спочатку декодує Base64, потім реверсує результат.
         * (Це зазвичай використовується, коли обфускація складається з двох етапів).
         * @param encodedString Закодований рядок.
         * @return Реверсований та декодований рядок або null.
         */
        fun decodeAndReverse(encodedString: String): String? {
            val decoded = decodeBase64(encodedString)
            return decoded?.let {
                reverseText(it)
            }
        }
    }
}