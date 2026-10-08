package com.example.telegramproxychecker

/**
 * Source metadata used by settings and background inventory retrieval.
 * The runtime scan remains MTProto-only until the separate SOCKS5 checker ships.
 */
enum class ProxySourceProtocol(val label: String) {
    MTPROTO("MTProto"),
    SOCKS5("SOCKS5"),
    HTTP("HTTP"),
    WEB("WEB")
}

internal enum class ProxySourceStatus {
    ACTIVE,
    PLANNED
}

internal data class ProxySourcePresentation(
    val id: String,
    val name: String,
    val repository: String,
    val sourceUrl: String,
    val protocol: ProxySourceProtocol,
    val status: ProxySourceStatus
)

internal object ProxySourceCatalogue {
    val entries: List<ProxySourcePresentation> = listOf(
        ProxySourcePresentation(
            id = "solispirit-mtproto",
            name = "SoliSpirit MTProto",
            repository = "SoliSpirit/mtproto · all_proxies.txt",
            sourceUrl = "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            protocol = ProxySourceProtocol.MTPROTO,
            status = ProxySourceStatus.ACTIVE
        ),
        ProxySourcePresentation(
            id = "hookzof-socks5",
            name = "hookzof SOCKS5",
            repository = "hookzof/socks5_list · proxy.txt",
            sourceUrl = "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
            protocol = ProxySourceProtocol.SOCKS5,
            status = ProxySourceStatus.PLANNED
        )
    )
}
