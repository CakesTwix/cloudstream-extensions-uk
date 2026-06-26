package com.lagradost.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@CloudstreamPlugin
class CloudSyncPlugin : Plugin() {
    var activity: AppCompatActivity? = null

    private var lifecycleCallbacks: android.app.Application.ActivityLifecycleCallbacks? = null
    private var registeredApp: android.app.Application? = null

    private var pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dirtyCategories = mutableSetOf<SyncCategory>()
    private val dirtyCategoriesLock = Any()

    @Volatile
    private var isRestoring = false
    @Volatile
    private var restoringUntil = 0L
    private val RESTORE_GUARD_MS = 5_000L

    private val pullMutex = Mutex()
    private val syncMutex = Mutex()

    private var dataPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var defaultPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var pushJob: Job? = null
    private var pollJob: Job? = null

    companion object {
        private const val TAG = "CloudSync"
        private const val PUSH_DEBOUNCE_MS = 2_000L
        private const val POLL_INTERVAL_MS = 30_000L
    }

    // --- Category dirty tracking ---

    private fun markDirty(category: SyncCategory) {
        synchronized(dirtyCategoriesLock) { dirtyCategories.add(category) }
        scheduleDebouncedPush()
    }

    private fun consumeDirtyCategories(): Set<SyncCategory> {
        synchronized(dirtyCategoriesLock) {
            val copy = dirtyCategories.toSet()
            dirtyCategories.clear()
            return copy
        }
    }

    // --- Debounced push ---

    private fun scheduleDebouncedPush() {
        val ctx = CloudStreamApp.context ?: activity ?: return
        val creds = SyncStorage.creds ?: return
        if (!creds.isLoggedIn() || !creds.backupDevice) return

        pushJob?.cancel()
        pushJob = pluginScope.launch {
            delay(PUSH_DEBOUNCE_MS.milliseconds)
            syncMutex.withLock { mergeAndSyncAllCategories(ctx) }
        }
    }

    // --- Polling (replaces SSE) ---

