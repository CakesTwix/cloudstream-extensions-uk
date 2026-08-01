package com.lagradost.sync

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object SyncStorage {

    // TODO: Перед публічним використанням зберігати bearer-токен через
    // Android Keystore/зашифроване сховище, а не загальний DataStore хоста.
    var creds: SyncCreds?
        get() = getKey("CLOUDSYNC_CREDS")
        set(value) {
            setKey("CLOUDSYNC_CREDS", value)
        }

    var syncV2Migrated: Boolean
        get() = getKey("CLOUDSYNC_V2_MIGRATED") ?: false
        set(value) {
            setKey("CLOUDSYNC_V2_MIGRATED", value)
        }

    fun getCategoryTimestamp(category: SyncCategory): Long {
        val key = "CLOUDSYNC_TS_${category.key}"
        val stored: Long = getKey(key) ?: 0L
        val normalized = SyncTime.toEpochSeconds(stored)
        if (stored != normalized) {
            // Міграція з v1: нуль примусить одне повне отримання даних і
            // відновить непарні ключі закладок, залишені старим злиттям.
            setKey(key, 0L)
            setCategoryHash(category, "")
            return 0L
        }
        return normalized
    }

    fun setCategoryTimestamp(category: SyncCategory, ts: Long) {
        setKey("CLOUDSYNC_TS_${category.key}", SyncTime.toEpochSeconds(ts))
    }

    fun getCategoryHash(category: SyncCategory): String =
        getKey("CLOUDSYNC_HASH_${category.key}") ?: ""

    fun setCategoryHash(category: SyncCategory, hash: String) {
        setKey("CLOUDSYNC_HASH_${category.key}", hash)
    }

    fun getCategorySyncedKeys(category: SyncCategory): Set<String> =
        getKey<Array<String>>("CLOUDSYNC_SYNCED_KEYS_${category.key}")?.toSet() ?: emptySet()

    fun setCategorySyncedKeys(category: SyncCategory, keys: Set<String>) {
        setKey("CLOUDSYNC_SYNCED_KEYS_${category.key}", keys.toTypedArray())
    }

    fun deleteAllData() {
        setKey("CLOUDSYNC_CREDS", null)
        setKey("CLOUDSYNC_V2_MIGRATED", null)
        SyncCategory.entries.forEach { cat ->
            setKey("CLOUDSYNC_TS_${cat.key}", null)
            setKey("CLOUDSYNC_HASH_${cat.key}", null)
            setKey("CLOUDSYNC_SYNCED_KEYS_${cat.key}", null)
        }
    }
}
