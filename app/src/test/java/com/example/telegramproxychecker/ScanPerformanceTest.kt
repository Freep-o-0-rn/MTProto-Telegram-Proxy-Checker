package com.example.telegramproxychecker

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic scheduling benchmark, not a measurement of a real network or Android CPU. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanPerformanceTest {
    @Test
    fun compareWithOriginalSchedulerOnIdenticalSimulatedResponses() = runTest {
        val source = (0 until 60).map { testProxy(it) }
        val scenarios = listOf<Pair<String, (Int) -> Pair<Long, TelegramCheckResult>>>(
            "fast first DC" to { 100L to TelegramCheckResult(true, 100, null) },
            "all DCs timeout" to { 5000L to TelegramCheckResult(false, null, "timeout") },
            "only DC5 works" to { dcId ->
                if (dcId == 5) 100L to TelegramCheckResult(true, 100, null)
                else 5000L to TelegramCheckResult(false, null, "timeout")
            }
        )

        for ((name, answer) in scenarios) {
            val oldStart = currentTime
            val baseline = coroutineScope {
                val slots = Semaphore(3)
                source.map { proxy ->
                    async {
                        slots.withPermit {
                            delay(50) // Identical TCP connection latency in both versions.
                            var ok = false
                            for (dcId in 1..5) {
                                val (latency, result) = answer(dcId)
                                delay(latency)
                                if (result.ok) {
                                    ok = true
                                    break
                                }
                            }
                            proxy.copy(telegramOk = ok)
                        }
                    }
                }.awaitAll()
            }
            val oldDuration = currentTime - oldStart

            val checker = TelegramMtprotoChecker(
                { FakeTelegramClient(backgroundScope, answer) },
                StandardTestDispatcher(testScheduler)
            )
            val repository = ProxyRepository(
                sourceLoader = { source },
                tcpCheck = { delay(50); it },
                telegramCheck = checker::check,
                nowMillis = { currentTime }
            )
            val newStart = currentTime
            val optimized = repository.loadAndCheckProxies(emptyList())
            val newDuration = currentTime - newStart

            assertEquals(
                baseline.associate { it.cacheKey to it.telegramOk },
                optimized.associate { it.cacheKey to it.telegramOk }
            )
            assertTrue("$name should improve without changing classifications", newDuration < oldDuration)
            println("Simulated 60 proxies, $name: original=${oldDuration}ms optimized=${newDuration}ms")
        }
    }
}