    private fun startPolling(context: Context) {
        pollJob?.cancel()
        pollJob = pluginScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    val creds = SyncStorage.creds
                    if (creds != null && creds.isLoggedIn() && creds.restoreDevice) {
                        syncMutex.withLock { pullChangedCategories(context) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "poll error: ${e.message}")
                }
            }
        }
    }

    // --- Pull ---

    private suspend fun pullChangedCategories(context: Context, force: Boolean = false): Boolean {
        val appContext = context.applicationContext
        val creds = SyncStorage.creds ?: return false
        if (!creds.isLoggedIn() || !creds.restoreDevice) return false
        if (!pullMutex.tryLock()) return false

        try {
            val manifest = SyncNetwork.fetchManifest() ?: run {
                Log.d(TAG, "pullChangedCategories: manifest fetch failed")
                return false
            }
            Log.d(TAG, "pullChangedCategories: manifest ext=${manifest.extensions?.ts} set=${manifest.settings?.ts} bk=${manifest.bookmarks?.ts} rw=${manifest.resumeWatching?.ts} sh=${manifest.searchHistory?.ts}")
            val categoriesToFetch = mutableListOf<Pair<SyncCategory, SyncCategoryMeta>>()
            for (category in SyncCategory.entries) {
                if (!creds.isRestoreEnabled(category)) continue
                val cloudMeta = manifest.getMeta(category) ?: continue
                val localTs = SyncStorage.getCategoryTimestamp(category)
                if (!force && cloudMeta.ts.toLong() <= localTs) continue
                categoriesToFetch.add(category to cloudMeta)
            }
            Log.d(TAG, "pullChangedCategories: will fetch ${categoriesToFetch.size} categories: ${categoriesToFetch.map { it.first.key }}")
            if (categoriesToFetch.isEmpty()) return false

            var restoredAny = false
            isRestoring = true
            try {
                for ((category, cloudMeta) in categoriesToFetch) {
                    try {
                        val payload = SyncNetwork.fetchCategory(category) ?: continue
                        if (payload.data.isBlank()) continue
                        val backupFile = Gson().fromJson(payload.data, BackupFile::class.java)
                        restoreAndReload(appContext, category, backupFile)
                        SyncStorage.setCategoryTimestamp(category, cloudMeta.ts.toLong())
                        SyncStorage.setCategoryHash(category, cloudMeta.hash)
                        restoredAny = true
                        Log.d(TAG, "Restored ${category.key} from ${cloudMeta.device}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error restoring ${category.key}: ${e.message}")
                    }
                }
            } finally {
                restoringUntil = System.currentTimeMillis() + RESTORE_GUARD_MS
                withContext(Dispatchers.Main) { isRestoring = false }
            }
            if (restoredAny) reload()
            return restoredAny
        } finally {
            pullMutex.unlock()
        }
    }

    // --- Reload UI ---

    private fun reload() {
        pluginScope.launch(Dispatchers.Main) {
            val act = activity
            if (act == null || act.isFinishing || act.isDestroyed) return@launch
            try {
                MainActivity.bookmarksUpdatedEvent.invoke(true)
                MainActivity.reloadLibraryEvent.invoke(true)
            } catch (e: Throwable) {
                Log.e(TAG, "reload events failed: ${e.message}")
            }
        }
    }

    private suspend fun restoreAndReload(
        context: Context,
        category: SyncCategory,
        backupFile: BackupFile,
    ) {
        when (category) {
            SyncCategory.EXTENSIONS -> {
                SyncBackup.restoreCategory(context, category, backupFile)
                withContext(Dispatchers.Main) {
                    try { MainActivity.afterPluginsLoadedEvent.invoke(true) } catch (_: Throwable) {}
                }
            }
            SyncCategory.BOOKMARKS -> {
                SyncBackup.restoreCategory(context, category, backupFile)
                withContext(Dispatchers.Main) {
                    try { MainActivity.bookmarksUpdatedEvent(true) } catch (_: Throwable) {}
                }
            }
            SyncCategory.SETTINGS -> {
                SyncBackup.restoreCategory(context, category, backupFile)
                withContext(Dispatchers.Main) {
                    try { showToast("Налаштування синхронізовано. Перезапустіть застосунок.") } catch (_: Throwable) {}
                }
            }
            else -> SyncBackup.restoreCategory(context, category, backupFile)
        }
    }

    // --- Push all categories ---

    suspend fun pushAllCategories(context: Context) {
        val creds = SyncStorage.creds ?: return
        if (!creds.isLoggedIn()) return
        val resumeWatching = try { getResumeWatching() } catch (e: Exception) { Log.e(TAG, "getResumeWatching failed: ${e.message}"); null }
        val categoryData = mutableMapOf<SyncCategory, Pair<String, String>>()
        for (category in SyncCategory.entries) {
            if (!creds.isBackupEnabled(category)) continue
            try {
                val backup = SyncBackup.getBackupForCategory(context, category, resumeWatching)
                if (backup != null) {
                    val data = backup.toJsonSorted()
                    val hash = SyncBackup.computeHash(data)
                    categoryData[category] = data to hash
                }
            } catch (e: Exception) {
                Log.e(TAG, "prepare ${category.key}: ${e.message}")
            }
        }
        Log.d(TAG, "pushAllCategories: collected ${categoryData.size} categories: ${categoryData.keys.map { it.key }}")
        if (categoryData.isNotEmpty()) {
            val pushed = SyncNetwork.pushCategories(categoryData)
            Log.d(TAG, "Pushed ${pushed.size}/${categoryData.size} categories: ${pushed.map { it.key }}")
            updateLocalState(pushed, categoryData, System.currentTimeMillis())
        }
    }

    private fun updateLocalState(
        pushed: Set<SyncCategory>,
        categoryData: Map<SyncCategory, Pair<String, String>>,
        now: Long,
    ) {
        for (category in pushed) {
            val hash = categoryData[category]?.second ?: ""
            SyncStorage.setCategoryTimestamp(category, now)
            SyncStorage.setCategoryHash(category, hash)
            try {
                val data = categoryData[category]?.first ?: ""
                val backupFile = Gson().fromJson(data, BackupFile::class.java)
                SyncStorage.setCategorySyncedKeys(category, SyncBackup.getBackupFileKeys(backupFile))
            } catch (_: Exception) {
            }
        }
    }

    // --- Merge + sync (push & pull together) ---

    suspend fun mergeAndSyncAllCategories(context: Context) {
        val appContext = context.applicationContext
        val creds = SyncStorage.creds ?: return
        if (!creds.isLoggedIn()) return

        val currentDirtyCategories = dirtyCategories.toSet()
        consumeDirtyCategories()

        val manifest = SyncNetwork.fetchManifest()
        val resumeWatching = try { getResumeWatching() } catch (e: Exception) { Log.e(TAG, "getResumeWatching failed: ${e.message}"); null }
        val enabledCategories = SyncCategory.entries.filter {
            creds.isBackupEnabled(it) || creds.isRestoreEnabled(it)
        }

        val cloudPayloads = coroutineScope {
            enabledCategories.map { category ->
                async(Dispatchers.IO) {
                    try {
                        val cloudMeta = manifest?.getMeta(category)
                        val localHash = SyncStorage.getCategoryHash(category)
                        if (cloudMeta != null && cloudMeta.hash.isNotEmpty() && cloudMeta.hash == localHash) {
                            category to null
                        } else {
                            category to SyncNetwork.fetchCategory(category)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "fetch ${category.key}: ${e.message}")
                        category to null
                    }
                }
            }.awaitAll().toMap()
        }

        val categoriesToPush = mutableMapOf<SyncCategory, Pair<String, String>>()
        var restoredAny = false

        for (category in enabledCategories) {
            val isBackup = creds.isBackupEnabled(category)
            val isRestore = creds.isRestoreEnabled(category)
            if (!isBackup && !isRestore) continue
            try {
                val cloudPayload = cloudPayloads[category]
                val cloudBackup =
                    if (cloudPayload != null && cloudPayload.data.isNotBlank()) {
                        try { Gson().fromJson(cloudPayload.data, BackupFile::class.java) } catch (_: Exception) { null }
                    } else null
                val localBackup = SyncBackup.getBackupForCategory(appContext, category, resumeWatching)
                val isLocalEmpty = localBackup == null || SyncBackup.run { localBackup.datastore.isEmpty() && localBackup.settings.isEmpty() }
                val isCloudEmpty = cloudBackup == null || SyncBackup.run { cloudBackup.datastore.isEmpty() && cloudBackup.settings.isEmpty() }

                if (isLocalEmpty) {
                    if (!isCloudEmpty && isRestore) {
                        val cloudMeta = manifest?.getMeta(category)
                        val cloudHash = cloudMeta?.hash ?: ""
                        if (cloudHash != SyncStorage.getCategoryHash(category) && cloudBackup != null) {
                            isRestoring = true
                            try {
                                restoreAndReload(appContext, category, cloudBackup)
                                restoredAny = true
                            } finally {
                                restoringUntil = System.currentTimeMillis() + RESTORE_GUARD_MS
                                withContext(Dispatchers.Main) { isRestoring = false }
                            }
                            if (cloudMeta != null) {
                                SyncStorage.setCategoryTimestamp(category, cloudMeta.ts.toLong())
                                SyncStorage.setCategoryHash(category, cloudMeta.hash)
                            }
                        }
                    }
                } else if (isCloudEmpty) {
                    if (isBackup) {
                        val data = localBackup.toJsonSorted()
                        val hash = SyncBackup.computeHash(data)
                        if (hash != (manifest?.getMeta(category)?.hash ?: "")) {
                            categoriesToPush[category] = data to hash
                        }
                    }
                } else {
                    val localCategoryTs = SyncStorage.getCategoryTimestamp(category)
                    val cloudPayloadTs = (cloudPayload?.ts ?: 0.0).toLong()
                    val isLocallyDirty = currentDirtyCategories.contains(category)
                    val mergedBackup = SyncBackup.mergeBackupFiles(localBackup, cloudBackup, localCategoryTs, cloudPayloadTs, isLocallyDirty)
                    if (mergedBackup != null) {
                        val data = mergedBackup.toJsonSorted()
                        val hash = SyncBackup.computeHash(data)
                        val liveLocalHash = SyncBackup.computeHash(localBackup.toJsonSorted())
                        val cloudHash = manifest?.getMeta(category)?.hash ?: ""

                        if (hash != liveLocalHash && isRestore) {
                            isRestoring = true
                            try {
                                restoreAndReload(appContext, category, mergedBackup)
                                restoredAny = true
                            } finally {
                                restoringUntil = System.currentTimeMillis() + RESTORE_GUARD_MS
                                withContext(Dispatchers.Main) { isRestoring = false }
                            }
                            SyncStorage.setCategoryHash(category, hash)
                            manifest?.getMeta(category)?.let {
                                SyncStorage.setCategoryTimestamp(category, it.ts.toLong())
                            }
                            SyncStorage.setCategorySyncedKeys(category, SyncBackup.getBackupFileKeys(mergedBackup))
                        }
                        if (hash != cloudHash && isBackup) {
                            categoriesToPush[category] = data to hash
                        } else if (cloudHash.isNotEmpty() && SyncStorage.getCategoryHash(category) != cloudHash) {
                            SyncStorage.setCategoryHash(category, cloudHash)
                            manifest?.getMeta(category)?.let { SyncStorage.setCategoryTimestamp(category, it.ts.toLong()) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "merge ${category.key}: ${e.message}")
            }
        }

        if (categoriesToPush.isNotEmpty()) {
            val pushed = SyncNetwork.pushCategories(categoriesToPush)
            Log.d(TAG, "Batch pushed ${pushed.size}/${categoriesToPush.size}")
            updateLocalState(pushed, categoriesToPush, System.currentTimeMillis())
            val failed = categoriesToPush.keys - pushed
            if (failed.isNotEmpty()) {
                synchronized(dirtyCategoriesLock) { dirtyCategories.addAll(failed) }
                scheduleDebouncedPush()
            }
        }
        if (restoredAny) reload()
    }

    // --- Lifecycle ---

    private fun cleanup() {
        pushJob?.cancel()
        pollJob?.cancel()
        pluginScope.cancel()
        try {
            dataPrefsListener?.let { CloudStreamApp.context?.getSharedPrefs()?.unregisterOnSharedPreferenceChangeListener(it) }
            defaultPrefsListener?.let { CloudStreamApp.context?.getDefaultSharedPrefs()?.unregisterOnSharedPreferenceChangeListener(it) }
        } catch (_: Exception) {
        }
        dataPrefsListener = null
        defaultPrefsListener = null
        lifecycleCallbacks?.let { registeredApp?.unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
        registeredApp = null
        activity = null
    }

    override fun load(context: Context) {
        cleanup()
        pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activity = context as? AppCompatActivity
        registerMainAPI(CloudSyncProvider(this))

        openSettings = { ctx ->
            val act = ctx as? AppCompatActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                SyncSettings(this).show(act)
            }
        }

        val creds = SyncStorage.creds
        // Diagnostic: dump SharedPreferences keys and their category classifications
        val allKeys = context.getSharedPrefs().all.keys + context.getDefaultSharedPrefs().all.keys
        val classified = allKeys.mapNotNull { key ->
            val cat = SyncBackup.classifyKey(key)
            "$key -> ${cat?.key ?: "IGNORED"}"
        }.groupBy { it.substringAfterLast(" -> ") }
        classified.forEach { (cat, keys) ->
            Log.d(TAG, "PrefKeys[$cat]: ${keys.size} keys")
            if (cat != "SETTINGS" && cat != "IGNORED") {
                Log.d(TAG, "  ${keys.joinToString()}")
            }
        }

        if (creds != null && creds.isLoggedIn()) {
            pluginScope.launch {
                try {
                    if (creds.restoreDevice) {
                        if (pullChangedCategories(context)) {
                            withContext(Dispatchers.Main) { showToast("Синхронізовано з сервера") }
                        }
                    }
                    if (creds.backupDevice) {
                        val m = SyncNetwork.fetchManifest()
                        if (m == null || m.extensions == null && m.settings == null &&
                            m.bookmarks == null && m.resumeWatching == null && m.searchHistory == null
                        ) {
                            Log.d(TAG, "No cloud data — initial push")
                            pushAllCategories(context)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startup sync failed: ${e.message}")
                }
            }
        }

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) {
                if (key == "CLOUDSYNC_CREDS") {
                    startPolling(context)
                    return@OnSharedPreferenceChangeListener
                }
                if (!isRestoring && System.currentTimeMillis() > restoringUntil) {
                    val category = SyncBackup.classifyKey(key)
                    if (category != null) markDirty(category)
                }
            }
        }
        dataPrefsListener = listener
        defaultPrefsListener = listener
        try {
            context.getSharedPrefs().registerOnSharedPreferenceChangeListener(listener)
            context.getDefaultSharedPrefs().registerOnSharedPreferenceChangeListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "prefs listener register failed: ${e.message}")
        }

        try {
            MainActivity.bookmarksUpdatedEvent += { _: Boolean ->
                if (!isRestoring && System.currentTimeMillis() > restoringUntil) {
                    markDirty(SyncCategory.BOOKMARKS)
                }
            }
        } catch (_: Throwable) {
        }

        val appInstance = context.applicationContext as? android.app.Application
        val callback = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) {
                if (a is MainActivity) {
                    this@CloudSyncPlugin.activity = a as? AppCompatActivity
                    pluginScope.launch {
                        try {
                            val c = SyncStorage.creds
                            if (c != null && c.isLoggedIn()) {
                                if (c.restoreDevice) {
                                    pullChangedCategories(a)
                                }
                                if (c.backupDevice && dirtyCategories.isNotEmpty()) {
                                    pushAllCategories(a)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {
                if (a.javaClass.name == "com.lagradost.cloudstream3.MainActivity") {
                    pluginScope.launch {
                        try {
                            val c = SyncStorage.creds
                            if (c != null && c.isLoggedIn() && c.backupDevice) {
                                pushAllCategories(a.applicationContext)
                                Log.d(TAG, "Sync on exit: pushed categories")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Sync on exit failed: ${e.message}")
                        }
                    }
                }
            }
            override fun onActivitySaveInstanceState(a: android.app.Activity, o: android.os.Bundle) {
                if (a.javaClass.name == "com.lagradost.cloudstream3.MainActivity") {
                    o.remove("android:support:fragments")
                    o.remove("android:support:fragments:state")
                }
            }
            override fun onActivityDestroyed(a: android.app.Activity) {
                if (a === this@CloudSyncPlugin.activity) this@CloudSyncPlugin.activity = null
            }
        }
        lifecycleCallbacks = callback
        registeredApp = appInstance
        appInstance?.registerActivityLifecycleCallbacks(callback)

        startPolling(context)
    }
}
