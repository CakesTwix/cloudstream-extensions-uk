package com.lagradost.models

object MoonUtils {

    val baseDomains = listOf("https://moonanime.art", "https://moonanime.net")

    private val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
    private val keyRegex = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
    private val callRegex = Regex("""(\w+)\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
    private val rawPosterEncRegex = Regex("""rawPoster\s*=\s*\w+\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
    private val rawPosterPlainRegex = Regex("""rawPoster\s*=\s*["'](https?://[^"']+)["']""")
    private val posterPropRegex = Regex("""poster["']?\s*[:=]\s*["'](https?://[^"']+)["']""")
    private val imageExtRegex = Regex("""\.(webp|jpe?g|png|avif)(\?|$)""", RegexOption.IGNORE_CASE)
    private val htmlPosterRegex = Regex("""((?:https?:)?//[^"'\s\\]+/poster\.(?:webp|jpg|jpeg|png)(?:\?[^"'\s\\]*)?)""")
    private val htmlPosterPropRegex = Regex("""poster["']?\s*:\s*["']([^"']+)["']""")
    private val subtitleEncRegex = Regex("""(?:subtitle|rawSubtitle)\s*[:=]\s*\w+\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
    private val subtitleEntryRegex = Regex("""\[([^\]]+)\](https?://[^\[,]+)""")
    private val staticAssetRegex = Regex("""\.(jpg|jpeg|png|webp|avif|vtt|srt|txt)(\?|$)""", RegexOption.IGNORE_CASE)
    private val contentUrlRegex = Regex("""(https?://s\.moonanime\.(?:net|art)/content/[^\s"'`]+)""")
    private val imageTailRegex = Regex("""\.(jpg|jpeg|png)$""")
    private val hashRegex = Regex("""/iframe/([a-zA-Z0-9]+)/?""")

    fun origin(url: String): String {
        return try {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            "https://moonanime.art"
        }
    }

    fun baseFromUrl(url: String): String {
        return origin(url).replace("://s.moonanime", "://moonanime")
    }

    fun withPlayerParam(url: String, player: String = "animeon.club"): String {
        return if (url.contains("player=")) {
            url
        } else {
            "$url${if (url.contains("?")) "&" else "?"}player=$player"
        }
    }

    fun videoHeaders(url: String, userAgent: String): Map<String, String> {
        val base = baseFromUrl(url)
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$base/",
            "Origin" to base,
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Dest" to "video",
            "X-Requested-With" to "mark.via.gp"
        )
    }

    fun iframeHeaders(mainUrl: String, userAgent: String): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$mainUrl/",
            "X-Requested-With" to "mark.via.gp",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-User" to "?1",
            "Sec-Fetch-Dest" to "document",
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    fun decrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val cleanEncoded = encoded.replace("\\s".toRegex(), "")
            val decoded = android.util.Base64.decode(cleanEncoded, android.util.Base64.DEFAULT)
            val decryptedBytes = ByteArray(decoded.size)

            for (i in decoded.indices) {
                decryptedBytes[i] = ((decoded[i].toInt() and 0xFF) xor key[i % key.length].code).toByte()
            }

            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun outerDecode(base64Blob: String): String {
        return try {
            val raw = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)
            if (raw.size < 33) return ""

            val state0 = raw[0].toInt() and 0xFF
            val key = raw.sliceArray(1 until 33)
            val data = raw.sliceArray(33 until raw.size)
            val result = ByteArray(data.size)

            var state = state0

            for (i in data.indices) {
                val d = data[i].toInt() and 0xFF
                val k = key[i % 32].toInt() and 0xFF
                result[i] = (d xor k xor state).toByte()
                state = (d + k) and 0xFF
            }

            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun playerJs(html: String): String {
        for (m in atobRegex.findAll(html)) {
            val d = outerDecode(m.groupValues[1])
            if (d.contains("_0xd") || d.contains("file")) {
                return d
            }
        }
        return ""
    }

    fun xorKey(js: String): String? {
        return keyRegex.find(js)?.groupValues?.get(1)
    }

    fun posterUrl(html: String): String? {
        if (html.isEmpty()) return null

        fun isUsablePoster(url: String?): Boolean {
            return !url.isNullOrEmpty() && url.startsWith("http") && !url.contains("mooncdn.")
        }

        for (match in atobRegex.findAll(html)) {
            val decoded = outerDecode(match.groupValues[1])
            if (decoded.isEmpty()) continue

            val key = xorKey(decoded)

            if (!key.isNullOrEmpty()) {
                val encodedPoster = rawPosterEncRegex.find(decoded)?.groupValues?.get(1)
                if (!encodedPoster.isNullOrEmpty()) {
                    val decrypted = decrypt(encodedPoster, key).trim()
                    if (isUsablePoster(decrypted)) {
                        return decrypted
                    }
                }
            }

            val plainPoster = rawPosterPlainRegex.find(decoded)?.groupValues?.get(1)
            if (isUsablePoster(plainPoster)) {
                return plainPoster
            }

            val propPoster = posterPropRegex.find(decoded)?.groupValues?.get(1)
            if (isUsablePoster(propPoster)) {
                return propPoster
            }

            if (!key.isNullOrEmpty()) {
                for (callMatch in callRegex.findAll(decoded)) {
                    val result = decrypt(callMatch.groupValues[2], key).trim()
                    if (isUsablePoster(result) && imageExtRegex.containsMatchIn(result)) {
                        return result
                    }
                }
            }
        }

        for (pattern in listOf(htmlPosterRegex, htmlPosterPropRegex)) {
            val match = pattern.find(html) ?: continue
            val extracted = match.groupValues[1]
            val finalUrl = if (extracted.startsWith("//")) "https:$extracted" else extracted
            if (isUsablePoster(finalUrl)) {
                return finalUrl
            }
        }

        return null
    }

    fun subtitleString(js: String, key: String): String? {
        val encoded = subtitleEncRegex.find(js)?.groupValues?.get(1)
        if (encoded.isNullOrEmpty()) return null

        val decoded = decrypt(encoded, key)
        val entries = mutableListOf<Pair<String, String>>()
        val entryMatches = subtitleEntryRegex.findAll(decoded).toList()

        if (entryMatches.isNotEmpty()) {
            entryMatches.forEach { m ->
                entries.add(Pair(m.groupValues[1], m.groupValues[2].trim(',', ' ')))
            }
        } else if (decoded.startsWith("http")) {
            entries.add(Pair("UA", decoded.trim()))
        }

        if (entries.isEmpty()) return null

        return entries.joinToString("|||") { "${it.first}::${it.second}" }
    }

    fun rawFile(js: String, key: String): String? {
        val candidates = mutableListOf<String>()

        for (match in callRegex.findAll(js)) {
            val decoded = decrypt(match.groupValues[2], key)
            if (decoded.isEmpty()) continue

            val isVideoOrPlaylist = decoded.contains(".m3u8") || decoded.contains(".mp4") ||
                decoded.contains(".webm") || decoded.startsWith("[")
            val isMoonDomain = decoded.contains("mooncdn") || decoded.contains("moonanime")
            val isStaticAsset = staticAssetRegex.containsMatchIn(decoded)

            if ((isVideoOrPlaylist || isMoonDomain) && !isStaticAsset) {
                candidates.add(decoded)
            }
        }

        if (candidates.isEmpty()) return null

        candidates.firstOrNull { it.contains(".m3u8") }?.let { return it }
        candidates.firstOrNull { it.contains("avc", true) || it.contains("h264", true) }?.let { return it }
        candidates.firstOrNull { !it.contains(".webm", true) && !it.contains("vp9", true) }?.let { return it }

        return candidates.first()
    }

    fun contentUrl(js: String): String? {
        val match = contentUrlRegex.find(js)?.groupValues?.get(1) ?: return null
        if (match.isEmpty() || imageTailRegex.containsMatchIn(match)) return null
        return match
    }

    fun iframeHash(url: String): String? {
        return hashRegex.find(url)?.groupValues?.get(1)
    }
}