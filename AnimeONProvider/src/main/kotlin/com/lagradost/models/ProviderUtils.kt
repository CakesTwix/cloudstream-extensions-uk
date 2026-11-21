package com.lagradost.models

import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.ConcurrentHashMap

object ProviderUtils {

    private val codecSupportCache = ConcurrentHashMap<String, Boolean>()
    private val hevcProfileRegex = Regex("""(?:hvc1|hev1)\.(\d+)""")

    fun isImageBytes(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 12) return false

        fun b(i: Int): Int = bytes[i].toInt() and 0xFF

        return (b(0) == 0x89 && b(1) == 0x50) ||
            (b(0) == 0xFF && b(1) == 0xD8) ||
            (b(0) == 0x52 && b(1) == 0x49 && b(8) == 0x57 && b(9) == 0x45) ||
            (b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70) ||
            (b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46)
    }

    fun imageContentType(bytes: ByteArray): String {
        fun b(i: Int): Int = bytes[i].toInt() and 0xFF

        return when {
            bytes.size > 8 && b(0) == 0x89 && b(1) == 0x50 -> "image/png"
            bytes.size > 2 && b(0) == 0xFF && b(1) == 0xD8 -> "image/jpeg"
            bytes.size > 9 && b(0) == 0x52 && b(1) == 0x49 -> "image/webp"
            bytes.size > 8 && b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 -> "image/avif"
            else -> "application/octet-stream"
        }
    }

    fun isVp9Webm(url: String): Boolean = url.contains(".webm", true)

    fun codecLabel(codecs: String?): String? {
        val c = codecs?.lowercase() ?: return null
        return when {
            c.contains("vp09") || c.contains("vp9") -> "VP9"
            c.contains("av01") -> "AV1"
            c.contains("hvc1.2") || c.contains("hev1.2") -> "HEVC 10bit"
            else -> null
        }
    }

    fun filterStreams(streams: List<ExtractorLink>): List<ExtractorLink> {
        if (streams.size < 2) return streams

        val distinct = streams.distinctBy { "${it.quality}|${it.url.substringBefore("?")}" }
        if (distinct.size < 2) return distinct

        val maxQuality = distinct.maxOf { it.quality }
        val last = distinct.last()

        return if (last.quality < maxQuality) distinct.dropLast(1) else distinct
    }

    fun fixMovieExtractorLink(link: ExtractorLink, sourceName: String): ExtractorLink {
        val cleanQuality = when {
            link.url.contains("/1080/") -> 1080
            link.url.contains("/720/") -> 720
            link.url.contains("/480/") -> 480
            link.url.contains("/360/") -> 360
            else -> when (link.quality) {
                in 900..1150 -> 1080
                in 600..899 -> 720
                in 400..599 -> 480
                in 240..399 -> 360
                else -> link.quality
            }
        }

        return ExtractorLink(
            source = link.source,
            name = sourceName,
            url = link.url,
            referer = link.referer,
            quality = cleanQuality,
            type = link.type,
            headers = link.headers,
            extractorData = link.extractorData
        )
    }

    fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun buildEpisodeDataJson(sources: List<EpisodeSource>): String {
        val sb = StringBuilder("[")
        sources.forEachIndexed { index, s ->
            if (index > 0) sb.append(",")
            sb.append("{\"translationName\":\"${escapeJson(s.translationName)}\",")
            sb.append("\"playerName\":\"${escapeJson(s.playerName)}\",")
            sb.append("\"episodeId\":${s.episodeId},")
            sb.append("\"episodeNum\":${s.episodeNum ?: 0}}")
        }
        sb.append("]")
        return sb.toString()
    }

    fun subtitleExtFromUrl(url: String): String {
        val path = url.substringBefore("?").substringAfterLast("/")

        return when {
            path.endsWith(".ass", true) -> ".ass"
            path.endsWith(".ssa", true) -> ".ass"
            path.endsWith(".vtt", true) -> ".vtt"
            path.endsWith(".srt", true) -> ".srt"
            else -> ".vtt"
        }
    }

    fun subtitleContentType(ext: String): String {
        return when (ext) {
            ".ass" -> "text/x-ssa"
            ".srt" -> "application/x-subrip"
            else -> "text/vtt"
        }
    }

    fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") {
            return value.value.drop(1).toIntOrNull()
        }
        return value.value.toIntOrNull()
    }

    fun currentSeasonLabel(): String {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1

        val seasonUa = when (month) {
            12, 1, 2 -> "зимового"
            3, 4, 5 -> "весняного"
            6, 7, 8 -> "літнього"
            else -> "осіннього"
        }

        return "Аніме ${seasonUa} сезону"
    }

    fun codecSupported(mime: String, width: Int, height: Int, profile: Int? = null): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 21) return false

        val key = "$mime|$width|$height|$profile"
        codecSupportCache[key]?.let { return it }

        val result = try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                if (info.isEncoder) {
                    false
                } else {
                    try {
                        val caps = info.getCapabilitiesForType(mime) ?: return@any false
                        val videoCaps = caps.videoCapabilities ?: return@any false
                        val profileOk = profile == null || caps.profileLevels?.any { it.profile == profile } == true
                        profileOk && videoCaps.isSizeSupported(width, height)
                    } catch (e: Exception) {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            false
        }

        codecSupportCache[key] = result
        return result
    }

    fun hasSoftwareDecoder(mime: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 21) return false
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                if (info.isEncoder) return@any false
                val name = info.name.lowercase()
                val isSoftware = name.startsWith("c2.android.") || name.startsWith("omx.google.")
                isSoftware && try {
                    info.getCapabilitiesForType(mime) != null
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isVariantSupported(codecs: String?, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        val c = codecs?.lowercase() ?: return true

        return when {
            c.contains("hvc1") || c.contains("hev1") -> {
                val idc = hevcProfileRegex.find(c)?.groupValues?.get(1)?.toIntOrNull()
                val profile = when (idc) {
                    2 -> android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    1 -> android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    else -> null
                }
                codecSupported("video/hevc", width, height, profile)
            }
            c.contains("avc1") || c.contains("avc3") -> codecSupported("video/avc", width, height, null)
            c.contains("vp09") || c.contains("vp9") -> codecSupported("video/x-vnd.on2.vp9", width, height, null)
            c.contains("av01") -> codecSupported("video/av01", width, height, null)
            else -> true
        }
    }

    fun checkHevcMain10(): Boolean {
        return codecSupported(
            "video/hevc", 1920, 1080,
            android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        )
    }
}