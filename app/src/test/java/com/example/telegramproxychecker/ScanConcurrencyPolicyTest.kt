package com.example.telegramproxychecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanConcurrencyPolicyTest {
    @Test
    fun manualSettingsClampToSafeSupportedRange() {
        assertEquals(1, ScanConcurrencyPolicy.clamp(-1))
        assertEquals(1, ScanConcurrencyPolicy.clamp(0))
        assertEquals(6, ScanConcurrencyPolicy.clamp(6))
        assertEquals(30, ScanConcurrencyPolicy.clamp(30))
        assertEquals(30, ScanConcurrencyPolicy.clamp(1000))
    }

    @Test
    fun automaticModeAccountsForCpuMemoryAndLowRam() {
        assertEquals(3, ScanConcurrencyPolicy.autoWorkers(8, 512, true))
        assertEquals(2, ScanConcurrencyPolicy.autoWorkers(1, 512, false))
        assertEquals(4, ScanConcurrencyPolicy.autoWorkers(8, 128, false))
        assertEquals(8, ScanConcurrencyPolicy.autoWorkers(8, 256, false))
        assertEquals(12, ScanConcurrencyPolicy.autoWorkers(8, 384, false))
        assertEquals(20, ScanConcurrencyPolicy.autoWorkers(12, 1024, false))
        for (cores in 1..16) for (ram in listOf(64, 128, 192, 256, 512, 1024)) {
            val suggested = ScanConcurrencyPolicy.autoWorkers(cores, ram, false)
            assertTrue(suggested in 1..30)
        }
    }

    @Test
    fun tdlibConcurrencyNeverExceedsPreviouslyValidatedSix() {
        for (workers in 1..30) {
            assertEquals(minOf(workers, 6), ScanConcurrencyPolicy.telegramLimit(workers))
        }
    }
}
