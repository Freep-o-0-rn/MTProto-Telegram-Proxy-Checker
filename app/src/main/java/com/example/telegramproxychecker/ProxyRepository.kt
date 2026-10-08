package com.example.telegramproxychecker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.HttpURLConnection

private const val PROXY_LIST_URL =
    "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"

private const val CHECK_FRESH_MS = 30L * 60L * 1000L
private const val RESULT_UPDATE_INTERVAL_MS = 200L

class ProxyRepository internal constructor(
    private val sourceLoader: suspend () -> List<MtProxy>,
    private val tcpCheck: suspend (MtProxy) -> MtProxy,
    private val telegramCheck: suspend (MtProxy) -> MtProxy,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val socksCheck: suspend (MtProxy) -> MtProxy = { checkSocks5Handshake(it) }
) {
    constructor() : this(
        sourceLoader = ::downloadProxies,
        tcpCheck = { checkTcpProxy(it) },
        telegramCheck = TelegramMtprotoChecker()::check
    )

    // Shared by bulk and individual checks; extra taps cannot bypass the limit.
    private val checkSlots = AdjustableConcurrencyGate(ScanConcurrencyPolicy.DEFAULT_WORKERS)
    private val telegramSlots = Semaphore(ScanConcurrencyPolicy.MAX_TDLIB_CHECKS)

    fun configureParallelChecks(value: Int) {
        checkSlots.configure(value)
    }

    suspend fun loadProxies(): List<MtProxy> = sourceLoader()

    suspend fun recheckOneProxy(proxy: MtProxy): MtProxy {
        return checkSingleProxy(proxy, verifyDespiteTcpFailure = true)
    }

    suspend fun loadAndCheckProxies(
        cachedProxies: List<MtProxy>,
        force: Boolean = false,
        onUpdate: (List<MtProxy>) -> Unit = {},
        beforeCheck: suspend () -> Unit = {},
        tcpOkOnly: Boolean = false,
        mtprotoEnabled: Boolean = true,
        socksProxies: List<MtProxy> = emptyList(),
        scanLimit: Int? = null,
        parallelChecks: Int = ScanConcurrencyPolicy.DEFAULT_WORKERS,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): List<MtProxy> {
        // One value is frozen for the entire run. This gate is shared with
        // manual rechecks and never exceeds the configured global limit.
        val workerCount = ScanConcurrencyPolicy.clamp(parallelChecks)
        configureParallelChecks(workerCount)
        val sourceProxies = try {
            val githubProxies = if (mtprotoEnabled) loadProxies() else emptyList()
            mergeGithubWithCache(githubProxies + socksProxies, cachedProxies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cachedProxies.isNotEmpty()) {
                cachedProxies
            } else {
                throw e
            }
        }

        // Previously checked SOCKS5 entries remain visible in the result list,
        // but only the selected rotating batch may enter a full scan.
        val selectedSocksKeys = socksProxies.mapTo(HashSet()) { it.cacheKey }
        val eligible = sourceProxies.filter {
            when (it.protocol) {
                ProxySourceProtocol.MTPROTO -> mtprotoEnabled
                ProxySourceProtocol.SOCKS5 -> if (tcpOkOnly) selectedSocksKeys.contains(it.cacheKey) ||
                    it.tcpOk == true else it.cacheKey in selectedSocksKeys
                else -> false
            }
        }
        val candidateQueue = buildCheckQueue(eligible, force, tcpOkOnly)
        val queue = if (scanLimit == null) candidateQueue else
            interleaveSourceQueues(candidateQueue).take(scanLimit.coerceAtLeast(1))

        onProgress(0, queue.size)
        onUpdate(sortProxies(sourceProxies))

        if (queue.isEmpty()) {
            return sortProxies(sourceProxies)
        }

        return coroutineScope {
            val pending = Channel<MtProxy>(workerCount)
            val completed = Channel<MtProxy>(workerCount)
            launch {
                for (proxy in queue) pending.send(proxy)
                pending.close()
            }
            // Fixed worker count instead of allocating one suspended async per proxy.
            repeat(minOf(workerCount, queue.size)) {
                launch {
                    for (proxy in pending) {
                        beforeCheck()
                        completed.send(checkSingleProxy(proxy, verifyDespiteTcpFailure = force || tcpOkOnly))
                    }
                }
            }

            val current = sourceProxies.associateByTo(LinkedHashMap()) { it.cacheKey }
            var lastUpdate = nowMillis()
            var lastList = emptyList<MtProxy>()
            // One collector owns progress and snapshots, even on a multi-threaded caller.
            try {
                repeat(queue.size) { index ->
                    val result = completed.receive()
                current[result.cacheKey] = result
                onProgress(index + 1, queue.size)
                val now = nowMillis()
                if (index == 0 || index == queue.lastIndex || now - lastUpdate >= RESULT_UPDATE_INTERVAL_MS) {
                    lastList = sortProxies(current.values.toList())
                    onUpdate(lastList)
                    lastUpdate = now
                }
            }
                lastList
            } finally {
                // Publish the latest completed results even if Stop cancels the collector.
                val finalSnapshot = sortProxies(current.values.toList())
                if (lastList != finalSnapshot) onUpdate(finalSnapshot)
            }
        }
    }

    private suspend fun checkSingleProxy(
        proxy: MtProxy,
        verifyDespiteTcpFailure: Boolean = false
    ): MtProxy = checkSlots.withPermit {
        val tcpChecked = when (proxy.protocol) {
            ProxySourceProtocol.MTPROTO -> tcpCheck(proxy)
            ProxySourceProtocol.SOCKS5 -> socksCheck(proxy)
            else -> error("Unsupported proxy type: ${proxy.protocol}")
        }

        val telegramChecked = if (tcpChecked.tcpOk == true ||
            (verifyDespiteTcpFailure && proxy.protocol == ProxySourceProtocol.MTPROTO)) {
            // Each proxy owns a TDLib client and fans out to five DC requests.
            // Cap TDLib separately: faster prechecks must not amplify native
            // TestProxy concurrency beyond the previously tested six clients.
            telegramSlots.withPermit { telegramCheck(tcpChecked) }
        } else {
            tcpChecked
        }

        telegramChecked.copy(
            checkedAt = nowMillis(),
            isFavorite = proxy.isFavorite
        )
    }

    private fun mergeGithubWithCache(
        githubProxies: List<MtProxy>,
        cachedProxies: List<MtProxy>
    ): List<MtProxy> {
        val cachedMap = cachedProxies.associateBy { it.cacheKey }

        val mergedFromGithub = githubProxies.map { fresh ->
            val cached = cachedMap[fresh.cacheKey]

            if (cached == null) {
                fresh
            } else {
                fresh.copy(
                    tcpOk = cached.tcpOk,
                    tcpPingMs = cached.tcpPingMs,
                    telegramOk = cached.telegramOk,
                    telegramPingMs = cached.telegramPingMs,
                    telegramError = cached.telegramError,
                    checkedAt = cached.checkedAt,
                    isFavorite = cached.isFavorite
                )
            }
        }

        val githubKeys = githubProxies.map { it.cacheKey }.toSet()

        val retained = cachedProxies.filter {
            it.cacheKey !in githubKeys &&
                (it.isFavorite || it.protocol == ProxySourceProtocol.SOCKS5)
        }
        return (mergedFromGithub + retained).distinctBy { it.cacheKey }
    }

    private fun buildCheckQueue(
        proxies: List<MtProxy>,
        force: Boolean,
        tcpOkOnly: Boolean
    ): List<MtProxy> {
        val now = nowMillis()

        fun isFresh(proxy: MtProxy): Boolean {
            val checkedAt = proxy.checkedAt ?: return false
            return now - checkedAt in 0 until CHECK_FRESH_MS
        }

        // Force checks every proxy; ordinary refresh checks all stale entries.
        // Fresh results are still reused by incremental refreshes.
        if (force) return proxies.distinctBy { it.cacheKey }

        // Quick manual "Проверка": only previously TCP-reachable servers.
        // Re-test regardless of TTL; a Telegram FAIL may become Telegram OK.
        if (tcpOkOnly) return proxies.filter { it.tcpOk == true }.distinctBy { it.cacheKey }

        val needCheck = proxies.filterNot { isFresh(it) }

        val oldTelegramOk = needCheck
            .filter { it.telegramOk == true }

        val newProxies = needCheck
            .filter { it.checkedAt == null }

        // All stale failures participate; prioritize the oldest first.
        // No arbitrary per-pass limit now that scans can continue in the background.
        val oldTelegramFail = needCheck
            .filter { it.checkedAt != null && it.telegramOk == false }
            .sortedBy { it.checkedAt }

        val unknown = needCheck
            .filter { it.telegramOk == null }
            .filter { it.checkedAt != null }

        return (oldTelegramOk + newProxies + unknown + oldTelegramFail)
            .distinctBy { it.cacheKey }
    }

    /**
     * Interleave source queues when limiting work; a constantly refreshed MTProto
     * list must not permanently starve tens of thousands of SOCKS5 addresses.
     */
    private fun interleaveSourceQueues(queue: List<MtProxy>): List<MtProxy> {
        val mt = ArrayDeque(queue.filter { it.protocol == ProxySourceProtocol.MTPROTO })
        val socks = ArrayDeque(queue.filter { it.protocol == ProxySourceProtocol.SOCKS5 })
        if (mt.isEmpty() || socks.isEmpty()) return queue
        val merged = ArrayList<MtProxy>(queue.size)
        // Give the protocol with older checks the first turn.
        val socksFirst = (socks.firstOrNull()?.checkedAt ?: Long.MIN_VALUE) <
            (mt.firstOrNull()?.checkedAt ?: Long.MIN_VALUE)
        while (mt.isNotEmpty() || socks.isNotEmpty()) {
            if (socksFirst) {
                if (socks.isNotEmpty()) merged += socks.removeFirst()
                if (mt.isNotEmpty()) merged += mt.removeFirst()
            } else {
                if (mt.isNotEmpty()) merged += mt.removeFirst()
                if (socks.isNotEmpty()) merged += socks.removeFirst()
            }
        }
        return merged
    }

    fun sortProxies(proxies: List<MtProxy>): List<MtProxy> {
        return proxies.sortedWith(
            compareByDescending<MtProxy> { it.isFavorite }
                .thenByDescending { it.telegramOk == true }
                .thenByDescending { it.tcpOk == true }
                .thenBy { it.telegramPingMs ?: Long.MAX_VALUE }
                .thenBy { it.tcpPingMs ?: Long.MAX_VALUE }
        )
    }
}

private suspend fun downloadProxies(): List<MtProxy> = withContext(Dispatchers.IO) {
    val connection = URI(PROXY_LIST_URL).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    try {
        connection.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { parseProxyLine(it) }.distinctBy { it.cacheKey }.toList()
        }
    } finally {
        connection.disconnect()
    }
}
