package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySourceFoundationTest {
    @Test
    fun mtprotoKeepsLegacyCacheIdentity() {
        val old = MtProxy(
            originalUrl = "https://t.me/proxy?server=example.org&port=443&secret=abc",
            server = "example.org", port = 443, secret = "abc"
        )
        assertEquals("example.org:443:abc", old.cacheKey)
        assertEquals(ProxySourceProtocol.MTPROTO, old.protocol)
        assertEquals("solispirit-mtproto", old.sourceId)
    }

    @Test
    fun socks5LinesBecomeProtocolTypedProxiesWithoutMtprotoSecrets() {
        val source = parseSocks5Line("208.102.51.6:58208")!!
        assertEquals("208.102.51.6", source.server)
        assertEquals(58208, source.port)
        assertEquals("", source.secret)
        assertEquals(ProxySourceProtocol.SOCKS5, source.protocol)
        assertEquals("hookzof-socks5", source.sourceId)
        assertTrue(source.originalUrl.contains("/socks?"))
        assertTrue(source.cacheKey.startsWith("SOCKS5:"))
        assertNotEquals(
            source.cacheKey,
            source.copy(protocol = ProxySourceProtocol.MTPROTO).cacheKey
        )
    }

    @Test
    fun malformedSocks5EntriesAreNeverImported() {
        for (bad in listOf(
            "", "# comment", "256.0.0.1:443", "127.0.0.1:0", "127.0.0.1:65536",
            "127.0.0.1:bad", "127.0.0.1", "http://127.0.0.1:8080",
            "login:password@127.0.0.1:1080", "127.0.0.1:-1",
            "127.0.0.1:443:extra", "a b.example:1080"
        )) {
            assertNull("Rejected: $bad", parseSocks5Line(bad))
        }
    }

    @Test
    fun duplicateEntriesCollapseToUniqueProxyIdentities() {
        val parsed = listOf("192.0.2.1:1080", "192.0.2.1:1080", "192.0.2.2:1080")
            .mapNotNull(::parseSocks5Line).distinctBy { it.cacheKey }
        assertEquals(2, parsed.size)
    }

    @Test
    fun supportsValidHostsAndPortRange() {
        assertEquals(1, parseSocks5Line("proxy.example:1")?.port)
        assertEquals(65535, parseSocks5Line("proxy.example:65535")?.port)
        assertFalse(parseSocks5Line("proxy.example:1080")!!.cacheKey.isEmpty())
    }

    @Test
    fun sourceCatalogueDoesNotAccidentallyEnableSocks5Checker() {
        val socks = ProxySourceCatalogue.entries.single { it.protocol == ProxySourceProtocol.SOCKS5 }
        assertEquals(ProxySourceStatus.PLANNED, socks.status)
        assertEquals("hookzof-socks5", socks.id)
    }
}
