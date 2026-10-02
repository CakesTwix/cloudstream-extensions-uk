package com.lagradost

/**
 * Один трек субтитрів, готовий до передачі у subtitleCallback.
 * Публічний, бо фігурує у сигнатурах екстракторів.
 */
data class AnitubeSubtitle(val label: String, val url: String)

const val DEFAULT_SUBTITLE_LABEL = "Субтитри"

/**
 * Розбирає поле `subtitle:` плеєрів на базі Playerjs (ashdi.vip, csst.online, monstro.*).
 *
 * Формат — набір пар `[Назва]посилання`, розділених комами:
 *   `subtitle:"[Повні]https://a.vtt,[Написи]https://b.vtt"`
 * Трапляється і варіант без назви — тоді лишається одне посилання без дужок.
 */
internal fun parsePlayerJsSubtitles(raw: String?): List<AnitubeSubtitle> {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return emptyList()

    val parsed = mutableListOf<AnitubeSubtitle>()
    if (value.contains('[')) {
        // `[^,\[\]]+` зупиняє посилання на комі, тому сусідні треки не злипаються
        Regex("""\[([^\]]*)\]\s*([^,\[\]]+)""").findAll(value).forEach { match ->
            val label = match.groupValues[1].trim()
            val url = match.groupValues[2].trim().trim(',').trim()
            if (url.isNotBlank()) {
                parsed.add(AnitubeSubtitle(label.ifBlank { DEFAULT_SUBTITLE_LABEL }, url))
            }
        }
    } else {
        value.split(',').forEach { part ->
            val url = part.trim()
            if (url.startsWith("http", ignoreCase = true) || url.startsWith("//")) {
                parsed.add(AnitubeSubtitle(DEFAULT_SUBTITLE_LABEL, url))
            }
        }
    }

    return parsed.distinctSubtitles()
}

/**
 * Витягує треки субтитрів із master-плейлиста HLS (`#EXT-X-MEDIA:TYPE=SUBTITLES`).
 * Частина плеєрів віддає форсовані субтитри саме так, а не окремим полем `subtitle:`.
 */
internal fun parseHlsSubtitles(playlist: String?, playlistUrl: String): List<AnitubeSubtitle> {
    val content = playlist.orEmpty()
    if (content.isBlank()) return emptyList()

    val parsed = mutableListOf<AnitubeSubtitle>()
    content.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) return@forEach

        val attributes = parseHlsAttributes(trimmed.substringAfter(':'))
        if (!attributes["TYPE"].equals("SUBTITLES", ignoreCase = true)) return@forEach

        val url = resolveAnitubeUrl(playlistUrl, attributes["URI"].orEmpty()) ?: return@forEach
        val name = attributes["NAME"]?.takeIf { it.isNotBlank() }
            ?: attributes["LANGUAGE"]?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SUBTITLE_LABEL

        parsed.add(
            AnitubeSubtitle(
                markForcedSubtitle(name, attributes["FORCED"].equals("YES", ignoreCase = true)),
                url,
            )
        )
    }

    return parsed.distinctSubtitles()
}

/** Розбирає список атрибутів HLS, не ламаючись на комах усередині лапок. */
private fun parseHlsAttributes(raw: String): Map<String, String> {
    val attributes = mutableMapOf<String, String>()
    val current = StringBuilder()
    var inQuotes = false

    fun flush() {
        val pair = current.toString()
        current.setLength(0)
        val key = pair.substringBefore('=', "").trim()
        if (key.isBlank() || !pair.contains('=')) return
        attributes[key.uppercase()] = pair.substringAfter('=').trim().trim('"')
    }

    raw.forEach { char ->
        when {
            char == '"' -> { inQuotes = !inQuotes; current.append(char) }
            char == ',' && !inQuotes -> flush()
            else -> current.append(char)
        }
    }
    flush()

    return attributes
}

/** Додає позначку до назви, щоб форсовані субтитри було видно у списку плеєра. */
internal fun markForcedSubtitle(label: String, isForced: Boolean): String {
    if (!isForced) return label
    val alreadyMarked = label.contains("forced", ignoreCase = true) ||
        label.contains("форс", ignoreCase = true) ||
        label.contains("напис", ignoreCase = true)
    return if (alreadyMarked) label else "$label (форсовані)"
}

/**
 * Приводить посилання з плейлиста чи конфігу плеєра до абсолютного вигляду.
 * Повертає null, якщо значення порожнє або не схоже на посилання.
 */
internal fun resolveAnitubeUrl(base: String, value: String): String? {
    val target = value.trim()
    if (target.isBlank()) return null

    return when {
        target.startsWith("http://", ignoreCase = true) ||
            target.startsWith("https://", ignoreCase = true) -> target
        target.startsWith("//") -> "https:$target"
        target.startsWith("/") -> baseOrigin(base)?.plus(target)
        else -> baseDirectory(base)?.plus(target)
    }
}

private fun baseOrigin(base: String): String? {
    val schemeEnd = base.indexOf("://")
    if (schemeEnd <= 0) return null
    val hostEnd = base.indexOf('/', schemeEnd + 3)
    return if (hostEnd < 0) base else base.substring(0, hostEnd)
}

private fun baseDirectory(base: String): String? {
    val withoutQuery = base.substringBefore('?').substringBefore('#')
    val schemeEnd = withoutQuery.indexOf("://")
    if (schemeEnd <= 0) return null
    val lastSlash = withoutQuery.lastIndexOf('/')
    if (lastSlash <= schemeEnd + 2) return "$withoutQuery/"
    return withoutQuery.substring(0, lastSlash + 1)
}

/**
 * Дістає JS-об'єкт після вказаного маркера (напр. `window.FENIX_PLAYLIST =`),
 * рахуючи дужки, бо такі конфіги задовгі й надто вкладені для регулярного виразу.
 */
internal fun extractJsObject(html: String, marker: String): String? {
    val markerIndex = html.indexOf(marker)
    if (markerIndex < 0) return null

    val start = html.indexOf('{', markerIndex + marker.length)
    if (start < 0) return null

    var depth = 0
    var inString = false
    var quote = ' '
    var escaped = false

    for (index in start until html.length) {
        val char = html[index]
        when {
            escaped -> escaped = false
            char == '\\' && inString -> escaped = true
            inString && char == quote -> inString = false
            !inString && (char == '"' || char == '\'') -> { inString = true; quote = char }
            inString -> Unit
            char == '{' -> depth++
            char == '}' -> {
                depth--
                if (depth == 0) return html.substring(start, index + 1)
            }
        }
    }

    return null
}

/** Прибирає дублікати за посиланням, лишаючи першу (найінформативнішу) назву. */
internal fun List<AnitubeSubtitle>.distinctSubtitles(): List<AnitubeSubtitle> =
    distinctBy { it.url }
