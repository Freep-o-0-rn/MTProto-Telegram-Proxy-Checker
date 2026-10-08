package com.example.telegramproxychecker

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProxyDataCleanupTest {
    private fun proxy() = MtProxy(
        originalUrl = "https://t.me/proxy?server=example.org&port=443&secret=abc",
        server = "example.org",
        port = 443,
        secret = "abc",
        tcpOk = true,
        telegramOk = true,
        isFavorite = true
    )

    @Test
    fun clearResetsLiveListAndProgressOnlyAfterStorageSuccess() = runTest {
        ScanSession.updateProxies(listOf(proxy()))
        ScanSession.progress(2, 5)
        var operations = 0
        val result = ScanSession.clearProxyData {
            operations++
            ProxyCleanupResult(1, 100, true)
        }
        assertEquals(1, operations)
        assertEquals(1, result.checkedProxies)
        assertEquals(100, result.sourceEntries)
        assertTrue(result.compacted)
        assertTrue(ScanSession.state.value.proxies.isEmpty())
        assertEquals(0, ScanSession.state.value.checked)
        assertEquals(0, ScanSession.state.value.total)
        assertFalse(ScanSession.state.value.running)
    }

    @Test
    fun failedStorageTransactionKeepsLiveProxies() = runTest {
        ScanSession.updateProxies(listOf(proxy()))
        try {
            ScanSession.clearProxyData { error("database error") }
            fail("Expected storage error")
        } catch (e: IllegalStateException) {
            assertEquals("database error", e.message)
            assertEquals(1, ScanSession.state.value.proxies.size)
        } finally {
            ScanSession.clearProxyData { ProxyCleanupResult(0, 0, true) }
        }
    }

    @Test
    fun clearCannotRunWhileScanningEvenIfPaused() = runTest {
        ScanSession.start()
        ScanSession.pause()
        var invoked = false
        try {
            ScanSession.clearProxyData {
                invoked = true
                ProxyCleanupResult(1, 1, true)
            }
            fail("Clearing while scan is running must be refused")
        } catch (_: IllegalStateException) {
            assertFalse(invoked)
            assertTrue(ScanSession.state.value.running)
        } finally {
            ScanSession.finish()
            ScanSession.clearProxyData { ProxyCleanupResult(0, 0, true) }
        }
    }
}
