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
private const val MAX_OLD_FAIL_RECHECK = 20
private const val PARALLEL_CHECKS = 6
private const val RESULT_UPDATE_INTERVAL_MS = 200L

class ProxyRepository internal constructor(
    private val sourceLoader: suspend () -> List<MtProxy>,
    private val tcpCheck: suspend (MtProxy) -> MtProxy,
    private val telegramCheck: suspend (MtProxy) -> MtProxy,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    constructor() : this(
        sourceLoader = ::downloadProxies,
        tcpCheck = { checkTcpProxy(it) },
        telegramCheck = TelegramMtprotoChecker()::check
    )

    // Shared by bulk and individual checks; extra taps cannot bypass the limit.
    private val checkSlots = Semaphore(PARALLEL_CHECKS)

    suspend fun loadProxies(): List<MtProxy> = sourceLoader()

    suspend fun recheckOneProxy(proxy: MtProxy): MtProxy {
        return checkSingleProxy(proxy)
    }

    suspend fun loadAndCheckProxies(
        cachedProxies: List<MtProxy>,
        force: Boolean = false,
        onUpdate: (List<MtProxy>) -> Unit = {},
        beforeCheck: suspend () -> Unit = {},
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): List<MtProxy> {
        val sourceProxies = try {
            val githubProxies = loadProxies()
            mergeGithubWithCache(githubProxies, cachedProxies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cachedProxies.isNotEmpty()) {
                cachedProxies
            } else {
                throw e
            }
        }

        val queue = buildCheckQueue(sourceProxies, force)

        onProgress(0, queue.size)
        onUpdate(sortProxies(sourceProxies))

        if (queue.isEmpty()) {
            return sortProxies(sourceProxies)
        }

        return coroutineScope {
            val pending = Channel<MtProxy>(PARALLEL_CHECKS)
            val completed = Channel<MtProxy>(PARALLEL_CHECKS)
            launch {
                for (proxy in queue) pending.send(proxy)
                pending.close()
            }
            // Fixed worker count instead of allocating one suspended async per proxy.
            repeat(minOf(PARALLEL_CHECKS, queue.size)) {
                launch {
                    for (proxy in pending) {
                        beforeCheck()
                        completed.send(checkSingleProxy(proxy))
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

    private suspend fun checkSingleProxy(proxy: MtProxy): MtProxy = checkSlots.withPermit {
        val tcpChecked = tcpCheck(proxy)

        val telegramChecked = if (tcpChecked.tcpOk == true) {
            telegramCheck(tcpChecked)
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

        val oldFavorites = cachedProxies
            .filter { it.isFavorite }
            .filter { it.cacheKey !in githubKeys }

        return (mergedFromGithub + oldFavorites)
            .distinctBy { it.cacheKey }
    }

    private fun buildCheckQueue(
        proxies: List<MtProxy>,
        force: Boolean
    ): List<MtProxy> {
        val now = nowMillis()

        fun isFresh(proxy: MtProxy): Boolean {
            val checkedAt = proxy.checkedAt ?: return false
            return now - checkedAt in 0 until CHECK_FRESH_MS
        }

        val needCheck = if (force) {
            proxies
        } else {
            proxies.filterNot { isFresh(it) }
        }

        val oldTelegramOk = needCheck
            .filter { it.telegramOk == true }

        val newProxies = needCheck
            .filter { it.checkedAt == null }

        // Rotate failures by oldest check. Using source order here permanently starves
        // proxies beyond the first 20 when the GitHub list stays unchanged.
        val oldTelegramFail = needCheck
            .filter { it.checkedAt != null && it.telegramOk == false }
            .sortedBy { it.checkedAt }
            .take(MAX_OLD_FAIL_RECHECK)

        val unknown = needCheck
            .filter { it.telegramOk == null }
            .filter { it.checkedAt != null }

        return (oldTelegramOk + newProxies + unknown + oldTelegramFail)
            .distinctBy { it.cacheKey }
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
