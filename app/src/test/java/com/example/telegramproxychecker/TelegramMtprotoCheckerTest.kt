package com.example.telegramproxychecker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal fun testProxy(id: Int = 0) = MtProxy(
    originalUrl = "https://t.me/proxy?server=proxy$id.example&port=443&secret=test",
    server = "proxy$id.example",
    port = 443,
    secret = "test",
    tcpOk = true
)

internal class FakeTelegramClient(
    private val scope: CoroutineScope,
    private val answer: (Int) -> Pair<Long, TelegramCheckResult>?
) : TelegramProbeClient {
    val started = mutableListOf<Int>()
    val callbacks = mutableListOf<(TelegramCheckResult) -> Unit>()
    val timeouts = mutableListOf<Double>()
    private val jobs = mutableListOf<Job>()
    var closed = 0

    override fun send(
        proxy: MtProxy,
        dcId: Int,
        timeoutSeconds: Double,
        onResult: (TelegramCheckResult) -> Unit
    ) {
        started += dcId
        callbacks += onResult
        timeouts += timeoutSeconds
        answer(dcId)?.let { (latency, result) ->
            jobs += scope.launch {
                delay(latency)
                onResult(result)
            }
        }
    }

    override fun close() {
        closed++
        jobs.forEach { it.cancel() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramMtprotoCheckerTest {
    @Test
    fun fastFirstDcDoesNotStartExtraConnections() = runTest {
        val client = FakeTelegramClient(backgroundScope) {
            100L to TelegramCheckResult(true, 100, null)
        }
        val checker = TelegramMtprotoChecker({ client }, StandardTestDispatcher(testScheduler))
        val result = checker.check(testProxy())

        assertEquals(true, result.telegramOk)
        assertEquals(100L, result.telegramPingMs)
        assertEquals(100L, currentTime)
        assertEquals(listOf(1), client.started)
        assertEquals(listOf(5.0), client.timeouts)
        assertEquals(1, client.closed)
    }

    @Test
    fun fifthDcCanSucceedWithoutWaitingForFourTimeouts() = runTest {
        val client = FakeTelegramClient(backgroundScope) { dcId ->
            if (dcId == 5) 100L to TelegramCheckResult(true, 100, null) else null
        }
        val checker = TelegramMtprotoChecker({ client }, StandardTestDispatcher(testScheduler))
        val result = checker.check(testProxy())

        assertEquals(true, result.telegramOk)
        assertEquals(900L, currentTime)
        assertEquals((1..5).toList(), client.started)
        assertEquals(1, client.closed)
        // Late native callbacks after another DC won must not resume a finished scan.
        client.callbacks.forEach { it(TelegramCheckResult(false, null, "late response")) }
        assertEquals(true, result.telegramOk)
    }

    @Test
    fun failureWaitsForEveryDcAndKeepsDeterministicError() = runTest {
        val client = FakeTelegramClient(backgroundScope) { dcId ->
            (if (dcId == 1) 3000L else 20L) to TelegramCheckResult(false, null, "failure $dcId")
        }
        val result = TelegramMtprotoChecker({ client }, StandardTestDispatcher(testScheduler))
            .check(testProxy())

        assertEquals(false, result.telegramOk)
        assertEquals("DC1: failure 1", result.telegramError)
        assertEquals(3000L, currentTime)
        assertEquals((1..5).toList(), client.started)
        assertEquals(1, client.closed)
    }

    @Test
    fun missingCallbacksTimeoutAndCloseTheClient() = runTest {
        val client = FakeTelegramClient(backgroundScope) { null }
        val result = TelegramMtprotoChecker({ client }, StandardTestDispatcher(testScheduler))
            .check(testProxy())

        assertEquals(false, result.telegramOk)
        assertEquals("DC1: TDLib timeout", result.telegramError)
        assertEquals(7800L, currentTime)
        assertEquals(1, client.closed)
        client.callbacks.forEach { it(TelegramCheckResult(true, 1, null)) }
    }

    @Test
    fun cancellationClosesClientAndDoesNotStartRemainingDcs() = runTest {
        val client = FakeTelegramClient(backgroundScope) { null }
        val checker = TelegramMtprotoChecker({ client }, StandardTestDispatcher(testScheduler))
        val scan = async { checker.check(testProxy()) }
        advanceTimeBy(250)
        runCurrent()
        scan.cancelAndJoin()

        assertTrue(scan.isCancelled)
        assertEquals(listOf(1, 2), client.started)
        assertEquals(1, client.closed)
        client.callbacks.forEach { it(TelegramCheckResult(true, 1, null)) }
    }

    @Test
    fun tcpFailureNeverCreatesTdlibClient() = runTest {
        val checker = TelegramMtprotoChecker({ error("Must not create client") })
        val result = checker.check(testProxy().copy(tcpOk = false))
        assertEquals(false, result.telegramOk)
        assertEquals("TCP недоступен", result.telegramError)
    }
}
