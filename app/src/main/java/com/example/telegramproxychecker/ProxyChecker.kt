package com.example.telegramproxychecker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

suspend fun checkTcpProxy(proxy: MtProxy, timeoutMs: Int = 5000): MtProxy {
    return withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(proxy.server, proxy.port),
                    timeoutMs
                )
            }

            proxy.copy(
                tcpOk = true,
                tcpPingMs = System.currentTimeMillis() - start
            )
        } catch (_: Exception) {
            proxy.copy(
                tcpOk = false,
                tcpPingMs = null,
                telegramOk = false,
                telegramPingMs = null,
                telegramError = "TCP недоступен"
            )
        }
    }
}