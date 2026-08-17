package com.lagradost.model

import org.json.JSONArray
import org.json.JSONObject

class NuxtResolver(private val array: JSONArray) {
        fun get(index: Int): Any? {
            if (index < 0 || index >= array.length()) return null
            val value = array.opt(index)
            return if (value == null || value == JSONObject.NULL) null else value
        }

        fun getObject(index: Int): JSONObject? = get(index) as? JSONObject

        fun resolve(obj: JSONObject, key: String): Any? {
            val value = obj.opt(key) ?: return null
            return if (value is Int) get(value) else value
        }

        fun resolveString(obj: JSONObject, key: String): String? = resolve(obj, key)?.toString()
        fun resolveInt(obj: JSONObject, key: String): Int? {
            return when (val res = resolve(obj, key)) {
                is Int -> res
                is Number -> res.toInt()
                is String -> res.toIntOrNull()
                else -> null
            }
        }

        fun resolveObject(obj: JSONObject, key: String): JSONObject? =
            resolve(obj, key) as? JSONObject

        fun resolveArray(obj: JSONObject, key: String): JSONArray? = resolve(obj, key) as? JSONArray
    }
