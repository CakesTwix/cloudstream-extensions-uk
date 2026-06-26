package com.lagradost.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SyncNetwork {
    private const val TAG = "CloudSync"
    private const val COMPRESSED_PREFIX = "gz:"

    private fun headers(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${SyncStorage.creds?.token ?: ""}")

    fun compressData(data: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz -> gz.write(data.toByteArray(Charsets.UTF_8)) }
        return COMPRESSED_PREFIX + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun decompressData(data: String): String {
        if (!data.startsWith(COMPRESSED_PREFIX)) return data
        val compressed = Base64.decode(data.removePrefix(COMPRESSED_PREFIX), Base64.NO_WRAP)
        return GZIPInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(Charsets.UTF_8).readText()
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(packageName: String, context: Context): String {
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) return md5(packageName + androidId)
        val deviceInfo = "${Build.BRAND}_${Build.MODEL}_${Build.DEVICE}"
        return md5(packageName + UUID.nameUUIDFromBytes(deviceInfo.toByteArray()).toString())
    }

    fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------- //
    // Manifest
    // ------------------------------------------------------------------- //

    suspend fun fetchManifest(): SyncManifest? {
        val creds = SyncStorage.creds ?: return null
        if (!creds.isLoggedIn()) return null
        return try {
            val res = app.get("${creds.baseUrl}v1/sync/manifest", headers = headers())
            if (res.code == 200) Gson().fromJson(res.text, SyncManifest::class.java) else null
        } catch (e: Exception) {
            Log.e(TAG, "fetchManifest failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------- //
    // Categories
    // ------------------------------------------------------------------- //

    suspend fun fetchCategory(category: SyncCategory): SyncCategoryPayload? {
        val creds = SyncStorage.creds ?: return null
        if (!creds.isLoggedIn()) return null
        return try {
            val res =
                app.get("${creds.baseUrl}v1/sync/categories/${category.key}", headers = headers())
            if (res.code == 200) {
                val payload = Gson().fromJson(res.text, SyncCategoryPayload::class.java)
                payload.copy(data = decompressData(payload.data))
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "fetchCategory(${category.key}) failed: ${e.message}")
            null
        }
    }

    /**
     * Push categories in parallel. The server auto-updates the manifest.
     * @param categoryData map of category to Pair(rawJsonData, hash)
     * @return set of successfully pushed categories
     */
    suspend fun pushCategories(categoryData: Map<SyncCategory, Pair<String, String>>): Set<SyncCategory> {
        val creds = SyncStorage.creds ?: return emptySet()
        if (!creds.isLoggedIn()) return emptySet()
        val successful = java.util.Collections.synchronizedSet(mutableSetOf<SyncCategory>())

        coroutineScope {
            categoryData.map { (category, dataPair) ->
                async(Dispatchers.IO) {
                    try {
                        val body = mapOf(
                            "data" to compressData(dataPair.first),
                            "device" to (creds.deviceName ?: "Unknown"),
                            "hash" to dataPair.second,
                        )
                        val res = app.put(
                            "${creds.baseUrl}v1/sync/categories/${category.key}",
                            json = body,
                            headers = headers(),
                        )
                        if (res.code in 200..299) successful.add(category)
                        else Log.e(TAG, "pushCategories: ${category.key} HTTP ${res.code}")
                    } catch (e: Exception) {
                        Log.e(TAG, "pushCategories: ${category.key} error: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        return successful
    }

    // ------------------------------------------------------------------- //
    // Devices
    // ------------------------------------------------------------------- //

    suspend fun registerDevice(): Boolean {
        val creds = SyncStorage.creds ?: return false
        if (!creds.isLoggedIn()) return false
        return try {
            val body = mapOf("name" to (creds.deviceName ?: "Unknown"))
            val res = app.put(
                "${creds.baseUrl}v1/sync/devices/${creds.deviceId}",
                json = body,
                headers = headers(),
            )
            res.code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "registerDevice failed: ${e.message}")
            false
        }
    }

    suspend fun fetchDevices(): List<CloudSyncDevice>? {
        val creds = SyncStorage.creds ?: return null
        if (!creds.isLoggedIn()) return null
        return try {
            val res = app.get("${creds.baseUrl}v1/sync/devices", headers = headers())
            if (res.code == 200) {
                Gson().fromJson<Map<String, CloudSyncDevice>>(res.text, object : TypeToken<Map<String, CloudSyncDevice>>() {}.type).values.toList()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun removeDevice(deviceId: String): Pair<Boolean, String?> {
        val creds = SyncStorage.creds ?: return false to "Credentials not found"
        if (!creds.isLoggedIn()) return false to "Not logged in"
        return try {
            val res =
                app.delete("${creds.baseUrl}v1/sync/devices/$deviceId", headers = headers())
            if (res.code in 200..299) true to "Device removed" else false to "HTTP ${res.code}"
        } catch (e: Exception) {
            false to e.message
        }
    }

    suspend fun deregisterThisDevice(): Pair<Boolean, String?> {
        val creds = SyncStorage.creds ?: return false to "Credentials not found"
        if (!creds.isLoggedIn()) return false to "Not logged in"
        return creds.deviceId?.let { removeDevice(it) } ?: (false to "No device id")
    }
}
