package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySourceCatalogueTest {
    @Test
    fun onlyExistingMtprotoFeedIsMarkedActive() {
        val sources = ProxySourceCatalogue.entries
        assertEquals(2, sources.size)
        assertEquals(sources.size, sources.map { it.id }.distinct().size)
        val active = sources.filter { it.status == ProxySourceStatus.ACTIVE }
        assertEquals(1, active.size)
        assertEquals(ProxySourceProtocol.MTPROTO, active.single().protocol)
        assertEquals(
            "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            active.single().sourceUrl
        )
    }

    @Test
    fun futureSocks5SourceCannotBeMistakenForAnActiveMtprotoFeed() {
        val future = ProxySourceCatalogue.entries.single { it.id == "hookzof-socks5" }
        assertEquals(ProxySourceProtocol.SOCKS5, future.protocol)
        assertEquals(ProxySourceStatus.PLANNED, future.status)
        assertTrue(future.sourceUrl.endsWith("/hookzof/socks5_list/master/proxy.txt"))
        assertFalse(future.protocol == ProxySourceProtocol.MTPROTO)
    }
}
