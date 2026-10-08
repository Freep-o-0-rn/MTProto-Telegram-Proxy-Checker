package com.example.telegramproxychecker

import android.content.Context

/**
 * Source preferences for the scanner. SOCKS5 is deliberately NOT selectable
 * until it has its own parser, test routine and cache model.
 */
internal object ProxySourceSettings {
    private const val PREFS = "proxy_source_settings"
    private const val MTPROTO_ENABLED = "solispirit_mtproto_enabled"

    fun mtprotoEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(MTPROTO_ENABLED, true)

    fun setMtprotoEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MTPROTO_ENABLED, enabled).apply()
    }
}
