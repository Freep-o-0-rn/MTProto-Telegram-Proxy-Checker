package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySourceCatalogueTest {
    @Test
    fun bothProtocolFeedsAreActive() {
        val sources = ProxySourceCatalogue.entries
        assertEquals(2, sources.size)
        assertEquals(sources.size, sources.map { it.id }.distinct().size)
        val active = sources.filter { it.status == ProxySourceStatus.ACTIVE }
        assertEquals(2, active.size)
        assertTrue(active.any { it.protocol == ProxySourceProtocol.MTPROTO })
        assertEquals(
            "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            active.single { it.protocol == ProxySourceProtocol.MTPROTO }.sourceUrl
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
