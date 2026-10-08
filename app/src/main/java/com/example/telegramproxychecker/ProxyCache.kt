package com.example.telegramproxychecker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProxyCache(context: Context) {

    private val prefs = context.getSharedPreferences(
        "proxy_cache",
        Context.MODE_PRIVATE
    )

    fun loadProxies(): List<MtProxy> {
        val json = prefs.getString("proxies_json", null) ?: return emptyList()

        return try {
            val array = JSONArray(json)
            val result = mutableListOf<MtProxy>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                result.add(
                    MtProxy(
                        originalUrl = obj.optString("originalUrl"),
                        server = obj.optString("server"),
                        port = obj.optInt("port"),
                        secret = obj.optString("secret"),

                        tcpOk = optBooleanNullable(obj, "tcpOk"),
                        tcpPingMs = optLongNullable(obj, "tcpPingMs"),

                        telegramOk = optBooleanNullable(obj, "telegramOk"),
                        telegramPingMs = optLongNullable(obj, "telegramPingMs"),
                        telegramError = optStringNullable(obj, "telegramError"),

                        checkedAt = optLongNullable(obj, "checkedAt"),

                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                )
            }

            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProxies(proxies: List<MtProxy>) {
        val array = JSONArray()

        proxies.forEach { proxy ->
            val obj = JSONObject()

            obj.put("originalUrl", proxy.originalUrl)
            obj.put("server", proxy.server)
            obj.put("port", proxy.port)
            obj.put("secret", proxy.secret)

            putNullable(obj, "tcpOk", proxy.tcpOk)
            putNullable(obj, "tcpPingMs", proxy.tcpPingMs)

            putNullable(obj, "telegramOk", proxy.telegramOk)
            putNullable(obj, "telegramPingMs", proxy.telegramPingMs)
            putNullable(obj, "telegramError", proxy.telegramError)

            putNullable(obj, "checkedAt", proxy.checkedAt)

            obj.put("isFavorite", proxy.isFavorite)

            array.put(obj)
        }

        prefs.edit()
            .putString("proxies_json", array.toString())
            .commit() // Called on Dispatchers.IO; persist before stopping the service.
    }

    private fun putNullable(obj: JSONObject, key: String, value: Any?) {
        if (value == null) {
            obj.put(key, JSONObject.NULL)
        } else {
            obj.put(key, value)
        }
    }

    private fun optBooleanNullable(obj: JSONObject, key: String): Boolean? {
        return if (obj.isNull(key) || !obj.has(key)) {
            null
        } else {
            obj.optBoolean(key)
        }
    }

    private fun optLongNullable(obj: JSONObject, key: String): Long? {
        return if (obj.isNull(key) || !obj.has(key)) {
            null
        } else {
            obj.optLong(key)
        }
    }

    private fun optStringNullable(obj: JSONObject, key: String): String? {
        return if (obj.isNull(key) || !obj.has(key)) {
            null
        } else {
            obj.optString(key)
        }
    }
}