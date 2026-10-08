package com.example.telegramproxychecker

import java.net.URLEncoder

/** Telegram deep links must match the proxy protocol, not just the server. */
internal fun telegramDeepLink(proxy: MtProxy): String {
    fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    val host = encoded(proxy.server)
    val base = "server=$host&port=${proxy.port}"
    return when (proxy.protocol) {
        ProxySourceProtocol.MTPROTO -> "tg://proxy?$base&secret=${encoded(proxy.secret)}"
        ProxySourceProtocol.SOCKS5 -> buildString {
            append("tg://socks?").append(base)
            proxy.username?.takeIf { it.isNotEmpty() }?.let {
                append("&user=").append(encoded(it))
            }
            proxy.password?.takeIf { it.isNotEmpty() }?.let {
                append("&pass=").append(encoded(it))
            }
        }
        else -> error("Неподдерживаемый тип прокси: ${proxy.protocol}")
    }
}
