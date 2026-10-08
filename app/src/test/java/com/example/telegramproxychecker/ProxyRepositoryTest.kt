package com.example.telegramproxychecker

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyRepositoryTest {
    @Test
    fun boundedWorkersPublishResultsBeforeScanFinishes() = runTest {
        val source = (0 until 20).map { testProxy(it) }
        var active = 0
        var peak = 0
        val progress = mutableListOf<Pair<Int, Int>>()
        val snapshots = mutableListOf<List<MtProxy>>()
        val repository = ProxyRepository(
            sourceLoader = { source },
            tcpCheck = {
                active++
                peak = maxOf(peak, active)
                delay(50)
                it.copy(tcpOk = true)
            },
            telegramCheck = {
                delay(100)
                active--
                it.copy(telegramOk = true)
            },
            nowMillis = { 10_000L + currentTime }
        )
        val scan = async {
            repository.loadAndCheckProxies(emptyList(), onUpdate = { snapshots += it }) { done, total ->
                progress += done to total
            }
        }
        advanceTimeBy(151)
        runCurrent()
        assertFalse(scan.isCompleted)
        assertTrue(snapshots.any { list -> list.any { it.telegramOk == true } })
        val result = scan.await()

        assertEquals(6, peak)
        assertEquals(0, active)
        assertEquals(20, result.size)
        assertTrue(result.all { it.telegramOk == true })
        assertTrue(result.all { it.checkedAt!! >= 10_150L })
        assertEquals((0..20).map { it to 20 }, progress)
        assertEquals(result, snapshots.last())
        assertTrue("Batch UI updates instead of publishing every result", snapshots.size < 20)
    }

    @Test
    fun bulkAndManualChecksShareOneConcurrencyLimit() = runTest {
        var active = 0
        var peak = 0
        val repository = ProxyRepository(
            sourceLoader = { (0 until 12).map { testProxy(it) } },
            tcpCheck = { it },
            telegramCheck = {
                active++
                peak = maxOf(peak, active)
                delay(100)
                active--
                it.copy(telegramOk = true)
            }
        )
        val bulk = async { repository.loadAndCheckProxies(emptyList()) }
        val manual = (20 until 30).map { async { repository.recheckOneProxy(testProxy(it)) } }
        bulk.await()
        manual.awaitAll()
        assertEquals(6, peak)
        assertEquals(0, active)
    }

    @Test
    fun freshResultsAndMissingFavoritesSurviveRefresh() = runTest {
        val favorite = testProxy(99).copy(isFavorite = true, checkedAt = 999L, telegramOk = true)
        val fresh = testProxy(0).copy(checkedAt = 999L, telegramOk = true)
        val stale = testProxy(1).copy(checkedAt = 1L, telegramOk = false)
        val checked = mutableListOf<String>()
        val repository = ProxyRepository(
            sourceLoader = { listOf(testProxy(0), testProxy(1)) },
            tcpCheck = { checked += it.cacheKey; it.copy(tcpOk = false, telegramOk = false) },
            telegramCheck = { error("TCP failure must skip Telegram") },
            nowMillis = { 1_800_001L }
        )
        val result = repository.loadAndCheckProxies(listOf(favorite, fresh, stale))
        assertEquals(listOf(stale.cacheKey), checked)
        assertEquals(favorite, result.first())
        assertEquals(fresh, result.single { it.cacheKey == fresh.cacheKey })
    }

    @Test
    fun allStaleFailuresAreCheckedWithoutTwentyItemLimit() = runTest {
        val cached = (0 until 50).map { testProxy(it).copy(checkedAt = 1L, telegramOk = false) }
        var checks = 0
        val progress = mutableListOf<Pair<Int, Int>>()
        val repository = ProxyRepository(
            sourceLoader = { cached },
            tcpCheck = { checks++; it.copy(tcpOk = false) },
            telegramCheck = { error("Unexpected Telegram check") },
            nowMillis = { 3_600_000L }
        )
        val result = repository.loadAndCheckProxies(cached, onProgress = { done, total ->
            progress += done to total
        })
        assertEquals(50, checks)
        assertEquals(50, result.size)
        assertEquals(0 to 50, progress.first())
        assertEquals(50 to 50, progress.last())
    }

    @Test
    fun sourceFailureFallsBackToCacheButCancellationDoesNot() = runTest {
        val cached = listOf(testProxy().copy(checkedAt = 999L))
        var tcpChecks = 0
        suspend fun load(error: Exception) = ProxyRepository(
            sourceLoader = { throw error },
            tcpCheck = { tcpChecks++; it },
            telegramCheck = { it },
            nowMillis = { 1000L }
        ).loadAndCheckProxies(cached)

        assertEquals(cached, load(IOException("offline")))
        try {
            load(CancellationException("cancelled"))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, tcpChecks)
        }
    }

    @Test
    fun cancellingScanStopsWorkersAndReleasesPermits() = runTest {
        var active = 0
        val repository = ProxyRepository(
            sourceLoader = { (0 until 100).map { testProxy(it) } },
            tcpCheck = { it },
            telegramCheck = {
                active++
                try {
                    delay(5000)
                    it.copy(telegramOk = true)
                } finally {
                    active--
                }
            }
        )
        val scan = async { repository.loadAndCheckProxies(emptyList()) }
        runCurrent()
        assertEquals(6, active)
        scan.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(true, repository.recheckOneProxy(testProxy()).telegramOk)
    }

    @Test
    fun progressiveResultsRespectBothFavoriteAdditionAndRemoval() {
        val snapshot = listOf(testProxy(1).copy(isFavorite = true), testProxy(2))
        val current = listOf(testProxy(1), testProxy(2).copy(isFavorite = true))
        val merged = snapshot.withFavoritesFrom(current)
        assertFalse(merged[0].isFavorite)
        assertTrue(merged[1].isFavorite)
    }

    @Test
    fun incrementalScanChecksOldestFailuresButSkipsFreshOnes() = runTest {
        val now = 3_600_000L
        val source = listOf(
            testProxy(0).copy(checkedAt = 100L, telegramOk = false),
            testProxy(1).copy(checkedAt = now - 10_000L, telegramOk = false),
            testProxy(2).copy(checkedAt = 1L, telegramOk = false),
            testProxy(3).copy(checkedAt = 50L, telegramOk = false)
        )
        val attempted = mutableListOf<String>()
        val repository = ProxyRepository(
            sourceLoader = { source },
            tcpCheck = { attempted += it.cacheKey; it.copy(tcpOk = false) },
            telegramCheck = { error("No Telegram test on a failed TCP precheck") },
            nowMillis = { now }
        )
        repository.loadAndCheckProxies(source)
        assertEquals(listOf(source[2], source[3], source[0]).map { it.cacheKey }, attempted)
    }

    @Test
    fun quickCheckRetestsEveryPreviousTcpOkEvenWhenCacheIsFresh() = runTest {
        val source = listOf(
            testProxy(0).copy(tcpOk = true, checkedAt = 3_599_000L, telegramOk = true),
            testProxy(1).copy(tcpOk = true, checkedAt = 3_599_000L, telegramOk = false),
            testProxy(2).copy(tcpOk = false, checkedAt = 1L, telegramOk = false),
            testProxy(3).copy(checkedAt = null)
        )
        val attempted = mutableListOf<String>()
        val telegramAttempts = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val repository = ProxyRepository(
            sourceLoader = { source },
            tcpCheck = { attempted += it.cacheKey; it.copy(tcpOk = false) },
            telegramCheck = {
                telegramAttempts += it.cacheKey
                it.copy(telegramOk = true, telegramPingMs = 200L)
            },
            nowMillis = { 3_600_000L }
        )
        val result = repository.loadAndCheckProxies(
            cachedProxies = source,
            tcpOkOnly = true,
            onProgress = { checked, total -> progress += checked to total }
        )

        assertEquals(source.take(2).map { it.cacheKey }.toSet(), attempted.toSet())
        assertEquals(source.take(2).map { it.cacheKey }.toSet(), telegramAttempts.toSet())
        assertEquals(0 to 2, progress.first())
        assertEquals(2 to 2, progress.last())
        assertEquals(4, result.size)
        assertTrue(result.filter { it.cacheKey in attempted }.all { it.telegramOk == true })
        assertEquals(false, result.single { it.cacheKey == source[2].cacheKey }.telegramOk)
    }

    @Test
    fun quickCheckWithoutTcpOkDoesNotRetryEveryFailedProxy() = runTest {
        val source = (0 until 8).map { testProxy(it).copy(tcpOk = false, telegramOk = false) }
        var checks = 0
        val repository = ProxyRepository(
            sourceLoader = { source },
            tcpCheck = { checks++; it },
            telegramCheck = { error("Quick check should have no work") }
        )
        val progress = mutableListOf<Pair<Int, Int>>()
        repository.loadAndCheckProxies(source, tcpOkOnly = true,
            onProgress = { checked, total -> progress += checked to total })
        assertEquals(0, checks)
        assertEquals(listOf(0 to 0), progress)
    }

    @Test
    fun forcedScanChecksEveryCachedFailure() = runTest {
        val stale = (0 until 50).map {
            testProxy(it).copy(checkedAt = 5L, telegramOk = false)
        }
        val requested = mutableListOf<String>()
        val deepChecks = mutableListOf<String>()
        val repository = ProxyRepository(
            sourceLoader = { stale },
            tcpCheck = { requested += it.cacheKey; it.copy(tcpOk = false) },
            telegramCheck = {
                deepChecks += it.cacheKey
                it.copy(telegramOk = false, telegramError = "TDLib timeout")
            },
            nowMillis = { 3_600_000L }
        )
        val refreshed = repository.loadAndCheckProxies(stale, force = true)
        assertEquals(50, requested.distinct().size)
        assertEquals(50, deepChecks.distinct().size)
        assertEquals(50, refreshed.size)
    }

}
