package com.example.telegramproxychecker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URI

private const val PROXY_LIST_URL =
    "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"

private const val CHECK_FRESH_MS = 30L * 60L * 1000L
private const val MAX_OLD_FAIL_RECHECK = 20

class ProxyRepository {

    private val telegramChecker = TelegramMtprotoChecker()

    suspend fun loadProxies(): List<MtProxy> {
        return withContext(Dispatchers.IO) {
            val text = URI(PROXY_LIST_URL).toURL().readText()

            text.lines()
                .mapNotNull { parseProxyLine(it) }
                .distinctBy { it.cacheKey }
        }
    }

    suspend fun loadAndCheckProxies(
        cachedProxies: List<MtProxy>,
        force: Boolean = false,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): List<MtProxy> {
        val sourceProxies = try {
            val githubProxies = loadProxies()
            mergeGithubWithCache(githubProxies, cachedProxies)
        } catch (e: Exception) {
            if (cachedProxies.isNotEmpty()) {
                cachedProxies
            } else {
                throw e
            }
        }

        val queue = buildCheckQueue(sourceProxies, force)

        onProgress(0, queue.size)

        if (queue.isEmpty()) {
            return sortProxies(sourceProxies)
        }

        return coroutineScope {
            val semaphore = Semaphore(3)
            var checkedCount = 0

            val checkedResults = queue.map { proxy ->
                async {
                    semaphore.withPermit {
                        val result = checkSingleProxy(proxy)

                        checkedCount += 1
                        onProgress(checkedCount, queue.size)

                        result
                    }
                }
            }.awaitAll()

            val checkedMap = checkedResults.associateBy { it.cacheKey }

            val finalList = sourceProxies.map { proxy ->
                checkedMap[proxy.cacheKey] ?: proxy
            }

            sortProxies(finalList)
        }
    }

    private suspend fun checkSingleProxy(proxy: MtProxy): MtProxy {
        val now = System.currentTimeMillis()

        val tcpChecked = checkTcpProxy(proxy)

        val telegramChecked = if (tcpChecked.tcpOk == true) {
            telegramChecker.check(tcpChecked)
        } else {
            tcpChecked
        }

        return telegramChecked.copy(
            checkedAt = now,
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
        val now = System.currentTimeMillis()

        fun isFresh(proxy: MtProxy): Boolean {
            val checkedAt = proxy.checkedAt ?: return false
            return now - checkedAt < CHECK_FRESH_MS
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

        val oldTelegramFail = needCheck
            .filter { it.checkedAt != null }
            .filter { it.telegramOk == false }
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