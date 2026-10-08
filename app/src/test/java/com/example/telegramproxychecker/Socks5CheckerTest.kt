package com.example.telegramproxychecker

import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5CheckerTest {
    private fun proxy(port: Int) = MtProxy(
        originalUrl = "https://t.me/socks?server=127.0.0.1&port=$port",
        server = "127.0.0.1", port = port, secret = "",
        protocol = ProxySourceProtocol.SOCKS5, sourceId = "hookzof-socks5"
    )

    @Test
    fun acceptsActualSocks5GreetingNotJustOpenTcpPort() = runTest {
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val listener = thread {
                server.accept().use { socket ->
                    val greet = ByteArray(3)
                    socket.getInputStream().read(greet)
                    assertEquals(listOf(5, 1, 0), greet.map { it.toInt() })
                    socket.getOutputStream().write(byteArrayOf(5, 0))
                }
            }
            val result = checkSocks5Handshake(proxy(server.localPort), 1500)
            listener.join(3000)
            assertEquals(true, result.tcpOk)
            assertEquals(null, result.telegramOk) // Only real TDLib can say Telegram OK.
        }
    }

    @Test
    fun openTcpPortWithNonSocksReplyIsRejected() = runTest {
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val listener = thread {
                server.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(3))
                    socket.getOutputStream().write(byteArrayOf(0, 0))
                }
            }
            val result = checkSocks5Handshake(proxy(server.localPort), 1500)
            listener.join(3000)
            assertEquals(false, result.tcpOk)
            assertEquals(false, result.telegramOk)
            assertTrue(result.telegramError.orEmpty().startsWith("SOCKS5:"))
        }
    }

    @Test
    fun quickAndFullScansUseSocksValidationBeforeTdlib() = runTest {
        val good = proxy(8081)
        val bad = proxy(8082)
        val attempted = mutableListOf<String>()
        val verified = mutableListOf<String>()
        val repo = ProxyRepository(
            sourceLoader = { emptyList() },
            tcpCheck = { error("Must not use MTProto socket checker for SOCKS5") },
            socksCheck = {
                attempted += it.cacheKey
                it.copy(
                    tcpOk = it.port == 8081,
                    telegramOk = if (it.port == 8081) null else false,
                    telegramError = if (it.port == 8081) null else "SOCKS5: invalid greeting"
                )
            },
            telegramCheck = {
                verified += it.cacheKey
                it.copy(telegramOk = true, telegramPingMs = 321)
            },
            nowMillis = { 1000L }
        )
        val result = repo.loadAndCheckProxies(
            cachedProxies = emptyList(), mtprotoEnabled = false,
            socksProxies = listOf(good, bad), force = true
        )
        assertEquals(2, attempted.size)
        assertEquals(listOf(good.cacheKey), verified)
        assertEquals(1, result.count { it.telegramOk == true })
        assertEquals(1, result.count { it.telegramOk == false })
    }

    @Test
    fun configuredLimitAppliesToSocksBatchAndDoesNotReportUncheckedAsOk() = runTest {
        val list = (1..9).map { proxy(10000 + it) }
        var checks = 0
        val repo = ProxyRepository(
            sourceLoader = { emptyList() },
            tcpCheck = { error("Wrong checker") },
            socksCheck = { checks++; it.copy(tcpOk = false, telegramOk = false) },
            telegramCheck = { error("No successful SOCKS handshake") }
        )
        val result = repo.loadAndCheckProxies(emptyList(), force = true,
            mtprotoEnabled = false, socksProxies = list, scanLimit = 3)
        assertEquals(3, checks)
        assertEquals(9, result.size)
        assertEquals(6, result.count { it.telegramOk == null })
    }
}
