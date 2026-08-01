package com.lagradost.sync

import kotlin.math.absoluteValue

/**
 * Сервер Sync API повертає Unix time у секундах, тоді як старі версії плагіна
 * зберігали System.currentTimeMillis(). Усі внутрішні часові мітки категорій
 * нормалізуємо до секунд.
 */
object SyncTime {
    private const val MILLIS_THRESHOLD = 100_000_000_000L

    fun toEpochSeconds(timestamp: Long): Long =
        if (timestamp.absoluteValue >= MILLIS_THRESHOLD) timestamp / 1_000L else timestamp

    fun nowEpochSeconds(nowMillis: Long = System.currentTimeMillis()): Long =
        toEpochSeconds(nowMillis)

    fun toEpochMillis(timestamp: Long): Long =
        if (timestamp.absoluteValue >= MILLIS_THRESHOLD) timestamp else timestamp * 1_000L

    fun shouldFetch(
        cloudTimestamp: Double,
        localTimestamp: Long,
        force: Boolean = false,
    ): Boolean =
        force || toEpochSeconds(cloudTimestamp.toLong()) > toEpochSeconds(localTimestamp)

    /** Порівнює timestamp значень SharedPreferences у спільних одиницях. */
    fun shouldRestore(cloudTimestamp: Long, localTimestamp: Long): Boolean =
        (cloudTimestamp == 0L && localTimestamp == 0L) ||
            toEpochSeconds(cloudTimestamp) > toEpochSeconds(localTimestamp)
}

/** Вибирає безпечнішу дію polling, якщо локальні зміни ще не відправлені. */
object SyncPollPolicy {
    fun shouldMerge(hasDirtyCategories: Boolean): Boolean = hasDirtyCategories
}

/**
 * CloudStream додає перед шляхом ключа номер акаунта:
 * `0/result_watch_state/123`. Старий парсер очікував шлях без `0/`.
 */
object SyncKeyPath {
    fun itemId(key: String): Int? = key.substringAfterLast('/').toIntOrNull()

    fun relatedTimestampKeys(key: String, category: SyncCategory): List<String> {
        val parts = key.split('/')
        if (parts.size < 2) return emptyList()
        val id = parts.last().toIntOrNull() ?: return emptyList()
        val type = parts[parts.lastIndex - 1].lowercase()
        val prefix = parts.dropLast(2)

        fun sibling(name: String): String =
            (prefix + name + id.toString()).joinToString("/")

        return when (category) {
            SyncCategory.BOOKMARKS -> when (type) {
                "result_watch_state", "result_watch_state_data" ->
                    listOf(sibling("result_watch_state_data"))
                else -> emptyList()
            }
            SyncCategory.RESUME_WATCHING -> when (type) {
                "result_resume_watching", "result_resume_watching_2",
                "result_season", "result_dub", "result_episode" ->
                    listOf(sibling("result_resume_watching_2"))
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    fun itemTimestamp(
        key: String,
        category: SyncCategory,
        stringMap: Map<String, String>?,
    ): Long {
        if (stringMap == null) return 0L
        extractTimestamp(stringMap[key]).takeIf { it > 0L }?.let {
            return SyncTime.toEpochSeconds(it)
        }
        for (relatedKey in relatedTimestampKeys(key, category)) {
            extractTimestamp(stringMap[relatedKey]).takeIf { it > 0L }?.let {
                return SyncTime.toEpochSeconds(it)
            }
        }
        return 0L
    }

    /** Читає timestamp із JSON-значення, яке зберігається у SharedPreferences. */
    fun extractTimestamp(json: String?): Long {
        if (json == null) return 0L
        return try {
            "\"updateTime\":\\s*(\\d+)".toRegex().find(json)?.groupValues?.get(1)?.toLong()
                ?: "\"latestUpdatedTime\":\\s*(\\d+)".toRegex()
                    .find(json)?.groupValues?.get(1)?.toLong()
                ?: "\"searchedAt\":\\s*(\\d+)".toRegex().find(json)?.groupValues?.get(1)?.toLong()
                ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
