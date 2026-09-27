package com.vinstall.alwiz.transfer

import java.util.concurrent.TimeUnit

internal const val MAX_PAIRING_ATTEMPTS = 5
internal val PAIRING_SESSION_TIMEOUT_MS: Long = TimeUnit.MINUTES.toMillis(10)
private val PAIRING_ATTEMPT_WINDOW_MS = TimeUnit.MINUTES.toMillis(1)
private val PAIRING_BLOCK_DURATION_MS = TimeUnit.MINUTES.toMillis(1)

internal fun isPairingSessionExpired(lastActivity: Long, now: Long): Boolean =
    now - lastActivity > PAIRING_SESSION_TIMEOUT_MS

internal class PairingAttemptWindow {
    private var windowStarted = 0L
    private var failures = 0
    private var blockedUntil = 0L

    @Synchronized
    fun isBlocked(now: Long): Boolean = now < blockedUntil

    @Synchronized
    fun recordFailure(now: Long) {
        if (now - windowStarted > PAIRING_ATTEMPT_WINDOW_MS) {
            windowStarted = now
            failures = 0
        }
        failures++
        if (failures >= MAX_PAIRING_ATTEMPTS) {
            blockedUntil = now + PAIRING_BLOCK_DURATION_MS
        }
    }
}
