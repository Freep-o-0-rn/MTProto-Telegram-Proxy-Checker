package com.example.telegramproxychecker

/**
 * SOCKS5 public-feed format: one host:port per line, no authentication.
 * Parsing does not imply that a proxy is reachable or usable by Telegram.
 */
internal fun parseSocks5Line(line: String, sourceId: String = "hookzof-socks5"): MtProxy? {
    val clean = line.substringBefore('#').trim()
    if (clean.isEmpty() || clean.contains("://") || '@' in clean) return null
    val colon = clean.lastIndexOf(':')
    if (colon <= 0 || colon == clean.lastIndex) return null
    val host = clean.substring(0, colon)
    val port = clean.substring(colon + 1).toIntOrNull() ?: return null
    if (port !in 1..65535 || !validSocksHost(host)) return null

    return MtProxy(
        originalUrl = "https://t.me/socks?server=$host&port=$port",
        server = host,
        port = port,
        secret = "",
        protocol = ProxySourceProtocol.SOCKS5,
        sourceId = sourceId
    )
}

private fun validSocksHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253 || host.any { it.isWhitespace() }) return false
    if (host.all { it.isDigit() || it == '.' }) {
        val octets = host.split('.')
        return octets.size == 4 &&
            octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) &&
                (it.toIntOrNull() ?: 256) in 0..255 }
    }
    // The supplied feed contains IPv4. Allow conventional ASCII DNS names as well.
    return host.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() || it == '-' }
    }
}
