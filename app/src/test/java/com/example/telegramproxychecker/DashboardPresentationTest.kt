package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPresentationTest {
    private fun proxy(
        host: String,
        ping: Long? = null,
        ok: Boolean? = null,
        favorite: Boolean = false,
        port: Int = 443
    ) = MtProxy(
        originalUrl = "tg://proxy?server=$host&port=$port",
        server = host,
        port = port,
        secret = "test-secret",
        telegramOk = ok,
        telegramPingMs = ping,
        isFavorite = favorite
    )

    @Test
    fun workingTabOnlyDisplaysVerifiedTelegramConnectionsByFastestPing() {
        val list = listOf(
            proxy("slow.example", 5000L, true),
            proxy("failed.example", null, false),
            proxy("fast.example", 270L, true),
            proxy("unknown.example"),
            proxy("medium.example", 800L, true)
        )
        assertEquals(
            listOf("fast.example", "medium.example", "slow.example"),
            dashboardProxies(list, ProxyTab.WORKING).map { it.server }
        )
    }

    @Test
    fun allAndFavoritesKeepFailuresButOrderTelegramOkFirst() {
        val list = listOf(
            proxy("bad", ok = false, favorite = true),
            proxy("healthy", 330L, true, favorite = true),
            proxy("unknown", favorite = true),
            proxy("ordinary", 100L, true)
        )
        assertEquals(
            listOf("ordinary", "healthy", "unknown", "bad"),
            dashboardProxies(list, ProxyTab.ALL).map { it.server }
        )
        assertEquals(
            listOf("healthy", "unknown", "bad"),
            dashboardProxies(list, ProxyTab.FAVORITES).map { it.server }
        )
    }

    @Test
    fun tcpDashboardCardIncludesTelegramFailuresAndOrdersByTcpPing() {
        val proxies = listOf(
            proxy("slow-tcp", ping = 140L, ok = true).copy(tcpOk = true, tcpPingMs = 1500L),
            proxy("closed", ok = false).copy(tcpOk = false, tcpPingMs = null),
            proxy("fast-tcp", ok = false).copy(tcpOk = true, tcpPingMs = 80L),
            proxy("unchecked").copy(tcpOk = null, tcpPingMs = null),
            proxy("middle-tcp", ok = null).copy(tcpOk = true, tcpPingMs = 300L)
        )
        assertEquals(
            listOf("fast-tcp", "middle-tcp", "slow-tcp"),
            dashboardProxies(proxies, ProxyTab.TCP_OK).map { it.server }
        )
        // Telegram OK is stricter than TCP OK, and the counters must not conflate them.
        assertEquals(listOf("slow-tcp"), dashboardProxies(proxies, ProxyTab.WORKING).map { it.server })
        assertEquals(5, dashboardProxies(proxies, ProxyTab.ALL).size)
    }

    @Test
    fun tcpFilterHonorsSearchAndDoesNotRequireTelegramOk() {
        val proxies = listOf(
            proxy("192.0.2.10", ok = false, port = 1080).copy(tcpOk = true, tcpPingMs = 200),
            proxy("192.0.2.11", ok = false, port = 1080).copy(tcpOk = false),
            proxy("other.example", ok = null, port = 80).copy(tcpOk = true, tcpPingMs = 120)
        )
        assertEquals(
            listOf("192.0.2.10"),
            dashboardProxies(proxies, ProxyTab.TCP_OK, "192.0.2").map { it.server }
        )
        assertEquals(
            listOf("192.0.2.10"),
            dashboardProxies(proxies, ProxyTab.TCP_OK, "1080").map { it.server }
        )
        assertTrue(dashboardProxies(proxies, ProxyTab.TCP_OK, "absent").isEmpty())
    }

    @Test
    fun searchOnlyMatchesDomainIpOrPortWithoutSecret() {
        val list = listOf(
            proxy("alpha.example", 200L, true, port = 443),
            proxy("192.0.2.1", 150L, true, port = 853)
        )
        assertEquals(listOf("alpha.example"), dashboardProxies(list, ProxyTab.ALL, "ALPHA").map { it.server })
        assertEquals(listOf("192.0.2.1"), dashboardProxies(list, ProxyTab.ALL, "192.0").map { it.server })
        assertEquals(listOf("192.0.2.1"), dashboardProxies(list, ProxyTab.ALL, "853").map { it.server })
        assertTrue(dashboardProxies(list, ProxyTab.ALL, "test-secret").isEmpty())
    }

    @Test
    fun freezePreventsResortingWhileReadingAndAppendsNewMatches() {
        val previous = listOf("a", "b", "c")
        val updated = listOf("c", "d", "a", "b")
        assertEquals(listOf("a", "b", "c", "d"), stableDashboardKeys(previous, updated, true))
        assertEquals(updated, stableDashboardKeys(previous, updated, false))
        assertEquals(listOf("a", "c", "d"), stableDashboardKeys(previous, listOf("d", "c", "a"), true))
    }

    @Test
    fun duplicatedUserFacingOrderIsNotCreatedByRepeatedSnapshots() {
        val original = listOf("a", "b")
        val first = stableDashboardKeys(original, listOf("b", "a", "c"), true)
        val second = stableDashboardKeys(first, listOf("b", "c", "a"), true)
        assertEquals(listOf("a", "b", "c"), second)
        assertEquals(second.size, second.toSet().size)
    }
    @Test
    fun unstableTdlibSuccessStaysWorkingButFollowsStableConnections() {
        val unstableFast = proxy("unstable-fast", 70L, true)
            .copy(tcpOk = false, tcpPingMs = null)
        val stableSlow = proxy("stable-slow", 250L, true)
            .copy(tcpOk = true, tcpPingMs = 30)
        val tcpFail = proxy("tcp-fail", null, false)
            .copy(tcpOk = false)
        val proxies = listOf(unstableFast, stableSlow, tcpFail)

        assertTrue(unstableFast.isUnstableTelegramOk)
        assertFalse(stableSlow.isUnstableTelegramOk)
        assertFalse(tcpFail.isUnstableTelegramOk)
        assertEquals(
            listOf("stable-slow", "unstable-fast"),
            dashboardProxies(proxies, ProxyTab.WORKING).map { it.server }
        )
        assertEquals(2, proxies.count { it.telegramOk == true })
        assertEquals(1, proxies.count { it.tcpOk == true })
        assertEquals(3, dashboardProxies(proxies, ProxyTab.ALL).size)
        assertEquals(1, dashboardProxies(proxies, ProxyTab.TCP_OK).size)
    }

    @Test
    fun unstableFlagIsDerivedFromCurrentTcpAndTdlibResults() {
        val proxy = proxy("unstable", 95L, true).copy(tcpOk = false)
        assertTrue(proxy.isUnstableTelegramOk)
        assertFalse(proxy.copy(tcpOk = true).isUnstableTelegramOk)
        assertFalse(proxy.copy(telegramOk = false).isUnstableTelegramOk)
        assertFalse(proxy.copy(telegramOk = null).isUnstableTelegramOk)
        assertFalse(proxy.copy(tcpOk = null).isUnstableTelegramOk)
    }

}
