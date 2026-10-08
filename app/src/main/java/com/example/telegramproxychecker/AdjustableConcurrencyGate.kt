package com.example.telegramproxychecker

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared with bulk and individual scans. Changing the target never interrupts
 * a running check; it only controls admission of the next check.
 */
internal class AdjustableConcurrencyGate(initialLimit: Int) {
    private val mutex = Mutex()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var active = 0

    @Volatile
    private var limit = ScanConcurrencyPolicy.clamp(initialLimit)

    fun configure(newLimit: Int) {
        limit = ScanConcurrencyPolicy.clamp(newLimit)
        wakeup.trySend(Unit)
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        while (true) {
            val admitted = mutex.withLock {
                if (active >= limit) false
                else {
                    active++
                    true
                }
            }
            if (admitted) break
            wakeup.receive()
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { active-- }
                wakeup.trySend(Unit)
            }
        }
    }
}
