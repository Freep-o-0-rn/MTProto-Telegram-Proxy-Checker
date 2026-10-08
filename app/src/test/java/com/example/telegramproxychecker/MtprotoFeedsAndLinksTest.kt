package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtprotoFeedsAndLinksTest {
    @Test
    fun parsesBothTelegramUrlFormatsAndIgnoresHeaders() {
        val https = parseProxyLine(
            "https://t.me/proxy?server=example.org.&port=853&secret=abc123"
        )!!
        val tg = parseProxyLine(
            "tg://proxy?server=example.org&port=853&secret=abc123"
        )!!
        assertEquals("example.org", https.server)
        assertEquals(853, https.port)
        assertEquals("abc123", https.secret)
        assertEquals(https.cacheKey, tg.cacheKey)
        assertEquals(ProxySourceProtocol.MTPROTO, tg.protocol)
        assertNull(parseProxyLine("# updated 2026-10-08"))
        assertNull(parseProxyLine("https://evil.example/proxy?server=a&port=443&secret=abc"))
    }

    @Test
    fun handlesShablinSuffixAndEscapedSecret() {
        val p = parseProxyLine(
            "tg://proxy?server=example.org&port=443&secret=ab%2Bcd%2Fef|2026-10-08T04:57"
        )!!
        assertEquals("ab+cd/ef", p.secret)
        assertEquals("example.org:443:ab+cd/ef", p.cacheKey)
        assertEquals("tg://proxy?server=example.org&port=443&secret=ab%2Bcd%2Fef", p.originalUrl)
    }

    @Test
    fun rejectsInvalidPortAndMissingFields() {
        for (bad in listOf(
            "", "tg://proxy?server=example.org&port=0&secret=abc",
            "tg://proxy?server=example.org&port=65536&secret=abc",
            "tg://proxy?server=example.org&port=no&secret=abc",
            "tg://proxy?server=example.org&port=443",
            "tg://proxy?server=&port=443&secret=abc",
            "tg://proxy?server=example.org&port=443&secret="
        )) assertNull(bad, parseProxyLine(bad))
    }

    @Test
    fun telegramConnectLinksMatchActualProtocol() {
        val mt = MtProxy("https://t.me/proxy?server=a&port=443&secret=a%2Bb",
            "proxy.example", 443, "a+b")
        val socks = MtProxy("https://t.me/socks?server=s&port=1080", "192.0.2.1",
            1080, "", protocol = ProxySourceProtocol.SOCKS5)
        assertEquals("tg://proxy?server=proxy.example&port=443&secret=a%2Bb",
            telegramDeepLink(mt))
        assertEquals("tg://socks?server=192.0.2.1&port=1080",
            telegramDeepLink(socks))
        val authenticated = socks.copy(username = "user name", password = "p@ss")
        assertEquals("tg://socks?server=192.0.2.1&port=1080&user=user+name&pass=p%40ss",
            telegramDeepLink(authenticated))
        assertTrue(telegramDeepLink(socks).startsWith("tg://socks?"))
    }
}
