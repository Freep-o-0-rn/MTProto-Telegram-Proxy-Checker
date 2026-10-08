package com.example.telegramproxychecker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * Validates the SOCKS5 protocol, not just an open TCP port (RFC 1928/1929).
 * TDLib TestProxy is responsible for verifying real Telegram reachability.
 */
internal suspend fun checkSocks5Handshake(
    proxy: MtProxy,
    timeoutMs: Int = 3500
): MtProxy = withContext(Dispatchers.IO) {
    require(proxy.protocol == ProxySourceProtocol.SOCKS5)
    coroutineContext.ensureActive()
    val start = System.nanoTime()
    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxy.server, proxy.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            val authRequired = !proxy.username.isNullOrEmpty() || !proxy.password.isNullOrEmpty()
            if (authRequired) {
                output.write(byteArrayOf(5, 2, 0, 2))
            } else {
                output.write(byteArrayOf(5, 1, 0))
            }
            output.flush()
            val version = input.read()
            val method = input.read()
            if (version != 5 || (method != 0 && method != 2)) {
                error("Неверный ответ SOCKS5")
            }
            if (method == 2) {
                if (!authRequired) error("SOCKS5 требует авторизацию")
                val user = proxy.username.orEmpty().toByteArray(Charsets.UTF_8)
                val pass = proxy.password.orEmpty().toByteArray(Charsets.UTF_8)
                if (user.isEmpty() || user.size > 255 || pass.size > 255) {
                    error("Недопустимая длина логина или пароля SOCKS5")
                }
                output.write(byteArrayOf(1, user.size.toByte()))
                output.write(user)
                output.write(pass.size)
                output.write(pass)
                output.flush()
                if (input.read() != 1 || input.read() != 0) error("SOCKS5: ошибка авторизации")
            }
        }
        proxy.copy(
            tcpOk = true,
            tcpPingMs = (System.nanoTime() - start) / 1_000_000,
            telegramOk = null,
            telegramPingMs = null,
            telegramError = null
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        coroutineContext.ensureActive()
        proxy.copy(
            tcpOk = false,
            tcpPingMs = null,
            telegramOk = false,
            telegramPingMs = null,
            telegramError = "SOCKS5: ${e.message?.take(140) ?: "недоступен"}"
        )
    }
}
