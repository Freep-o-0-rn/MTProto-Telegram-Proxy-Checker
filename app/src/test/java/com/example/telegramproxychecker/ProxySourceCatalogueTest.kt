package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySourceCatalogueTest {
    @Test
    fun mtprotoFeedsFirstAndSocks5Last() {
        val sources = ProxySourceCatalogue.entries
        assertEquals(5, sources.size)
        assertEquals(sources.size, sources.map { it.id }.distinct().size)
        val active = sources.filter { it.status == ProxySourceStatus.ACTIVE }
        assertEquals(5, active.size)
        assertTrue(active.any { it.protocol == ProxySourceProtocol.MTPROTO })
        assertEquals(
            listOf("solispirit-mtproto", "tgmtproxy-mtproto", "shablin-mtproto",
                "dubblebyte-mtproto", "hookzof-socks5"),
            active.map { it.id }
        )
        assertTrue(active.dropLast(1).all { it.protocol == ProxySourceProtocol.MTPROTO })
        assertEquals(ProxySourceProtocol.SOCKS5, active.last().protocol)
        assertEquals(
            "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            active.first().sourceUrl
        )
    }

    @Test
    fun socks5HasItsOwnProtocolAndUrl() {
        val future = ProxySourceCatalogue.entries.single { it.id == "hookzof-socks5" }
        assertEquals(ProxySourceProtocol.SOCKS5, future.protocol)
        assertEquals(ProxySourceStatus.ACTIVE, future.status)
        assertTrue(future.sourceUrl.endsWith("/hookzof/socks5_list/master/proxy.txt"))
        assertFalse(future.protocol == ProxySourceProtocol.MTPROTO)
    }
}
