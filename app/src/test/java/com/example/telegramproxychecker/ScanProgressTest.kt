package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScanProgressTest {
    @Test
    fun screenAndNotificationUseTheSameImmutableCounters() {
        val state = ScanSnapshot(running = true, checked = 3, total = 176)
        val display = state.progress
        assertEquals(3, display.checked)
        assertEquals(176, display.total)
        assertEquals("3/176", display.label())
        assertEquals(display, state.copy(proxies = listOf(testProxy(1))).progress)
    }

    @Test
    fun everyCompletedResultChangesTheNotificationProgressEvenWithinFiveHundredMs() {
        // No time-throttle: the 8/176 value must not remain displayed after 9/176.
        val first = ScanSnapshot(running = true, checked = 8, total = 176).progress
        val next = ScanSnapshot(running = true, checked = 9, total = 176).progress
        val final = ScanSnapshot(running = true, checked = 176, total = 176).progress
        assertNotEquals(first, next)
        assertNotEquals(next, final)
        assertEquals("176/176", final.label())
    }

    @Test
    fun pauseChangesProgressIdentityWithoutChangingCounters() {
        val active = ScanSnapshot(running = true, checked = 47, total = 176)
        val paused = active.copy(paused = true)
        assertEquals(active.progress.checked, paused.progress.checked)
        assertEquals(active.progress.total, paused.progress.total)
        assertNotEquals(active.progress, paused.progress)
    }

    @Test
    fun unknownTotalUsesIndeterminateLabel() {
        assertEquals("...", ScanSnapshot(running = true).progress.label())
    }
}
