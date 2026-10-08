package com.example.telegramproxychecker

/** Human-readable, secret-free diagnostics for failures observed by TDLib. */
internal data class ProxyFailureCount(val reason: String, val count: Int)

internal fun topProxyFailures(proxies: List<MtProxy>, limit: Int = 3): List<ProxyFailureCount> =
    proxies.asSequence()
        .filter { it.tcpOk == true && it.telegramOk == false }
        .groupingBy { it.telegramError?.ifBlank { "Причина не указана" } ?: "Причина не указана" }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { ProxyFailureCount(it.key, it.value) }

internal fun buildProxyDiagnostics(proxies: List<MtProxy>, checked: Int, total: Int): String =
    buildString {
        appendLine("Telegram proxy checker — diagnostics")
        appendLine("Downloaded entries: ${proxies.size}")
        appendLine("This scan: ${checked}/${total}")
        appendLine("TCP OK: ${proxies.count { it.tcpOk == true }}")
        appendLine("TCP FAIL: ${proxies.count { it.tcpOk == false }}")
        appendLine("Telegram OK: ${proxies.count { it.telegramOk == true }}")
        appendLine("Telegram FAIL after TCP OK: ${proxies.count { it.tcpOk == true && it.telegramOk == false }}")
        appendLine("Not tested: ${proxies.count { it.telegramOk == null }}")
        appendLine()
        for (p in proxies) {
            // Never copy MTProto secrets, original deep links, or cache keys.
            append(p.server).append(':').append(p.port)
            append(" | TCP=").append(p.tcpOk ?: "?")
            append(" | Telegram=").append(p.telegramOk ?: "?")
            append(" | latencyMs=").append(p.telegramPingMs ?: "-")
            append(" | checkedAtMs=").append(p.checkedAt ?: "-")
            if (p.telegramOk == false) {
                append(" | error=").append(
                    (p.telegramError ?: "Неизвестно").replace('\n', ' ').replace('\r', ' ')
                )
            }
            appendLine()
        }
    }
