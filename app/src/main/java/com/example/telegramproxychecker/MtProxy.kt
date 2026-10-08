package com.example.telegramproxychecker

data class MtProxy(
    val originalUrl: String,
    val server: String,
    val port: Int,
    val secret: String,

    val tcpOk: Boolean? = null,
    val tcpPingMs: Long? = null,

    val telegramOk: Boolean? = null,
    val telegramPingMs: Long? = null,
    val telegramError: String? = null,

    val checkedAt: Long? = null,

    val isFavorite: Boolean = false
) {
    val cacheKey: String
        get() = "$server:$port:$secret"
}

internal fun List<MtProxy>.withFavoritesFrom(current: List<MtProxy>): List<MtProxy> {
    val favorites = current.associate { it.cacheKey to it.isFavorite }
    return map { proxy ->
        proxy.copy(isFavorite = favorites[proxy.cacheKey] ?: proxy.isFavorite)
    }
}
