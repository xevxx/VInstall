package com.vinstall.alwiz.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecurityTest {
    @Test
    fun sessionExpiresOnlyAfterTenMinutesWithoutActivity() {
        val started = 1_000L
        assertFalse(isPairingSessionExpired(started, started + PAIRING_SESSION_TIMEOUT_MS))
        assertTrue(isPairingSessionExpired(started, started + PAIRING_SESSION_TIMEOUT_MS + 1))
    }

    @Test
    fun fifthFailedAttemptTemporarilyBlocksClient() {
        val window = PairingAttemptWindow()
        val now = 10_000L
        repeat(MAX_PAIRING_ATTEMPTS - 1) { window.recordFailure(now + it) }
        assertFalse(window.isBlocked(now + MAX_PAIRING_ATTEMPTS))

        window.recordFailure(now + MAX_PAIRING_ATTEMPTS)
        assertTrue(window.isBlocked(now + MAX_PAIRING_ATTEMPTS + 1))
        assertFalse(window.isBlocked(now + 60_006L))
    }
}
