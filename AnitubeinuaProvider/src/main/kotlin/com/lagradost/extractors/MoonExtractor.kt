package com.lagradost.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Витягує відео з плеєра MOON (moonanime.art).
 * moonanime віддає посилання зашифрованим у JS, тому тут є процедура розшифрування.
 * Перенесено з провайдера AnimeON.
 *
 * Використання:
 *   MoonExtractor().getUrl(iframeUrl, "ПЛЕЄР MOON (Студія)") { link -> callback(link) }
 */
class MoonExtractor {

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0"
    private val moonReferer = "https://moonanime.art/"

    private val moonVideoHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to moonReferer,
        "Origin" to "https://moonanime.art",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Dest" to "video",
        "X-Requested-With" to "mark.via.gp"
    )

    /** Головний вхід: дістати iframe MOON і віддати посилання через callback. */
    suspend fun getUrl(iframeUrl: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
        val rawFile = getMoonFile(iframeUrl)
        if (rawFile.isNotEmpty()) processMoonRawFile(rawFile, sourceName, callback)
    }

    private suspend fun processMoonRawFile(
        rawFile: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (rawFile.startsWith("[")) {
            val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")
            qualityRegex.findAll(rawFile).forEach { match ->
                val qUrl = match.groupValues[2]
                val qualityInt = match.groupValues[1].replace("p", "").toIntOrNull()
                    ?: Qualities.Unknown.value
                when {
                    qUrl.contains(".m3u8") -> emitM3u8(qUrl, sourceName, callback)
                    qUrl.contains("s.moonanime.art") || qUrl.contains("moonanime.art/content") -> {
                        val finalUrl = resolveMoonContent(qUrl)
                        if (!finalUrl.isNullOrEmpty()) emitVideo(finalUrl, sourceName, qualityInt, callback)
                    }
                    else -> emitVideo(qUrl, sourceName, qualityInt, callback)
                }
            }
        } else if (rawFile.contains(".m3u8")) {
            emitM3u8(rawFile, sourceName, callback)
        } else if (rawFile.contains("s.moonanime.art") || rawFile.contains("moonanime.art/content")) {
            val finalUrl = resolveMoonContent(rawFile)
            if (!finalUrl.isNullOrEmpty())
                emitVideo(finalUrl, sourceName, Qualities.Unknown.value, callback)
        } else {
            emitVideo(rawFile, sourceName, Qualities.Unknown.value, callback)
        }
    }

    private suspend fun emitM3u8(url: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
        // M3u8Helper є suspend всередині провайдера; тут викликаємо без додаткових налаштувань
        val streams = M3u8Helper.generateM3u8(
            source = sourceName,
            streamUrl = url,
            referer = moonReferer,
            headers = moonVideoHeaders
        )
        val filtered = streams.dropLast(1)
        (if (filtered.isNotEmpty()) filtered else streams).forEach(callback)
    }

    private fun emitVideo(
        url: String,
        sourceName: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            ExtractorLink(
                source = sourceName,
                name = sourceName,
                url = url,
                referer = moonReferer,
                quality = quality,
                type = ExtractorLinkType.VIDEO,
                headers = moonVideoHeaders
            )
        )
    }

    private suspend fun resolveMoonContent(contentUrl: String): String? {
        return try {
            val cookieResponse = app.get(
                moonReferer,
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
                    "User-Agent" to userAgent,
                    "Accept" to "*/*",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to moonReferer,
                    "Origin" to "https://moonanime.art",
                    "Sec-Fetch-Site" to "same-site",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty",
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

    private suspend fun getMoonFile(iframeUrl: String): String {
        val cleanUrl = iframeUrl
            .replace(Regex("[?&]player=[^&]*"), "")
            .replace("?&", "?")
            .trimEnd('?', '&')

        val html = try {
            app.get(
                cleanUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "https://anitube.in.ua/",
                    "X-Requested-With" to "mark.via.gp",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-User" to "?1",
                    "Sec-Fetch-Dest" to "document",
                    "Upgrade-Insecure-Requests" to "1"
                ),
                cacheTime = 0
            ).text
        } catch (e: Exception) {
            ""
        }

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

                        for (match in matches) {
                            val decoded = moonDecrypt(match.groupValues[1], xorKey)
                            if (decoded.isEmpty()) continue
                            val isVideoOrPlaylist = decoded.contains(".m3u8") ||
                                    decoded.contains(".mp4") || decoded.contains(".webm") ||
                                    decoded.startsWith("[")
                            val isMoonDomain = decoded.contains("mooncdn") ||
                                    decoded.contains("moonanime.art/content") ||
                                    decoded.contains("s.moonanime.art")
                            val isStaticAsset = decoded.contains(
                                Regex("""\.(jpg|jpeg|png|vtt|srt|txt)(\?|$)""", RegexOption.IGNORE_CASE)
                            )
                            if ((isVideoOrPlaylist || isMoonDomain) && !isStaticAsset) {
                                return decoded
                            }
                        }
                    }

                    val contentUrlRegex = Regex("""(https?://s\.moonanime\.art/content/[^\s"'`]+)""")
                    val contentMatch = contentUrlRegex.find(decodedJs)?.groupValues?.get(1)
                    if (!contentMatch.isNullOrEmpty() &&
                        !contentMatch.contains(Regex("""\.(jpg|jpeg|png)$"""))
                    ) {
                        val resolved = resolveMoonContent(contentMatch)
                        if (!resolved.isNullOrEmpty()) return resolved
                    }
                }
            }
        }

        // Fallback: пряме звернення до s.moonanime.art за хешем з iframe-URL
        val hash = Regex("""/iframe/([a-zA-Z0-9]+)/?""").find(cleanUrl)?.groupValues?.get(1)
        if (!hash.isNullOrEmpty()) {
            val qualityResults = mutableListOf<String>()
            for (quality in listOf(1080, 720, 480, 360)) {
                val resolved = resolveMoonContent("https://s.moonanime.art/content/v/$hash/$quality/")
                if (!resolved.isNullOrEmpty()) qualityResults.add("[${quality}p]$resolved")
            }
            if (qualityResults.isNotEmpty()) return qualityResults.joinToString(",")
        }

        return ""
    }

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val cleanEncoded = encoded.replace("\\s".toRegex(), "")
            val decoded = android.util.Base64.decode(cleanEncoded, android.util.Base64.DEFAULT)
            val decryptedBytes = ByteArray(decoded.size)
            for (i in decoded.indices) {
                decryptedBytes[i] =
                    ((decoded[i].toInt() and 0xFF) xor key[i % key.length].code).toByte()
            }
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
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
        } catch (e: Exception) {
            ""
        }
    }
}