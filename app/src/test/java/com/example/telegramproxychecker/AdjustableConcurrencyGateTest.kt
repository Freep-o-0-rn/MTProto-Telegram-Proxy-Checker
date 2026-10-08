package com.example.telegramproxychecker

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustableConcurrencyGateTest {
    @Test
    fun oneAndThirtyHaveCorrespondingInFlightLimits() = runTest {
        for (limit in listOf(1, 6, 15, 30)) {
            val gate = AdjustableConcurrencyGate(limit)
            var active = 0
            var peak = 0
            val jobs = (1..50).map {
                async {
                    gate.withPermit {
                        active++
                        peak = maxOf(peak, active)
                        delay(100)
                        active--
                    }
                }
            }
            jobs.awaitAll()
            assertEquals(limit, peak)
            assertEquals(0, active)
        }
    }

    @Test
    fun releaseAfterCancellationDoesNotStrandFutureRequests() = runTest {
        val gate = AdjustableConcurrencyGate(1)
        val stuck = async { gate.withPermit { delay(5000) } }
        runCurrent()
        val waiting = async { gate.withPermit { "passed" } }
        runCurrent()
        assertTrue(!waiting.isCompleted)
        stuck.cancelAndJoin()
        assertEquals("passed", waiting.await())
    }

    @Test
    fun reconfigurationDoesNotAbortActiveRequest() = runTest {
        val gate = AdjustableConcurrencyGate(1)
        var active = 0
        var peak = 0
        val jobs = (1..15).map {
            async {
                gate.withPermit {
                    active++
                    peak = maxOf(peak, active)
                    delay(100)
                    active--
                }
            }
        }
        runCurrent()
        assertEquals(1, active)
        gate.configure(5)
        jobs.awaitAll()
        assertTrue(peak in 2..5)
        assertEquals(0, active)
    }
}
