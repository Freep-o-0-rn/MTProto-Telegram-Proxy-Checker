package com.example.telegramproxychecker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

suspend fun checkTcpProxy(proxy: MtProxy, timeoutMs: Int = 5000): MtProxy {
    return withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val start = System.nanoTime()

        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(proxy.server, proxy.port),
                    timeoutMs
                )
            }

            proxy.copy(
                tcpOk = true,
                tcpPingMs = (System.nanoTime() - start) / 1_000_000
            )
        } catch (_: Exception) {
            coroutineContext.ensureActive()
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
