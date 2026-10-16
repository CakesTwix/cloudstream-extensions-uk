package com.lagradost

/** Перевіряє мінімальну форму JSON-LD перед викликом серіалізатора. */
internal fun hasJsonObjectShape(rawJson: String?): Boolean =
    rawJson?.trim()?.let { it.startsWith("{") && it.endsWith("}") } == true

/** Рік у дужках у кінці назви, напр. `(2019)`. Компілюється один раз. */
private val titleYearRegex = Regex("""\s*\(\d{4}\)\s*$""")

/** Прибирає рік у дужках із кінця назви, напр. `"Рік та Морті (2019)"` -> `"Рік та Морті"`. */
internal fun stripTitleYear(title: String): String =
    title.replace(titleYearRegex, "").trim()
