package com.example.telegramproxychecker

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

internal data class SourceRefreshResult(
    val counts: Map<String, SourceInventoryCount>,
    val errors: Map<String, String>
)

/**
 * Metadata/inventory import only: this DOES NOT send SOCKS5 addresses to
 * ProxyRepository, TDLib or the foreground scan queue.
 */
internal class ProxySourceInventory(context: Context) {
    private val store = ProxySqliteStore.instance(context)

    suspend fun cached(): Map<String, SourceInventoryCount> =
        withContext(Dispatchers.IO) { store.inventory() }

    suspend fun uniqueSelectedCount(sourceIds: Set<String>): Int =
        withContext(Dispatchers.IO) { store.uniqueInventoryCount(sourceIds) }

    suspend fun refresh(sourceIds: Set<String> = ProxySourceCatalogue.entries.map { it.id }.toSet()): SourceRefreshResult = withContext(Dispatchers.IO) {
        val errors = mutableMapOf<String, String>()
        for (source in ProxySourceCatalogue.entries.filter { it.id in sourceIds }) {
            try {
                val loaded = download(source)
                if (loaded.isEmpty()) error("В источнике нет корректных прокси")
                // A complete download replaces this source's inventory atomically.
                store.replaceSourceInventory(source.id, loaded, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the previous successful list/count intact.
                errors[source.id] = e.message?.take(140) ?: "Не удалось загрузить список"
            }
        }
        SourceRefreshResult(store.inventory(), errors)
    }

    private fun download(source: ProxySourcePresentation): List<MtProxy> {
        val conn = URI(source.sourceUrl).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("GitHub HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    when (source.protocol) {
                        ProxySourceProtocol.MTPROTO -> parseProxyLine(line)?.copy(sourceId = source.id)
                            ?.takeIf { it.port in 1..65535 && it.server.isNotBlank() }
                        ProxySourceProtocol.SOCKS5 -> parseSocks5Line(line, source.id)
                        else -> null
                    }
                }.distinctBy { it.cacheKey }.toList()
            }
        } finally {
            conn.disconnect()
        }
    }
}
