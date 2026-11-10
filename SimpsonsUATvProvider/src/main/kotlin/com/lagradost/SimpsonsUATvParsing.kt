package com.lagradost

import com.lagradost.common.TitleUtils

internal val titleMap = mapOf(
    "simpsony"             to "Сімпсони",
    "allfuturama"          to "Футурама",
    "family-guy"           to "Гріфіни",
    "pivdennyi-park"       to "Південний Парк",
    "riksanchez"           to "Рік та Морті",
    "solar-opposites"      to "Сонячні протилежності",
    "rozcharuvannya"       to "Розчарування",
    "duncanville"          to "Дунканвілл",
    "nevkolupnyi"          to "Невразливий",
    "central-park"         to "Центральний Парк",
    "sponge"               to "Губка Боб Квадратні Штани",
    "american-dad"         to "Американський тато",
    "clevelandshow"        to "Шоу Клівленда",
    "brickleberry"         to "Бріклбері",
    "pd-paradise"          to "Поліція Парадайз",
    "polus"                to "Полюс",
    "bojack"               to "Кінь BoДжек",
    "tuca-and-bertie"      to "Тука і Bertie",
    "big-mouth"            to "Великий рот",
    "gravity-falls"        to "Ґравіті Фолз",
    "amfibiya"             to "Амфібія",
    "owl-house"            to "Совиний Дім",
    "hotel-hazbin"         to "Готель Хазбін",
    "pekelniy-bos"         to "Пекельний бос",
    "gilda"                to "Гільда",
    "final-space"          to "Космічний рубіж",
    "adventure-time"       to "Час пригод",
    "star-proty-syl-zla"   to "Зоряна принцеса проти сил зла",
    "opivnichne-evangelie" to "Опівнічне Євангеліє",
    "infinity-train"       to "Нескінченний поїзд",
    "my-little-pony"       to "My Little Pony",
    "maylo-merfi"          to "Закон Майла Мерфі",
    "fineas-ferb"          to "Фінеас і Ферб",
    "rockos-modern-life"   to "Сучасне рок-життя Рокко",
    "invader-zim"          to "Загарвник Зім"
)

internal val sectionNameMap = mapOf(
    "inshe"                   to "Цікавинки",
    "dobirky"                 to "Добірки",
    "halloween"               to "Гелловін",
    "rizdvo"                  to "Різдво",
    "majbutnye"               to "Майбутнє",
    "main-simpsons-episodes"  to "Головні серії",
    "lito"                    to "Літо",
    "pereyizd"                to "Переїзд",
    "podoroz"                 to "Подорожі",
    "shkola"                  to "Школа",
    "love"                    to "Кохання",
    "lgbt"                    to "ЛГБТ",
    "patrik"                  to "Патрік",
    "simpsony-u-kino"         to "У кіно",
    "tracey-ullman-show"      to "Tracey Ullman Show"
)

internal val specialSlugs = setOf(
    "inshe", "dobirky", "simpsony-u-kino",
    "halloween", "rizdvo", "majbutnye", "main-simpsons-episodes",
    "lito", "pereyizd", "podoroz", "shkola", "love", "lgbt", "patrik",
    "tracey-ullman-show"
)

internal val ignoredUrlPatterns = listOf(
    "/multserialy-ukrainskoyu/",
    "/terms.html",
    "/subscribe.html",
    "/index.php",
    "do=login",
    "t.me/",
    "youtube.com",
    "tiktok.com",
    "x.com/",
    "/blog/",
    "franecki.net",
    "franeski.net",
    "javascript:"
)

private val numericPrefixRegex = Regex("""^\d+[-_]""")
private val seasonNumberRegex = Regex("""sezon-(\d+)""")

internal fun capitalizeWord(str: String): String {
    return str.split(" ").joinToString(" ") { word ->
        if (word.isNotEmpty()) word.substring(0, 1).uppercase() + word.substring(1)
        else ""
    }
}

internal fun isValidContentUrl(href: String): Boolean {
    if (href.isBlank() || (!href.startsWith("http") && !href.startsWith("/"))) return false
    return ignoredUrlPatterns.none { href.contains(it, ignoreCase = true) }
}

internal fun urlSlug(url: String) = url.removeSuffix("/").substringAfterLast("/")

internal fun fallbackTitle(url: String): String {
    val slug = urlSlug(url)
        .substringBefore("?")
        .substringBeforeLast(".")
        .replace(numericPrefixRegex, "")
    return titleMap[slug] ?: capitalizeWord(slug.replace("-", " "))
}

internal fun parseSeasonNumber(url: String): Int {
    return seasonNumberRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: -1
}

/** Очищає SEO хвости на кшталт "дивитися онлайн" та "українською" з кінця назви. */
internal fun cleanSimpsonsTitle(text: String): String {
    return TitleUtils.stripSeo(text)
}
