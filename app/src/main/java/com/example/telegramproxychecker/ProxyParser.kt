package com.example.telegramproxychecker

import java.net.URI
import java.net.URLDecoder

/**
 * Supported public MTProto feed links:
 * https://t.me/proxy?server=...&port=...&secret=...
 * tg://proxy?server=...&port=...&secret=...
 * Some feeds append a pipe-delimited timestamp; skip that suffix.
 *
 * Pure JVM parsing also lets local unit tests exercise the actual feed formats.
 */
fun parseProxyLine(line: String): MtProxy? {
    val clean = line.substringBefore('|').trim()
    if (!clean.startsWith("https://t.me/proxy?", ignoreCase = true) &&
        !clean.startsWith("tg://proxy?", ignoreCase = true)) return null
    val uri = try { URI(clean) } catch (_: Exception) { return null }
    val query = uri.rawQuery ?: return null
    val params = try {
        query.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else
                URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                    URLDecoder.decode(pair.substring(i + 1).replace("+", "%2B"), "UTF-8")
        }.toMap()
    } catch (_: Exception) { return null }

    val server = params["server"]?.trim()?.trimEnd('.')
        ?.takeIf { it.isNotBlank() && !it.any(Char::isWhitespace) } ?: return null
    val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val secret = params["secret"]?.takeIf { it.isNotBlank() } ?: return null
    return MtProxy(
        originalUrl = clean,
        server = server,
        port = port,
        secret = secret
    )
}
