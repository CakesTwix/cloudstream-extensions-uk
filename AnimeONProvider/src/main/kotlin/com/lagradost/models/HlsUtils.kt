package com.lagradost.models

data class HlsVariant(
    val master: String,
    val uri: String,
    val width: Int,
    val height: Int,
    val codecs: String?,
    val bandwidth: Int
)

object HlsUtils {

    private data class RawVariant(
        val tag: String,
        val uri: String,
        val width: Int,
        val height: Int,
        val codecs: String?,
        val bandwidth: Int
    )

    private val resolutionRegex = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val codecsRegex = Regex("CODECS=\"([^\"]+)\"")
    private val bandwidthRegex = Regex("""BANDWIDTH=(\d+)""")
    private val uriAttrRegex = Regex("URI=\"([^\"]+)\"")

    fun absolutize(baseUrl: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        return try {
            java.net.URI(baseUrl).resolve(uri).toString()
        } catch (e: Exception) {
            uri
        }
    }

    fun maxHeight(master: String): Int {
        return resolutionRegex.findAll(master)
            .mapNotNull { it.groupValues[2].toIntOrNull() }
            .maxOrNull() ?: 0
    }

    fun splitMaster(master: String, baseUrl: String): List<HlsVariant> {
        if (!master.contains("#EXT-X-STREAM-INF")) return emptyList()

        val lines = master.lines()
        val head = StringBuilder()
        val raw = mutableListOf<RawVariant>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val res = resolutionRegex.find(line)
                    val width = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val height = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    val codecs = codecsRegex.find(line)?.groupValues?.get(1)
                    val bandwidth = bandwidthRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                    val uri = if (j < lines.size) lines[j].trim() else ""

                    if (uri.isNotEmpty()) {
                        raw.add(RawVariant(line, absolutize(baseUrl, uri), width, height, codecs, bandwidth))
                    }

                    i = j + 1
                }
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> {
                    i++
                }
                line.contains("URI=\"") -> {
                    head.append(
                        uriAttrRegex.replace(line) { m ->
                            "URI=\"" + absolutize(baseUrl, m.groupValues[1]) + "\""
                        }
                    ).append('\n')
                    i++
                }
                else -> {
                    head.append(line).append('\n')
                    i++
                }
            }
        }

        if (raw.isEmpty()) return emptyList()

        val header = head.toString()

        return raw.map { variant ->
            val text = header + variant.tag + "\n" + variant.uri + "\n"
            HlsVariant(text, variant.uri, variant.width, variant.height, variant.codecs, variant.bandwidth)
        }
    }
}