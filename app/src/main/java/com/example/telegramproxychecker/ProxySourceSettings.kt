package com.example.telegramproxychecker

import android.content.Context

/**
 * Per-source selection. MTProto affects the existing scanner; SOCKS5 is stored
 * for the future checker but never runs through the MTProto pipeline.
 */
internal object ProxySourceSettings {
    private const val PREFS = "proxy_source_settings"
    private const val MTPROTO_ENABLED = "solispirit_mtproto_enabled"
    private const val SOCKS5_ENABLED = "hookzof_socks5_enabled"

    fun socks5Enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(SOCKS5_ENABLED, false)

    fun setSocks5Enabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SOCKS5_ENABLED, enabled).apply()
    }

    fun mtprotoEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(MTPROTO_ENABLED, true)

    fun setMtprotoEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MTPROTO_ENABLED, enabled).apply()
    }
}
