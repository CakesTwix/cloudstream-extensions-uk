package com.lagradost.sync

import com.lagradost.api.Log

/**
 * Детальний лог синхронізації. Типово вимкнений; вмикається перемикачем
 * «Детальний лог синхронізації» у налаштуваннях плагіна (SyncStorage.debugLogs),
 * щоб не створювати шум у Logcat для звичайних користувачів.
 *
 * Помилки пишуться напряму через Log.e (без перемикача) — вони мають бути
 * видимі завжди. Усі повідомлення під спільним тегом «CloudSync».
 * Токен та інші секрети логувати заборонено.
 */
object SyncLog {
    private const val TAG = "CloudSync"

    fun d(message: String) {
        if (SyncStorage.debugLogs) Log.d(TAG, message)
    }

    fun i(message: String) {
        if (SyncStorage.debugLogs) Log.i(TAG, message)
    }
}
