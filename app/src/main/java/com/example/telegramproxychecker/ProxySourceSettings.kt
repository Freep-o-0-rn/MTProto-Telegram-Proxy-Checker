package com.example.telegramproxychecker

import android.content.Context

/**
 * Persistent source selection and scan bounds for both protocols.
 * Worker settings are snapshotted on every new scan.
 */
internal object ProxySourceSettings {
    private const val PREFS = "proxy_source_settings"
    private const val MTPROTO_ENABLED = "solispirit_mtproto_enabled"
    private const val SOCKS5_ENABLED = "hookzof_socks5_enabled"
    private const val LIMIT = "scan_limit"
    private const val SCAN_ALL = "scan_all"
    private const val PARALLEL_CHECKS = "parallel_checks"
    private const val AUTO_CONCURRENCY = "auto_concurrency"

    fun parallelChecks(context: Context): Int =
        ScanConcurrencyPolicy.clamp(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(PARALLEL_CHECKS, ScanConcurrencyPolicy.DEFAULT_WORKERS)
        )

    fun autoConcurrency(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(AUTO_CONCURRENCY, false)

    fun setParallelChecks(context: Context, value: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PARALLEL_CHECKS, ScanConcurrencyPolicy.clamp(value)).apply()
    }

    fun setAutoConcurrency(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTO_CONCURRENCY, value).apply()
    }

    // A default batch size, never a hardcoded count of servers.
    fun scanLimit(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(LIMIT, 500).coerceAtLeast(1)

    fun scanAll(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(SCAN_ALL, false)

    fun setScanLimit(context: Context, limit: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(LIMIT, limit.coerceAtLeast(1)).apply()
    }

    fun setScanAll(context: Context, all: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SCAN_ALL, all).apply()
    }

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
