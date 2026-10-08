package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyDiagnosticsTest {
    @Test
    fun countsActualTelegramFailuresByTheirOriginalError() {
        val proxies = listOf(
            testProxy(1).copy(telegramOk = false, telegramError = "TDLib 400: invalid secret"),
            testProxy(2).copy(telegramOk = false, telegramError = "TDLib 400: invalid secret"),
            testProxy(3).copy(telegramOk = false, telegramError = "TDLib 500: timeout"),
            testProxy(4).copy(tcpOk = false, telegramOk = false, telegramError = "TCP недоступен"),
            testProxy(6).copy(tcpOk = false, telegramOk = false, telegramError = "TDLib 500: timeout"),
            testProxy(5).copy(telegramOk = true, telegramError = null)
        )
        val top = topProxyFailures(proxies)
        assertEquals(2, top.first().count)
        assertEquals("TDLib 400: invalid secret", top.first().reason)
        assertEquals(3, top.size)
    }

    @Test
    fun copiedDiagnosticsIncludeErrorsButNeverExposeMtprotoSecrets() {
        val proxy = testProxy(1).copy(
            secret = "top-secret-should-not-appear",
            originalUrl = "https://t.me/proxy?secret=top-secret-should-not-appear",
            telegramOk = false,
            telegramError = "TDLib 400: error",
            checkedAt = 1234
        )
        val report = buildProxyDiagnostics(listOf(proxy), 1, 1)
        assertTrue(report.contains("TDLib 400: error"))
        assertTrue(report.contains("proxy1.example:443"))
        assertFalse(report.contains("top-secret-should-not-appear"))
    }
}
