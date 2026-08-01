package com.lagradost

/** Перевіряє мінімальну форму JSON-LD перед викликом серіалізатора. */
internal fun hasJsonObjectShape(rawJson: String?): Boolean =
    rawJson?.trim()?.let { it.startsWith("{") && it.endsWith("}") } == true
