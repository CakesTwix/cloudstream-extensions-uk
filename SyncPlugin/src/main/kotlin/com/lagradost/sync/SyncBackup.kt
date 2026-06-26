package com.lagradost.sync

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.security.MessageDigest

private val syncGson: Gson = GsonBuilder().serializeNulls().create()

private fun sortJsonElement(elem: JsonElement): JsonElement {
    if (elem.isJsonObject) {
        val obj = elem.asJsonObject
        val sorted = JsonObject()
        obj.keySet().sorted().forEach { key ->
            sorted.add(key, sortJsonElement(obj.get(key)))
        }
        return sorted
    }
    return elem
}

fun BackupFile.toJsonSorted(): String = syncGson.toJson(sortJsonElement(syncGson.toJsonTree(this)))

object SyncBackup {
    private const val TAG = "CloudSync"

    val nonTransferableKeys = listOf(
        "anilist_unixtime", "anilist_token", "anilist_user", "anilist_cached_list",
        "anilist_accounts", "anilist_active",
        "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_token",
        "mal_accounts", "mal_active",
        "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time",
        "simkl_accounts", "simkl_active", "SIMKL_API_CACHE", "ANIWAVE_SIMKL_SYNC",
        "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
        "subdl_user", "subdl_accounts", "subdl_active",
        "biometric_key", "nginx_user",
        "download_path_key", "download_path_key_visual", "backup_path_key", "backup_dir_path_key",
        "cs3-votes", "last_sync_api", "last_click_action", "last_opened_id", "library_folder",
        "result_resume_watching_migrated", "jsdelivr_proxy_key",
        "device_id", "sync_token", "sync_project_num", "sync_project_id", "sync_item_id",
        "sync_device_id", "restore_device", "backup_device",
        "download_info", "download_resume", "download_q_resume", "download_episode_cache",
        "prerelease_update",
        "data_store_helper/account_key_index", "VERSION_NAME", "FILES_TO_DELETE_KEY",
        "HAS_DONE_SETUP", "PLUGINS_KEY",
        // This plugin's own state — must never be synced.
        "cloudsync_creds", "cloudsync_v2_migrated", "cloudsync_ts_", "cloudsync_hash_",
        "cloudsync_synced_keys_",
        "used_fstream_providers_v3", "fstream_version",
        "home_api_used", "home_api", "user_selected_homepage_api",
        "last_sync_api_key", "home_pref_homepage", "library_sorting_mode",
        "results_sorting_mode", "library_folder", "viewpager_item_key",
    )

    private fun String.isTransferable(): Boolean {
        val lower = this.lowercase()
        return !nonTransferableKeys.any { lower.contains(it.lowercase()) }
    }

    fun classifyKey(key: String): SyncCategory? {
        val lowerKey = key.lowercase()
        if (!key.isTransferable()) return null

        if (lowerKey.contains("result_favorites_state_data") || lowerKey.contains("result_watch_state")) {
            return SyncCategory.BOOKMARKS
        }
        if (lowerKey.contains("result_resume_watching") || lowerKey.contains("video_pos_dur") ||
            lowerKey.contains("download_header_cache") || lowerKey.contains("result_season") ||
            lowerKey.contains("result_dub") || lowerKey.contains("result_episode")
        ) {
            return SyncCategory.RESUME_WATCHING
        }
        if (lowerKey.contains("search_history")) {
            return SyncCategory.SEARCH_HISTORY
        }
        if (lowerKey.contains("plugins_key")) return null
        if (lowerKey.contains("plugins_repositories") ||
            lowerKey.contains("repositories")
        ) {
            return SyncCategory.EXTENSIONS
        }
        return SyncCategory.SETTINGS
    }

    fun isKeyBackupEnabled(category: SyncCategory, creds: SyncCreds): Boolean =
        creds.isBackupEnabled(category)

    fun isKeyRestoreEnabled(category: SyncCategory, creds: SyncCreds): Boolean =
        creds.isRestoreEnabled(category)

