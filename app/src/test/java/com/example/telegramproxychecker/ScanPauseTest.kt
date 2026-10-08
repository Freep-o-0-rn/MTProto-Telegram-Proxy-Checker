package com.example.telegramproxychecker

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pausing holds the existing workers, without reloading the source or discarding the queue. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanPauseTest {
    @Test
    fun pausedQueueDoesNotStartNetworkChecksAndResumesWithoutReload() = runTest {
        val source = (0 until 12).map { testProxy(it) }
        val gate = MutableStateFlow(false)
        var sourcesLoaded = 0
        var tcpAttempts = 0
        var completed = 0
        val repository = ProxyRepository(
            sourceLoader = { sourcesLoaded++; source },
            tcpCheck = { tcpAttempts++; delay(10); it.copy(tcpOk = true) },
            telegramCheck = { delay(10); it.copy(telegramOk = true) },
            nowMillis = { currentTime }
        )

        val job = async {
            repository.loadAndCheckProxies(
                cachedProxies = emptyList(),
                onProgress = { checked, _ -> completed = checked },
                beforeCheck = { gate.first { it } }
            )
        }
        runCurrent()
        assertEquals(1, sourcesLoaded)
        assertEquals(0, tcpAttempts)
        assertEquals(0, completed)

        gate.value = true
        advanceUntilIdle()
        assertEquals(12, completed)
        assertEquals(12, tcpAttempts)
        assertEquals(1, sourcesLoaded)
        assertTrue(job.await().all { it.telegramOk == true })
    }
}
