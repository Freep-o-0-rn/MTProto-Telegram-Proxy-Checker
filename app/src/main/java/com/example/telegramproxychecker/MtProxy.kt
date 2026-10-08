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

    val isFavorite: Boolean = false,

    // Appends optional fields without changing existing MTProto call sites/cached keys.
    val protocol: ProxySourceProtocol = ProxySourceProtocol.MTPROTO,
    val username: String? = null,
    val password: String? = null,
    val sourceId: String = "solispirit-mtproto"
) {
    val cacheKey: String
        get() = if (protocol == ProxySourceProtocol.MTPROTO) {
            // Preserve the legacy MTProto identity for cache/favorite migration.
            "$server:$port:$secret"
        } else {
            "${protocol.name}:$server:$port:${username.orEmpty()}:${password.orEmpty()}"
        }
}

internal fun List<MtProxy>.withFavoritesFrom(current: List<MtProxy>): List<MtProxy> {
    val favorites = current.associate { it.cacheKey to it.isFavorite }
    return map { proxy ->
        proxy.copy(isFavorite = favorites[proxy.cacheKey] ?: proxy.isFavorite)
    }
}
