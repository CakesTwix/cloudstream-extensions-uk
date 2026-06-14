package com.lagradost

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
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.newSubtitleFile
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

    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()
    val subsRegex = "subtitle\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()
    val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0"

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
    private val genresSelector = ".short-list li:contains(Жанр) a"
    private val actorsSelector = ".short-list li:contains(Актори) a"
    private val yearSelector = ".short-list a[href*=/year/]"
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
        val encodedQuery = java.net.URLEncoder.encode(query, "utf-8").replace("+", "%20")
        val document = app.get("$mainUrl/search/$encodedQuery/").document

        return document.select(".uas-card").map {
            val title = it.selectFirst(".uas-card__title")?.text()?.trim().toString()
            val engTitle = it.selectFirst(".uas-card__orig")?.text()?.trim().toString()
            val href = it.attr("href")
            val rawPoster = it.selectFirst(".uas-card__img")?.attr("data-src")
            val posterUrl = if (rawPoster?.startsWith("/") == true) "$mainUrl$rawPoster" else rawPoster

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.otherName = engTitle
                this.posterUrl = posterUrl
                addDubStatus(isDub = true)
            }
        }
    }

    // Detailed information
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val title = document.select(".short-title").text()
        val engTitle = document.select(".oname").text()
        val poster = document.selectFirst("div.fimg.img-wide img")?.attr("src")
        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()
        val year = document.selectFirst(yearSelector)?.text()?.toIntOrNull()
        val rating = document.selectFirst(ratingSelector)!!.text()

        document.select(genresSelector).forEach { tags.add(it.text()) }
        document.select(actorsSelector).forEach { actors.add(it.text()) }

        val tvType = with(tags) {
            when {
                contains("Серіал") -> TvType.TvSeries
                contains("Мультсеріал") -> TvType.Cartoon
                contains("Фільм") -> TvType.Movie
                contains("Мультфільм") -> TvType.Movie
                contains("Аніме") -> TvType.Anime
                else -> TvType.TvSeries
            }
        }
        val description = document.selectFirst(descriptionSelector)?.text()?.trim()

        val recommendations = document.select(animeSelector).map {
            it.toSearchResponse()
        }

        val decryptData = cryptojsAESHandler(
            Gson().fromJson(
                document.select("div.fplayer player-control").attr("data-tag1"),
                AesData::class.java,
            ),
            "297796CCB81D25512",
            false
        ).replace("\\", "")
        val lastBracket = decryptData.lastIndexOf("]")
        val cleanJson = if (lastBracket != -1) decryptData.substring(0, lastBracket + 1) else decryptData

        val playerTabs = Gson().fromJson<List<AESPlayerDecodedModel>>(cleanJson, listAESModel)
        val playerUrl = playerTabs.firstOrNull { it.tabName == "Плеєр" }?.url ?: playerTabs.firstOrNull()?.url

        val playerHtml = if (playerUrl != null) {
            app.get(playerUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).text
        } else {
            ""
        }

        val encodedFile = fileRegex.find(playerHtml)?.groups?.get(1)?.value ?: ""
        val decodedFile = if (encodedFile.isNotBlank()) {
            if (encodedFile.startsWith("http")) encodedFile
            else Decoder.tortugaDecode(encodedFile) ?: ""
        } else {
            ""
        }

        val subRaw = subsRegex.find(playerHtml)?.groups?.get(1)?.value ?: ""
        val subtitleUrl = when {
            subRaw.startsWith("[") -> subRaw
            subRaw.isNotBlank() -> Decoder.tortugaDecode(subRaw) ?: ""
            else -> ""
        }

        val listTortugaSeasonModel = object : TypeToken<List<TortugaSeason>>() { }.type
        val tortugaSeasons = try {
            if (decodedFile.startsWith("[")) {
                Gson().fromJson<List<TortugaSeason>>(decodedFile, listTortugaSeasonModel)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        return if (tortugaSeasons != null) {
            val episodes = mutableListOf<Episode>()

            tortugaSeasons.forEachIndexed { seasonsIndex, season ->
                val seasonNum = season.season.toIntOrNull() ?: (seasonsIndex + 1)
                season.folder.forEachIndexed { episodesIndex, episode ->
                    val epNum = episode.number.toIntOrNull() ?: (episodesIndex + 1)
                    val episodeData = "${episode.file}|${episode.subtitle ?: ""}"
                    episodes.add(
                        newEpisode(episodeData) {
                            this.name = episode.title
                            this.season = seasonNum
                            this.episode = epNum
                            this.data = episodeData
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
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "${title.replace("|", "")}|${playerTabs[0].url}") {
                this.posterUrl = poster
                this.score = Score.from10(rating)
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split("|")

        // Movie
        if (dataList.size == 2 && !dataList[0].startsWith("http") && !dataList[0].startsWith("{")) {
            val html = app.get(
                dataList[1],
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            ).text

            val m3u8Raw = fileRegex.find(html)?.groups?.get(1)?.value ?: ""
            val m3u8Url = when {
                m3u8Raw.startsWith("http") -> m3u8Raw
                m3u8Raw.isNotBlank() -> Decoder.tortugaDecode(m3u8Raw) ?: ""
                else -> ""
            }

            if (m3u8Url.isBlank()) return false

            M3u8Helper.generateM3u8(
                source = dataList[0],
                streamUrl = m3u8Url,
                referer = "https://tortuga.tw/",
            ).dropLast(1).forEach(callback)

            val subRaw = subsRegex.find(html)?.groups?.get(1)?.value ?: ""
            val subtitleUrl = when {
                subRaw.startsWith("[") -> subRaw
                subRaw.isNotBlank() -> Decoder.tortugaDecode(subRaw) ?: ""
                else -> ""
            }

            if (subtitleUrl.isBlank()) return true

            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitleUrl.substringAfterLast("[").substringBefore("]"),
                    subtitleUrl.substringAfter("]")
                )
            )

            return true
        }

        // Serial Episode
        val fileStr = dataList[0]
        val subtitleStr = if (dataList.size > 1) dataList[1] else ""

        if (fileStr.isBlank()) return false

        fileStr.split(";").forEach { track ->
            if (track.isNotBlank()) {
                val subTailMatch = "\\(subtitle:(.*)\\)$".toRegex().find(track)
                val cleanTrack = if (subTailMatch != null) {
                    track.substring(0, subTailMatch.range.first)
                } else {
                    track
                }

                val m3u8Url = cleanTrack.substringAfter("}")
                val name = if (cleanTrack.contains("{")) {
                    cleanTrack.substringAfter("{").substringBefore("}")
                } else {
                    "Плеєр"
                }

                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = "https://tortuga.tw/",
                ).dropLast(1).forEach(callback)

                subTailMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { sub ->
                    if (sub.isNotBlank()) {
                        val subName = sub.substringAfter("[").substringBefore("]")
                        val subUrl = sub.substringAfter("]")
                        subtitleCallback.invoke(
                            newSubtitleFile(subName, subUrl)
                        )
                    }
                }
            }
        }

        if (subtitleStr.isNotBlank()) {
            subtitleStr.split(",").forEach { sub ->
                if (sub.isNotBlank()) {
                    val subName = sub.substringAfter("[").substringBefore("]")
                    val subUrl = sub.substringAfter("]")
                    subtitleCallback.invoke(
                        newSubtitleFile(subName, subUrl)
                    )
                }
            }
        }

        return true
    }

    // THANKS to Hexated!
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

    data class TortugaSeason(
        @SerializedName("title") val title: String,
        @SerializedName("number") val number: String,
        @SerializedName("season") val season: String,
        @SerializedName("folder") val folder: List<TortugaEpisode>
    )

    data class TortugaEpisode(
        @SerializedName("id") val id: String,
        @SerializedName("title") val title: String,
        @SerializedName("number") val number: String,
        @SerializedName("file") val file: String,
        @SerializedName("poster") val poster: String? = null,
        @SerializedName("subtitle") val subtitle: String? = null
    )

    object Decoder {

        // Tortuga player XOR декодер (tor.core.min.js #w + #_ функції)
        // Алгоритм: перший байт = сіль, далі XOR з (salt + 7*i + 13) % 256
        fun tortugaDecode(encoded: String): String? {
    if (encoded.isBlank()) return encoded
    return try {
        val clean = encoded.trimEnd('=')
        val decoded = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        if (decoded.isEmpty()) return encoded

        val salt = decoded[0].toInt() and 0xFF
        
        val resultBytes = ByteArray(decoded.size - 1)

        for (i in 1 until decoded.size) {
            val key = (salt + 7 * (i - 1) + 13) % 256
            resultBytes[i - 1] = ((decoded[i].toInt() and 0xFF) xor key).toByte()
        }

        String(resultBytes, Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
        }

        fun torDecrypt(encoded: String): String {
            if (encoded.isEmpty()) return ""
            return try {
                val cleaned = encoded.replace(Regex("[^A-Za-z0-9+/]"), "")
                val pad = cleaned.length % 4
                val cleanEncoded = cleaned + if (pad > 1) "=".repeat(4 - pad) else ""

                val decoded = android.util.Base64.decode(cleanEncoded, android.util.Base64.DEFAULT)
                if (decoded.size < 2) return ""

                val saltChar = decoded[0].toInt() and 0xFF
                val decryptedBytes = ByteArray(decoded.size - 1)

                for (i in 1 until decoded.size) {
                    val f = (saltChar + 7 * (i - 1) + 13) % 256
                    decryptedBytes[i - 1] = (decoded[i].toInt() xor f).toByte()
                }

                String(decryptedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun decodeBase64(encodedString: String): String {
            return try {
                String(kotlin.io.encoding.Base64.decode(encodedString.replace("==", "")), Charsets.UTF_8)
            } catch (_: Exception) {
                String(kotlin.io.encoding.Base64.decode(encodedString.replace("===", "=")), Charsets.UTF_8)
            }
        }

        fun reverseText(inputString: String): String = inputString.reversed()

        fun decodeAndReverse(encodedString: String): String? {
            val tortuga = tortugaDecode(encodedString)
            if (tortuga?.startsWith("http") == true) return tortuga

            val decrypted = torDecrypt(encodedString)
            if (decrypted.startsWith("http")) return decrypted

            val decoded = decodeBase64(encodedString)
            return decoded?.let { reverseText(it) }
        }
    }
}