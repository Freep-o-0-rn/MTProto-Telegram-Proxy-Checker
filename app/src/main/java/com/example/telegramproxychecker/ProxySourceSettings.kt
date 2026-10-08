package com.example.telegramproxychecker

import android.content.Context

/**
 * Persistent per-source switches. Existing SoliSpirit/SOCKS5 preferences are
 * reused, so upgrades do not silently overwrite previous user choices.
 */
internal object ProxySourceSettings {
    private const val PREFS = "proxy_source_settings"
    private const val MTPROTO_ENABLED = "solispirit_mtproto_enabled"
    private const val SOCKS5_ENABLED = "hookzof_socks5_enabled"
    private const val LIMIT = "scan_limit"
    private const val SCAN_ALL = "scan_all"
    private const val PARALLEL_CHECKS = "parallel_checks"
    private const val AUTO_CONCURRENCY = "auto_concurrency"

    private fun keyFor(sourceId: String): String = when (sourceId) {
        "solispirit-mtproto" -> MTPROTO_ENABLED
        "hookzof-socks5" -> SOCKS5_ENABLED
        else -> "source_${sourceId}_enabled"
    }

    fun enabledSourceIds(context: Context): Set<String> {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ProxySourceCatalogue.entries.asSequence()
            .filter { source ->
                preferences.getBoolean(keyFor(source.id), source.protocol == ProxySourceProtocol.MTPROTO)
            }
            .map { it.id }
            .toSet()
    }

    fun sourceEnabled(context: Context, sourceId: String): Boolean =
        sourceId in enabledSourceIds(context)

    fun setSourceEnabled(context: Context, sourceId: String, enabled: Boolean) {
        require(ProxySourceCatalogue.entries.any { it.id == sourceId }) { "Unknown proxy source" }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(keyFor(sourceId), enabled).apply()
    }

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

    // Legacy callers select the protocol as a whole; the settings UI uses individual switches.
    fun mtprotoEnabled(context: Context): Boolean = enabledSourceIds(context).any { id ->
        ProxySourceCatalogue.entries.any { it.id == id && it.protocol == ProxySourceProtocol.MTPROTO }
    }

    fun socks5Enabled(context: Context): Boolean =
        sourceEnabled(context, "hookzof-socks5")

    fun setSocks5Enabled(context: Context, enabled: Boolean) =
        setSourceEnabled(context, "hookzof-socks5", enabled)

    fun setMtprotoEnabled(context: Context, enabled: Boolean) {
        ProxySourceCatalogue.entries.filter { it.protocol == ProxySourceProtocol.MTPROTO }
            .forEach { setSourceEnabled(context, it.id, enabled) }
    }
}
