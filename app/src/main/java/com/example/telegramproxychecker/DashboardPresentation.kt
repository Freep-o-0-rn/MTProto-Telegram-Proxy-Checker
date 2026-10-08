package com.example.telegramproxychecker

/** Presentation only: never changes repository order or scan scheduling. */
internal enum class ProxyTab {
    WORKING, ALL, FAVORITES
}

internal fun dashboardProxies(
    proxies: List<MtProxy>,
    tab: ProxyTab,
    query: String = ""
): List<MtProxy> {
    val search = query.trim()
    return proxies.asSequence()
        .filter {
            when (tab) {
                ProxyTab.WORKING -> it.telegramOk == true
                ProxyTab.ALL -> true
                ProxyTab.FAVORITES -> it.isFavorite
            }
        }
        .filter { search.isEmpty() || it.server.contains(search, ignoreCase = true) || it.port.toString().contains(search) }
        .sortedWith(
            compareBy<MtProxy> { when (it.telegramOk) { true -> 0; null -> 1; false -> 2 } }
                .thenBy { it.telegramPingMs ?: Long.MAX_VALUE }
                .thenBy { it.server.lowercase() }
                .thenBy { it.port }
        )
        .toList()
}

/**
 * A scanning snapshot can reorder the live list every 200ms. While the user is
 * reading lower rows, retain their identities/positions; only append new matches.
 * Re-sort when they return to the top (or change the filter).
 */
internal fun stableDashboardKeys(
    previous: List<String>,
    sortedKeys: List<String>,
    freezeOrder: Boolean
): List<String> {
    if (!freezeOrder) return sortedKeys
    val available = sortedKeys.toHashSet()
    val retained = previous.filter { it in available }
    val seen = retained.toHashSet()
    val added = sortedKeys.filter { it !in seen }
    return retained + added
}
