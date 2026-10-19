package com.lagradost.common

data class CleanedTitle(
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
)

object TitleUtils {

    private val seoPatterns = listOf(
        // "дивитись/дивитися онлайн ... (в HD, в хорошій якості тощо)"
        Regex("""\s*[-–—/:|]?\s*дивити(?:сь|ся)\s+онлайн.*$""", RegexOption.IGNORE_CASE),
        // " - українською (мовою/онлайн)"
        Regex("""\s*[-–—/:|]\s*українською(?:\s+мовою)?(?:\s+онлайн)?\s*$""", RegexOption.IGNORE_CASE),
        // "(українською/озвучка/дубляж)" в дужках на кінці
        Regex("""\s*\((?:українською|укр\.?|озвучка|дубляж)[^)]*\)\s*$""", RegexOption.IGNORE_CASE),
        // контекстні категорії перед "українською" (наприклад "мультсеріали українською", "всі серії українською")
        Regex(
            """(\b(?:всі\s+серії|сезон|серія|дубляж|озвучка|переклад|фільми?|серіали?|мультфільми?|мультсеріали?))\s+українською(?:\s+мовою)?\s*$""",
            RegexOption.IGNORE_CASE
        ),
    )

    private val trailingPunctuationRegex = Regex("""[\s\-–—/:|]+$""")
    private val trailingYearRegex = Regex("""\s*\(((?:19|20)\d{2})\)\s*$""")
    private val cyrillicRegex = Regex("""[\u0400-\u04FF]""")
    private val latinRegex = Regex("""[a-zA-Z]""")
    private val whitespaceRegex = Regex("""\s+""")

    /**
     * Очищає лише SEO-хвости та кінцеву пунктуацію/зайві пробіли.
     * Не чіпає рік та оригінальні назви.
     */
    fun stripSeo(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var result = raw.trim()

        for (regex in seoPatterns) {
            result = if (regex.pattern.startsWith("(\\b")) {
                regex.replace(result, "$1")
            } else {
                regex.replace(result, "")
            }
        }

        return result
            .replace(trailingPunctuationRegex, "")
            .replace(whitespaceRegex, " ")
            .trim()
    }

    /**
     * Відсікає рік у дужках на кінці назви, якщо він там присутній:
     * "Фільм (2024)" -> ("Фільм", 2024).
     * Якщо року в дужках на кінці немає, повертає (назва, null).
     */
    fun stripYear(raw: String?): Pair<String, Int?> {
        val clean = stripSeo(raw)
        val match = trailingYearRegex.find(clean) ?: return clean to null
        val year = match.groupValues.getOrNull(1)?.toIntOrNull()
        val titleWithoutYear = clean.removeRange(match.range)
            .replace(trailingPunctuationRegex, "")
            .trim()
        return titleWithoutYear to year
    }

    /**
     * Відокремлює оригінальну назву від української, якщо вони розділені слешем:
     * "Геніальний план / Masterplan" -> ("Геніальний план", "Masterplan")
     * "Первородні / Прадавні / The Originals" -> ("Первородні / Прадавні", "The Originals")
     * "Первородні / Прадавні" -> ("Первородні / Прадавні", null)
     */
    fun splitOriginalTitle(raw: String?): Pair<String, String?> {
        val clean = stripSeo(raw)
        val parts = clean.split("/").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size <= 1) return clean to null

        val cyrillicParts = parts.filter { cyrillicRegex.containsMatchIn(it) }
        val latinParts = parts.filter { latinRegex.containsMatchIn(it) && !cyrillicRegex.containsMatchIn(it) }

        return if (cyrillicParts.isNotEmpty() && latinParts.isNotEmpty()) {
            cyrillicParts.joinToString(" / ") to latinParts.joinToString(" / ")
        } else {
            clean to null
        }
    }

    /**
     * Універсальне очищення назви.
     * За замовчуванням прибирає SEO-хвости та нормалізує пробіли/пунктуацію.
     * Якщо [stripYear] = true, також прибирає кінцевий рік у дужках.
     */
    fun cleanTitle(raw: String?, stripYear: Boolean = false): String {
        return if (stripYear) {
            stripYear(raw).first
        } else {
            stripSeo(raw)
        }
    }

    /**
     * Комплексний аналіз та очищення назви з вилученням окремо української назви,
     * оригінальної назви та року (за наявності).
     */
    fun cleanTitleWithOriginal(raw: String?, stripYear: Boolean = false): CleanedTitle {
        val (titleWithoutSeo, extractedYear) = if (stripYear) {
            stripYear(raw)
        } else {
            stripSeo(raw) to null
        }

        val (mainTitle, originalTitle) = splitOriginalTitle(titleWithoutSeo)

        return CleanedTitle(
            title = mainTitle,
            originalTitle = originalTitle,
            year = extractedYear,
        )
    }
}