    fun computeHash(data: String): String =
        MessageDigest.getInstance("MD5").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    fun getBackupForCategory(
        context: Context,
        category: SyncCategory,
        resumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null,
    ): BackupFile? {
        val creds = SyncStorage.creds ?: SyncCreds()
        val allData = context.getSharedPrefs().all.filter { entry ->
            entry.key.isTransferable() && classifyKey(entry.key) == category &&
                isResumeWatchingRelevant(entry.key, resumeWatching) &&
                isKeyBackupEnabled(category, creds)
        }
        val allSettings = context.getDefaultSharedPrefs().all.filter { entry ->
            entry.key.isTransferable() && classifyKey(entry.key) == category &&
                isResumeWatchingRelevant(entry.key, resumeWatching) &&
                isKeyBackupEnabled(category, creds)
        }
        if (allData.isEmpty() && allSettings.isEmpty()) {
            Log.d(TAG, "getBackupForCategory(${category.key}): no keys found")
            return null
        }
        Log.d(TAG, "getBackupForCategory(${category.key}): datastore=${allData.size} settings=${allSettings.size}")
        return BackupFile(
            datastore = buildBackupVars(allData),
            settings = buildBackupVars(allSettings),
        )
    }

    private fun isResumeWatchingRelevant(
        key: String,
        resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?,
    ): Boolean {
        if (resumeWatching == null) return true
        val lowerKey = key.lowercase()
        if (lowerKey.contains("download_header_cache")) {
            val id = key.split("/").getOrNull(1)?.toIntOrNull()
            return id?.let { intId ->
                resumeWatching.any { if (it.parentId != null) it.parentId == intId else it.id == intId }
            } ?: false
        } else if (lowerKey.contains("video_pos_dur")) {
            val id = key.split("/").getOrNull(2)?.toIntOrNull()
            return id?.let { intId -> resumeWatching.any { it.id == intId } } ?: false
        } else if (lowerKey.contains("result_season") || lowerKey.contains("result_dub") ||
            lowerKey.contains("result_episode")
        ) {
            val id = key.split("/").getOrNull(2)?.toIntOrNull()
            return id?.let { intId -> resumeWatching.any { it.parentId == intId } } ?: false
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildBackupVars(data: Map<String, *>): BackupVars = BackupVars(
        bool = data.filter { it.value is Boolean } as? Map<String, Boolean>,
        int = data.filter { it.value is Int } as? Map<String, Int>,
        string = data.filter { it.value is String } as? Map<String, String>,
        float = data.filter { it.value is Float } as? Map<String, Float>,
        long = data.filter { it.value is Long } as? Map<String, Long>,
        stringSet = data.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>,
    )

    // ------------------------------------------------------------------- //
    // Restore
    // ------------------------------------------------------------------- //

    fun restoreCategory(context: Context, category: SyncCategory, backupFile: BackupFile) {
        restoreBackupVars(context, category, backupFile.datastore, isSettings = false)
        restoreBackupVars(context, category, backupFile.settings, isSettings = true)
        try {
            val keys = getBackupFileKeys(backupFile)
            SyncStorage.setCategorySyncedKeys(category, keys)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save synced keys on restore: ${e.message}")
        }
    }

    private fun restoreBackupVars(
        context: Context,
        category: SyncCategory,
        vars: BackupVars,
        isSettings: Boolean,
    ) {
        val creds = SyncStorage.creds ?: SyncCreds()
        val prefs = if (isSettings) context.getDefaultSharedPrefs() else context.getSharedPrefs()
        val editor = editor(context, isSettings)

        if (isDynamicCategory(category)) {
            val incomingKeys = mutableSetOf<String>()
            vars.bool?.keys?.let { incomingKeys.addAll(it) }
            vars.int?.keys?.let { incomingKeys.addAll(it) }
            vars.float?.keys?.let { incomingKeys.addAll(it) }
            vars.long?.keys?.let { incomingKeys.addAll(it) }
            vars.stringSet?.keys?.let { incomingKeys.addAll(it) }
            vars.string?.keys?.let { incomingKeys.addAll(it) }

            prefs.all.forEach { (k, _) ->
                if (k.isTransferable() && classifyKey(k) == category &&
                    isKeyRestoreEnabled(category, creds) && !incomingKeys.contains(k)
                ) {
                    editor.editor.remove(k)
                }
            }
        }

        vars.bool?.forEach { (k, v) ->
            if (k.isTransferable() && isKeyRestoreEnabled(category, creds)) editor.setKeyRaw(k, v)
        }
        vars.int?.forEach { (k, v) ->
            if (k.isTransferable() && isKeyRestoreEnabled(category, creds)) editor.setKeyRaw(k, v)
        }
        vars.float?.forEach { (k, v) ->
            if (k.isTransferable() && isKeyRestoreEnabled(category, creds)) editor.setKeyRaw(k, v)
        }
        vars.long?.forEach { (k, v) ->
            if (k.isTransferable() && isKeyRestoreEnabled(category, creds)) editor.setKeyRaw(k, v)
        }
        vars.stringSet?.forEach { (k, v) ->
            if (k.isTransferable() && isKeyRestoreEnabled(category, creds)) editor.setKeyRaw(k, v)
        }
        vars.string?.forEach { (k, v) ->
            if (k.isTransferable() && isKeyRestoreEnabled(category, creds)) {
                val localVal = prefs.getString(k, null)
                val cloudTs = extractTimestamp(v)
                val localTs = extractTimestamp(localVal)
                if (localVal == null || (cloudTs == 0L && localTs == 0L) || cloudTs > localTs) {
                    editor.setKeyRaw(k, v)
                }
            }
        }
        editor.apply()
    }

    // ------------------------------------------------------------------- //
    // Merge
    // ------------------------------------------------------------------- //

    fun mergeBackupFiles(
        local: BackupFile?,
        cloud: BackupFile?,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
        isLocallyDirty: Boolean = false,
    ): BackupFile? {
        if (local == null) return cloud
        if (cloud == null) return local
        val sampleKey = local.datastore.string?.keys?.firstOrNull()
            ?: local.settings.string?.keys?.firstOrNull()
            ?: local.datastore.bool?.keys?.firstOrNull()
            ?: local.settings.bool?.keys?.firstOrNull()
            ?: cloud.datastore.string?.keys?.firstOrNull()
            ?: cloud.settings.string?.keys?.firstOrNull()
        val category = sampleKey?.let { classifyKey(it) } ?: SyncCategory.SETTINGS
        return BackupFile(
            datastore = mergeBackupVars(local.datastore, cloud.datastore, localCategoryTs, cloudPayloadTs, category, isLocallyDirty),
            settings = mergeBackupVars(local.settings, cloud.settings, localCategoryTs, cloudPayloadTs, category, isLocallyDirty),
        )
    }

    private fun mergeBackupVars(
        local: BackupVars,
        cloud: BackupVars,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
        category: SyncCategory,
        isLocallyDirty: Boolean,
    ): BackupVars = BackupVars(
        bool = mergeCategoryMap(category, local.bool, cloud.bool, localCategoryTs, cloudPayloadTs, local.string, cloud.string, isLocallyDirty),
        int = mergeCategoryMap(category, local.int, cloud.int, localCategoryTs, cloudPayloadTs, local.string, cloud.string, isLocallyDirty),
        float = mergeCategoryMap(category, local.float, cloud.float, localCategoryTs, cloudPayloadTs, local.string, cloud.string, isLocallyDirty),
        long = mergeCategoryMap(category, local.long, cloud.long, localCategoryTs, cloudPayloadTs, local.string, cloud.string, isLocallyDirty),
        string = mergeCategoryMap(category, local.string, cloud.string, localCategoryTs, cloudPayloadTs, local.string, cloud.string, isLocallyDirty),
        stringSet = mergeCategoryMap(category, local.stringSet, cloud.stringSet, localCategoryTs, cloudPayloadTs, local.string, cloud.string, isLocallyDirty),
    )

    private fun <T> mergeCategoryMap(
        category: SyncCategory,
        local: Map<String, T>?,
        cloud: Map<String, T>?,
        localCategoryTs: Long,
        cloudPayloadTs: Long,
        localStrings: Map<String, String>?,
        cloudStrings: Map<String, String>?,
        isLocallyDirty: Boolean = false,
    ): Map<String, T>? {
        if (local == null && cloud == null) return null

        val lastSyncedKeys = if (isDynamicCategory(category)) {
            SyncStorage.getCategorySyncedKeys(category)
        } else emptySet()

        if (local == null) {
            val nonNullCloud = cloud ?: return null
            if (localCategoryTs == 0L || !isDynamicCategory(category) || lastSyncedKeys.isEmpty()) return nonNullCloud
            return nonNullCloud.filter { (key, _) ->
                val inLastSync = lastSyncedKeys.contains(key)
                if (!inLastSync) true else getSpecificKeyTimestamp(key, category, cloudStrings) > localCategoryTs
            }
        }
        if (cloud == null) {
            val nonNullLocal = local
            if (localCategoryTs == 0L || !isDynamicCategory(category) || lastSyncedKeys.isEmpty()) return nonNullLocal
            return nonNullLocal.filter { (key, _) ->
                val inLastSync = lastSyncedKeys.contains(key)
                if (!inLastSync) true else getSpecificKeyTimestamp(key, category, localStrings) > localCategoryTs
            }
        }

        val merged = HashMap<String, T>()
        for ((key, localVal) in local) {
            val cloudVal = cloud[key]
            if (cloudVal != null) {
                val localTs = getSpecificKeyTimestamp(key, category, localStrings)
                val cloudTs = getSpecificKeyTimestamp(key, category, cloudStrings)
                if (localTs > 0L || cloudTs > 0L) {
                    merged[key] = if (cloudTs > localTs) cloudVal else localVal
                } else {
                    merged[key] = if (cloudPayloadTs > localCategoryTs && !isLocallyDirty) cloudVal else localVal
                }
            } else {
                val itemTs = getSpecificKeyTimestamp(key, category, localStrings)
                if (itemTs > 0L) {
                    if (localCategoryTs == 0L || lastSyncedKeys.isEmpty()) {
                        merged[key] = localVal
                    } else {
                        val inLastSync = lastSyncedKeys.contains(key)
                        if (!inLastSync) merged[key] = localVal
                        else if (itemTs > localCategoryTs) merged[key] = localVal
                    }
                } else {
                    if (localCategoryTs == 0L) {
                        merged[key] = localVal
                    } else if (cloudPayloadTs > localCategoryTs && !isLocallyDirty && lastSyncedKeys.contains(key)) {
                        // was deleted on cloud
                    } else {
                        merged[key] = localVal
                    }
                }
            }
        }
        for ((key, cloudVal) in cloud) {
            if (!local.containsKey(key)) {
                val itemTs = getSpecificKeyTimestamp(key, category, cloudStrings)
                if (itemTs > 0L) {
                    if (localCategoryTs == 0L || lastSyncedKeys.isEmpty()) {
                        merged[key] = cloudVal
                    } else {
                        val inLastSync = lastSyncedKeys.contains(key)
                        if (!inLastSync) merged[key] = cloudVal
                        else if (itemTs > localCategoryTs) merged[key] = cloudVal
                    }
                } else {
                    if (localCategoryTs == 0L) {
                        merged[key] = cloudVal
                    } else if (cloudPayloadTs > localCategoryTs && !isLocallyDirty) {
                        merged[key] = cloudVal
                    }
                }
            }
        }
        return merged
    }

    // ------------------------------------------------------------------- //
    // Utilities
    // ------------------------------------------------------------------- //

    fun editor(context: Context, isEditingAppSettings: Boolean = false): SyncEditor {
        val e: SharedPreferences.Editor =
            if (isEditingAppSettings) context.getDefaultSharedPrefs().edit()
            else context.getSharedPrefs().edit()
        return SyncEditor(e)
    }

    class SyncEditor(val editor: SharedPreferences.Editor) {
        fun <T> setKeyRaw(path: String, value: T) {
            @Suppress("UNCHECKED_CAST")
            if (isStringSet(value)) {
                editor.putStringSet(path, value as Set<String>)
            } else {
                when (value) {
                    is Boolean -> editor.putBoolean(path, value)
                    is Int -> editor.putInt(path, value)
                    is String -> editor.putString(path, value)
                    is Float -> editor.putFloat(path, value)
                    is Long -> editor.putLong(path, value)
                }
            }
        }

        private fun isStringSet(value: Any?): Boolean =
            (value is Set<*>) && value.filterIsInstance<String>().size == value.size

        fun apply() = editor.apply()
    }

    fun getBackupFileKeys(backupFile: BackupFile): Set<String> {
        val keys = mutableSetOf<String>()
        backupFile.datastore.bool?.keys?.let { keys.addAll(it) }
        backupFile.datastore.int?.keys?.let { keys.addAll(it) }
        backupFile.datastore.float?.keys?.let { keys.addAll(it) }
        backupFile.datastore.long?.keys?.let { keys.addAll(it) }
        backupFile.datastore.stringSet?.keys?.let { keys.addAll(it) }
        backupFile.datastore.string?.keys?.let { keys.addAll(it) }
        backupFile.settings.bool?.keys?.let { keys.addAll(it) }
        backupFile.settings.int?.keys?.let { keys.addAll(it) }
        backupFile.settings.float?.keys?.let { keys.addAll(it) }
        backupFile.settings.long?.keys?.let { keys.addAll(it) }
        backupFile.settings.stringSet?.keys?.let { keys.addAll(it) }
        backupFile.settings.string?.keys?.let { keys.addAll(it) }
        return keys
    }

    fun isDynamicCategory(category: SyncCategory): Boolean =
        category == SyncCategory.BOOKMARKS ||
            category == SyncCategory.RESUME_WATCHING ||
            category == SyncCategory.SEARCH_HISTORY

    private fun extractIdFromKey(key: String): Int? {
        val lower = key.lowercase()
        return when {
            lower.contains("download_header_cache") -> key.split("/").getOrNull(1)?.toIntOrNull()
            lower.contains("video_pos_dur") -> key.split("/").getOrNull(2)?.toIntOrNull()
            lower.contains("result_season") -> key.split("/").getOrNull(2)?.toIntOrNull()
            lower.contains("result_dub") -> key.split("/").getOrNull(2)?.toIntOrNull()
            lower.contains("result_episode") -> key.split("/").getOrNull(2)?.toIntOrNull()
            lower.contains("result_favorites_state_data") -> key.split("/").getOrNull(1)?.toIntOrNull()
            lower.contains("result_watch_state") -> key.split("/").getOrNull(1)?.toIntOrNull()
            lower.contains("result_resume_watching") -> key.split("/").getOrNull(1)?.toIntOrNull()
            else -> null
        }
    }

    private fun getSpecificKeyTimestamp(
        key: String,
        category: SyncCategory,
        stringMap: Map<String, String>?,
    ): Long {
        if (stringMap == null) return 0L
        stringMap[key]?.let { extractTimestamp(it)?.takeIf { ts -> ts > 0L } }?.let { return it }
        val id = extractIdFromKey(key) ?: return 0L
        val relatedKeys = when (category) {
            SyncCategory.BOOKMARKS -> listOf("result_favorites_state_data/$id")
            SyncCategory.RESUME_WATCHING -> listOf("result_resume_watching/$id", "video_pos_dur/$id")
            else -> emptyList()
        }
        for (relKey in relatedKeys) {
            val ts = extractTimestamp(stringMap[relKey])
            if (ts > 0L) return ts
        }
        if (category == SyncCategory.RESUME_WATCHING) {
            stringMap.forEach { (k, v) ->
                if (k.contains("video_pos_dur") && k.contains("/$id")) {
                    val ts = extractTimestamp(v)
                    if (ts > 0L) return ts
                }
            }
        }
        return 0L
    }

    private fun extractTimestamp(json: String?): Long {
        if (json == null) return 0L
        return try {
            "\"updateTime\":\\s*(\\d+)".toRegex().find(json)?.let { it.groupValues[1].toLong() }
                ?: "\"latestUpdatedTime\":\\s*(\\d+)".toRegex().find(json)?.let { it.groupValues[1].toLong() }
                ?: "\"searchedAt\":\\s*(\\d+)".toRegex().find(json)?.let { it.groupValues[1].toLong() }
                ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun BackupVars.isEmpty(): Boolean =
        bool.isNullOrEmpty() && int.isNullOrEmpty() && string.isNullOrEmpty() &&
            float.isNullOrEmpty() && long.isNullOrEmpty() && stringSet.isNullOrEmpty()
}
