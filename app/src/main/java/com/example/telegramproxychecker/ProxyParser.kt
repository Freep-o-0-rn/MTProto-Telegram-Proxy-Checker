package com.example.telegramproxychecker

import android.net.Uri

fun parseProxyLine(line: String): MtProxy? {
    val trimmed = line.trim()

    if (!trimmed.startsWith("https://t.me/proxy")) {
        return null
    }

    val uri = Uri.parse(trimmed)

    val server = uri.getQueryParameter("server") ?: return null
    val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
    val secret = uri.getQueryParameter("secret") ?: return null

    return MtProxy(
        originalUrl = trimmed,
        server = server.trimEnd('.'),
        port = port,
        secret = secret
    )
}