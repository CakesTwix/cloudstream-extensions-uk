package com.lagradost.sync

import com.google.gson.annotations.SerializedName

data class SyncCreds(
    @SerializedName("serverUrl") var serverUrl: String? = null,
    @SerializedName("token") var token: String? = null,
    @SerializedName("deviceName") var deviceName: String? = null,
    @SerializedName("deviceId") var deviceId: String? = null,
    @SerializedName("backupDevice") var backupDevice: Boolean = false,
    @SerializedName("restoreDevice") var restoreDevice: Boolean = false,
    @SerializedName("backupExtensions") var backupExtensions: Boolean = true,
    @SerializedName("backupBookmarks") var backupBookmarks: Boolean = true,
    @SerializedName("backupResumeWatching") var backupResumeWatching: Boolean = true,
    @SerializedName("backupSearchHistory") var backupSearchHistory: Boolean = true,
    @SerializedName("backupSettings") var backupSettings: Boolean = true,
    @SerializedName("restoreExtensions") var restoreExtensions: Boolean = true,
    @SerializedName("restoreBookmarks") var restoreBookmarks: Boolean = true,
    @SerializedName("restoreResumeWatching") var restoreResumeWatching: Boolean = true,
    @SerializedName("restoreSearchHistory") var restoreSearchHistory: Boolean = true,
    @SerializedName("restoreSettings") var restoreSettings: Boolean = true,
) {
    val baseUrl: String
        get() {
            val u = serverUrl ?: return ""
            return if (u.endsWith("/")) u else "$u/"
        }

    fun isLoggedIn(): Boolean = !token.isNullOrEmpty() && !serverUrl.isNullOrEmpty()

    fun isBackupEnabled(category: SyncCategory): Boolean = when (category) {
        SyncCategory.EXTENSIONS -> backupExtensions
        SyncCategory.BOOKMARKS -> backupBookmarks
        SyncCategory.RESUME_WATCHING -> backupResumeWatching
        SyncCategory.SEARCH_HISTORY -> backupSearchHistory
        SyncCategory.SETTINGS -> backupSettings
    }

    fun isRestoreEnabled(category: SyncCategory): Boolean = when (category) {
        SyncCategory.EXTENSIONS -> restoreExtensions
        SyncCategory.BOOKMARKS -> restoreBookmarks
        SyncCategory.RESUME_WATCHING -> restoreResumeWatching
        SyncCategory.SEARCH_HISTORY -> restoreSearchHistory
        SyncCategory.SETTINGS -> restoreSettings
    }
}

enum class SyncCategory(val key: String) {
    EXTENSIONS("extensions"),
    SETTINGS("settings"),
    BOOKMARKS("bookmarks"),
    RESUME_WATCHING("resume_watching"),
    SEARCH_HISTORY("search_history");
}

data class SyncCategoryMeta(
    @SerializedName("ts") val ts: Double = 0.0,
    @SerializedName("hash") val hash: String = "",
    @SerializedName("device") val device: String = "",
)

data class SyncManifest(
    @SerializedName("extensions") val extensions: SyncCategoryMeta? = null,
    @SerializedName("settings") val settings: SyncCategoryMeta? = null,
    @SerializedName("bookmarks") val bookmarks: SyncCategoryMeta? = null,
    @SerializedName("resume_watching") val resumeWatching: SyncCategoryMeta? = null,
    @SerializedName("search_history") val searchHistory: SyncCategoryMeta? = null,
    @SerializedName("version") val version: Int = 2,
) {
    fun getMeta(category: SyncCategory): SyncCategoryMeta? = when (category) {
        SyncCategory.EXTENSIONS -> extensions
        SyncCategory.SETTINGS -> settings
        SyncCategory.BOOKMARKS -> bookmarks
        SyncCategory.RESUME_WATCHING -> resumeWatching
        SyncCategory.SEARCH_HISTORY -> searchHistory
    }
}

data class SyncCategoryPayload(
    @SerializedName("data") val data: String = "",
    @SerializedName("ts") val ts: Double = 0.0,
    @SerializedName("device") val device: String = "",
)

data class CloudSyncDevice(
    @SerializedName("name") val name: String = "",
    @SerializedName("deviceId") val deviceId: String = "",
    @SerializedName("lastActive") val lastActive: Double = 0.0,
)

data class BackupVars(
    @SerializedName("_Bool") val bool: Map<String, Boolean>?,
    @SerializedName("_Int") val int: Map<String, Int>?,
    @SerializedName("_String") val string: Map<String, String>?,
    @SerializedName("_Float") val float: Map<String, Float>?,
    @SerializedName("_Long") val long: Map<String, Long>?,
    @SerializedName("_StringSet") val stringSet: Map<String, Set<String>?>?,
)

data class BackupFile(
    @SerializedName("datastore") val datastore: BackupVars,
    @SerializedName("settings") val settings: BackupVars,
)
