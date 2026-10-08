package com.example.telegramproxychecker

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerConcurrencyTest {
    private fun proxy(i: Int) = MtProxy(
        originalUrl = "tg://proxy?server=server$i.example&port=443&secret=abc",
        server = "server$i.example",
        port = 443,
        secret = "abc"
    )

    @Test
    fun thirtyWorkersCanPrecheckFasterWhileTdlibRemainsAtSix() = runTest {
        val proxies = (0 until 48).map(::proxy)
        var activeTcp = 0
        var peakTcp = 0
        var activeTelegram = 0
        var peakTelegram = 0
        val repo = ProxyRepository(
            sourceLoader = { proxies },
            tcpCheck = {
                activeTcp++
                peakTcp = maxOf(activeTcp, peakTcp)
                try {
                    delay(60)
                    it.copy(tcpOk = true)
                } finally {
                    activeTcp--
                }
            },
            telegramCheck = {
                activeTelegram++
                peakTelegram = maxOf(activeTelegram, peakTelegram)
                try {
                    delay(100)
                    it.copy(telegramOk = true)
                } finally {
                    activeTelegram--
                }
            }
        )
        val result = repo.loadAndCheckProxies(emptyList(), force = true, parallelChecks = 30)
        assertEquals(48, result.count { it.telegramOk == true })
        assertTrue("Prechecks should have multiple independent sockets", peakTcp > 6)
        assertTrue(peakTcp <= 30)
        assertEquals("More workers must NOT create >6 TDLib clients", 6, peakTelegram)
        assertEquals(0, activeTcp)
        assertEquals(0, activeTelegram)
    }

    @Test
    fun manualRechecksAndBulkShareTheConfiguredAdmissionLimit() = runTest {
        var active = 0
        var peak = 0
        val repo = ProxyRepository(
            sourceLoader = { (0 until 40).map(::proxy) },
            tcpCheck = {
                active++
                peak = maxOf(active, peak)
                try {
                    delay(50)
                    it.copy(tcpOk = false, telegramOk = false)
                } finally {
                    active--
                }
            },
            // Individual MTProto rechecks deliberately probe TDLib after TCP FAIL.
            telegramCheck = { it.copy(telegramOk = false) }
        )
        val bulk = async { repo.loadAndCheckProxies(emptyList(), parallelChecks = 12) }
        runCurrent()
        val manual = (50 until 65).map { async { repo.recheckOneProxy(proxy(it)) } }
        bulk.await()
        manual.awaitAll()
        assertTrue(peak <= 12)
        assertTrue(peak > 6)
        assertEquals(0, active)
    }

    @Test
    fun resultsRemainTheSameWithOneSixOrThirtyWorkers() = runTest {
        val proxies = (0 until 24).map(::proxy)
        val outputs = mutableListOf<Map<String, Boolean?>>()
        for (parallel in listOf(1, 6, 30)) {
            val repo = ProxyRepository(
                sourceLoader = { proxies },
                tcpCheck = {
                    delay(2)
                    it.copy(tcpOk = true)
                },
                telegramCheck = {
                    delay(2)
                    val id = it.server.removePrefix("server").substringBefore('.').toInt()
                    it.copy(telegramOk = id % 4 == 0)
                }
            )
            val completed = repo.loadAndCheckProxies(
                emptyList(), force = true, parallelChecks = parallel
            )
            outputs += completed.associate { it.cacheKey to it.telegramOk }
        }
        assertEquals(outputs[0], outputs[1])
        assertEquals(outputs[1], outputs[2])
    }
}
