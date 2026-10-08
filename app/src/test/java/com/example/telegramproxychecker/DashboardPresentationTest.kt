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
}
