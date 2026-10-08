package com.example.telegramproxychecker

import android.app.ActivityManager
import android.content.Context

/**
 * Throughput tuning must not increase the number of simultaneous TDLib
 * clients compared with the previously tested implementation.
 */
internal object ScanConcurrencyPolicy {
    const val MIN_WORKERS = 1
    const val MAX_WORKERS = 30
    const val DEFAULT_WORKERS = 6
    const val MAX_TDLIB_CHECKS = 6

    fun clamp(value: Int): Int = value.coerceIn(MIN_WORKERS, MAX_WORKERS)

    /**
     * Conservative suggestion based on physical resources. This is deliberately
     * not a claim that more cores imply a faster or more reliable network.
     * Changes take effect at the start of the next scan.
     */
    fun autoWorkers(cores: Int, memoryClassMb: Int, lowRam: Boolean): Int {
        if (lowRam) return 3
        val cpuBound = (cores.coerceAtLeast(1) * 2).coerceAtMost(MAX_WORKERS)
        val memoryBound = when {
            memoryClassMb <= 128 -> 4
            memoryClassMb <= 192 -> 6
            memoryClassMb <= 256 -> 8
            memoryClassMb <= 384 -> 12
            memoryClassMb <= 512 -> 16
            else -> 20
        }
        return clamp(minOf(cpuBound, memoryBound))
    }

    fun recommended(context: Context): Int {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryMb = am?.memoryClass ?: 192
        val lowRam = am?.isLowRamDevice ?: false
        return autoWorkers(Runtime.getRuntime().availableProcessors(), memoryMb, lowRam)
    }

    fun effective(context: Context): Int {
        return if (ProxySourceSettings.autoConcurrency(context)) recommended(context)
            else clamp(ProxySourceSettings.parallelChecks(context))
    }

    fun telegramLimit(workers: Int): Int = minOf(clamp(workers), MAX_TDLIB_CHECKS)
}
