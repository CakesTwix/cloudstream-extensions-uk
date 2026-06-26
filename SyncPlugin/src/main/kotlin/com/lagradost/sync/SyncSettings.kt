package com.lagradost.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import ua.CakesTwix.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncSettings(private val plugin: CloudSyncPlugin) {

    private val sm = SyncStorage
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private val packageName = BuildConfig.LIBRARY_PACKAGE_NAME
    private var dialog: BottomSheetDialog? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun show(context: Context) {
        val inflater = LayoutInflater.from(context)
        val view = buildView(inflater, context)

        val d = BottomSheetDialog(context)
        d.setContentView(view)
        d.setOnDismissListener { scope.cancel() }
        d.show()

        val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null) {
            com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet).state =
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog = d
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    @SuppressLint("DiscouragedApi")
    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", packageName)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", packageName)
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", packageName)
        return this.findViewById(id)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", packageName)
        this.background = res.getDrawable(outlineId, null)
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
    private fun buildView(inflater: LayoutInflater, context: Context): View {
        val settings = getLayout("settings", inflater, null)

        // Save / close button
        settings.findView<ImageView>("save").apply {
            setImageDrawable(getDrawable("save_icon"))
            makeTvCompatible()
            setOnClickListener { dismiss() }
        }

        // Configure button
        settings.findView<ImageView>("creds_btn").apply {
            setImageDrawable(getDrawable("edit_icon"))
            makeTvCompatible()
            setOnClickListener { showCredentialsDialog(settings, inflater, context) }
        }

        // Global backup/restore switches
        settings.findView<Switch>("backup_device").apply {
            val c = sm.creds
            isChecked = c?.backupDevice ?: false
            setOnCheckedChangeListener { _, checked ->
                val creds = sm.creds
                if (creds != null) {
                    creds.backupDevice = checked
                    sm.creds = creds
                    if (checked) doSync(settings, context)
                } else if (checked) {
                    showToast("Спочатку налаштуйте облікові дані")
                    this.isChecked = false
                }
            }
        }

        settings.findView<Switch>("restore_device").apply {
            val c = sm.creds
            isChecked = c?.restoreDevice ?: false
            setOnCheckedChangeListener { _, checked ->
                val creds = sm.creds
                if (creds != null) {
                    creds.restoreDevice = checked
                    sm.creds = creds
                    if (checked) doSync(settings, context)
                } else if (checked) {
                    showToast("Спочатку налаштуйте облікові дані")
                    this.isChecked = false
                }
            }
        }

        // Sync now button
        settings.findView<Button>("sync_now_btn").apply {
            setOnClickListener {
                val creds = sm.creds
                if (creds == null || !creds.isLoggedIn()) {
                    showToast("Спочатку налаштуйте облікові дані")
                    return@setOnClickListener
                }
                doSync(settings, context)
            }
        }

        // Per-category rows
        val currentCreds = sm.creds ?: SyncCreds()
        val categoriesList = settings.findView<LinearLayout>("categories_list")
        val categories = listOf(
            Triple("Репозиторії", "Список URL репозиторіїв розширень", SyncCategory.EXTENSIONS),
            Triple("Закладки", "Улюблені фільми та серіали", SyncCategory.BOOKMARKS),
            Triple("Продовження перегляду", "Позиція та прогрес перегляду", SyncCategory.RESUME_WATCHING),
            Triple("Історія пошуку", "Останні пошукові запити", SyncCategory.SEARCH_HISTORY),
            Triple("Налаштування", "Преференції та конфігурація", SyncCategory.SETTINGS),
        )

        categories.forEach { (label, desc, cat) ->
            val row = getLayout("sync_cat_row", inflater, null)
            row.findView<TextView>("cat_label").text = label
            row.findView<TextView>("cat_desc").text = desc
            val backupCb = row.findView<Switch>("cat_backup")
            val restoreCb = row.findView<Switch>("cat_restore")
            backupCb.makeTvCompatible()
            restoreCb.makeTvCompatible()

            backupCb.isChecked = currentCreds.isBackupEnabled(cat)
            restoreCb.isChecked = currentCreds.isRestoreEnabled(cat)

            val listener = { _: android.widget.CompoundButton, _: Boolean ->
                val creds = sm.creds ?: SyncCreds()
                when (cat) {
                    SyncCategory.EXTENSIONS -> { creds.backupExtensions = backupCb.isChecked; creds.restoreExtensions = restoreCb.isChecked }
                    SyncCategory.BOOKMARKS -> { creds.backupBookmarks = backupCb.isChecked; creds.restoreBookmarks = restoreCb.isChecked }
                    SyncCategory.RESUME_WATCHING -> { creds.backupResumeWatching = backupCb.isChecked; creds.restoreResumeWatching = restoreCb.isChecked }
                    SyncCategory.SEARCH_HISTORY -> { creds.backupSearchHistory = backupCb.isChecked; creds.restoreSearchHistory = restoreCb.isChecked }
                    SyncCategory.SETTINGS -> { creds.backupSettings = backupCb.isChecked; creds.restoreSettings = restoreCb.isChecked }
                }
                sm.creds = creds
            }
            backupCb.setOnCheckedChangeListener { b, c -> listener(b, c) }
            restoreCb.setOnCheckedChangeListener { b, c -> listener(b, c) }
            categoriesList.addView(row)
        }

        // Initial status update
        updateStatusCard(settings, context)
        updateDisconnectButton(settings, context, inflater)

        scope.launch {
            refreshDevicesList(settings, inflater)
            updateSyncInfo(settings, context)
        }

        return settings
    }

    // ----------------------------------------------------------------------- //

    @SuppressLint("SetTextI18n")
    private fun updateDisconnectButton(root: View, context: Context, inflater: LayoutInflater) {
        val btn = root.findView<Button>("disconnect_btn")
        val creds = sm.creds
        if (creds == null || !creds.isLoggedIn()) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        btn.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Відключити пристрій")
                .setMessage("Видалити цей пристрій (${creds.deviceName ?: Build.MODEL}) з сервера? Локальні дані залишаться.")
                .setPositiveButton("Відключити") { _, _ ->
                    scope.launch {
                        val res = SyncNetwork.deregisterThisDevice()
                        sm.creds = null
                        showToast("Пристрій відключено: ${res.second ?: "OK"}")
                        updateStatusCard(root, context)
                        updateSyncInfo(root, context)
                        updateDisconnectButton(root, context, inflater)
                        refreshDevicesList(root, inflater)
                    }
                }
                .setNegativeButton("Скасувати") { d, _ -> d.dismiss() }
                .show()
                .setDefaultFocus()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatusCard(root: View, context: Context) {
        val dot = root.findView<View>("status_dot")
        val statusText = root.findView<TextView>("status_text")
        val serverInfo = root.findView<TextView>("server_info")

        val creds = sm.creds
        if (creds == null || !creds.isLoggedIn()) {
            dot.background?.setColorFilter(0xFFF44336.toInt(), PorterDuff.Mode.SRC_ATOP)
            statusText.text = "Не підключено"
            serverInfo.text = "Натисніть \u2699, щоб налаштувати"
        } else {
            dot.background?.setColorFilter(0xFF4CAF50.toInt(), PorterDuff.Mode.SRC_ATOP)
            statusText.text = "Підключено"
            serverInfo.text = "\uD83D\uDCC1 ${creds.deviceName ?: Build.MODEL}"
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun updateSyncInfo(root: View, ctx: Context) {
        val infoView = root.findView<TextView>("last_sync_info")
        val creds = sm.creds

        if (creds == null || !creds.isLoggedIn()) {
            infoView.text = ""
            return
        }

        val parts = mutableListOf<String>()
        for (category in SyncCategory.entries) {
            val ts = sm.getCategoryTimestamp(category)
            if (ts > 0) {
                val ago = formatTimeAgo(System.currentTimeMillis() - ts)
                val label = when (category) {
                    SyncCategory.EXTENSIONS -> "Репозиторії"
                    SyncCategory.BOOKMARKS -> "Закладки"
                    SyncCategory.RESUME_WATCHING -> "Прогрес"
                    SyncCategory.SEARCH_HISTORY -> "Пошук"
                    SyncCategory.SETTINGS -> "Налаштування"
                }
                parts.add("$label: $ago")
            }
        }

        infoView.text = if (parts.isEmpty()) "" else "\u21BB " + parts.joinToString("  \u2022  ")
    }

    private fun formatTimeAgo(diffMs: Long): String {
        val min = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        return when {
            min < 1 -> "щойно"
            min < 60 -> "$min хв тому"
            min < 1440 -> "${min / 60} год тому"
            else -> "${min / 1440} дн тому"
        }
    }

    // ----------------------------------------------------------------------- //

    @SuppressLint("SetTextI18n")
    private fun showCredentialsDialog(root: View, inflater: LayoutInflater, context: Context) {
        val credsView = getLayout("sync_creds", inflater, null)
        val deviceNameInput = credsView.findView<EditText>("device_name")
        val serverUrlInput = credsView.findView<EditText>("server_url")
        val tokenInput = credsView.findView<EditText>("sync_token")
        val generateBtn = credsView.findView<Button>("generate_key_btn")

        val current = sm.creds ?: SyncCreds()
        deviceNameInput.setText(current.deviceName ?: Build.MODEL)
        serverUrlInput.setText(current.serverUrl)
        tokenInput.setText(current.token)

        generateBtn.setOnClickListener {
            tokenInput.setText(UUID.randomUUID().toString().replace("-", "").take(20))
        }

        AlertDialog.Builder(context)
            .setTitle("Підключення до сервера")
            .setView(credsView)
            .setPositiveButton("Зберегти") { _, _ ->
                val devName = deviceNameInput.text.toString().trim()
                val url = serverUrlInput.text.toString().trim().removeSuffix("/")
                val token = tokenInput.text.toString().trim()

                if (url.isEmpty() || token.isEmpty()) {
                    showToast("Адреса сервера та токен обов'язкові")
                    return@setPositiveButton
                }

                val deviceId = current.deviceId
                    ?: SyncNetwork.getDeviceId(packageName, context)

                val newCreds = current.copy(
                    serverUrl = url,
                    token = token,
                    deviceName = if (devName.isEmpty()) Build.MODEL else devName,
                    deviceId = deviceId,
                )
                sm.creds = newCreds

                scope.launch {
                    showToast("Підключення...")
                    SyncNetwork.registerDevice()
                    plugin.mergeAndSyncAllCategories(context)
                    showToast("Готово!")
                    updateStatusCard(root, context)
                    updateSyncInfo(root, context)
                    refreshDevicesList(root, inflater)
                }
            }
            .setNegativeButton("Скинути") { _, _ ->
                scope.launch {
                    val deleteRes = SyncNetwork.deregisterThisDevice()
                    sm.creds = null
                    showToast("Відключено: ${deleteRes.second ?: "OK"}")
                    updateStatusCard(root, context)
                    updateSyncInfo(root, context)
                    refreshDevicesList(root, inflater)
                }
            }
            .show()
            .setDefaultFocus()
    }

    private fun doSync(root: View, context: Context) {
        val creds = sm.creds
        if (creds == null || !creds.isLoggedIn()) {
            showToast("Спочатку налаштуйте облікові дані")
            return
        }
        scope.launch {
            try {
                showToast("Синхронізація...")
                plugin.mergeAndSyncAllCategories(context)
                showToast("Готово!")
                updateSyncInfo(root, context)
                refreshDevicesList(root, LayoutInflater.from(context))
            } catch (e: Exception) {
                showToast("Помилка: ${e.message}")
            }
        }
    }

    // ----------------------------------------------------------------------- //

    @SuppressLint("SetTextI18n")
    private suspend fun refreshDevicesList(rootView: View, inflater: LayoutInflater) {
        val devicesListLayout = rootView.findView<LinearLayout>("devices_list")
        devicesListLayout.removeAllViews()
        val creds = sm.creds ?: run {
            devicesListLayout.addView(emptyDevicesHint(inflater))
            return
        }
        val devices = SyncNetwork.fetchDevices()
        if (devices.isNullOrEmpty()) {
            devicesListLayout.addView(emptyDevicesHint(inflater))
            return
        }
        val ctx = rootView.context
        devices.forEach { device ->
            val isCurrent = device.deviceId == creds.deviceId
            val deviceView = getLayout("sync_device", inflater, null)
            val nameText = deviceView.findView<TextView>("device_name")
            val lastSeenText = deviceView.findView<TextView>("device_last_seen")
            val removeBtn = deviceView.findView<ImageView>("device_remove")
            removeBtn.setImageDrawable(getDrawable("delete_icon"))
            removeBtn.makeTvCompatible()
            val dot = deviceView.findViewById<View>(
                res.getIdentifier("device_status_dot", "id", packageName)
            )

            nameText.text = device.name + if (isCurrent) " (цей пристрій)" else ""
            lastSeenText.text = if (device.lastActive > 0) {
                "Був у мережі: " + formatTimeAgo(System.currentTimeMillis() - (device.lastActive * 1000).toLong())
            } else "Невідомо"

            if (dot != null) {
                dot.setBackgroundColor(
                    if (isCurrent) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()
                )
            }

            removeBtn.setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Видалити пристрій")
                    .setMessage("Видалити '${device.name}' з мережі синхронізації?")
                    .setPositiveButton("Видалити") { _, _ ->
                        scope.launch {
                            val res = SyncNetwork.removeDevice(device.deviceId)
                            if (res.first) {
                                showToast("Видалено ${device.name}")
                                if (isCurrent) {
                                    sm.creds = null
                                    updateStatusCard(rootView, ctx)
                                    updateSyncInfo(rootView, ctx)
                                }
                                refreshDevicesList(rootView, inflater)
                            } else {
                                showToast("Помилка: ${res.second}")
                            }
                        }
                    }
                    .setNegativeButton("Скасувати") { d, _ -> d.dismiss() }
                    .show()
                    .setDefaultFocus()
            }
            devicesListLayout.addView(deviceView)
        }
    }

    private fun emptyDevicesHint(inflater: LayoutInflater): View {
        val tv = TextView(inflater.context)
        tv.text = "Немає інших пристроїв"
        tv.textSize = 13f
        tv.setPadding(16, 24, 16, 24)
        tv.setTextColor(0x99999999.toInt())
        return tv
    }
}
