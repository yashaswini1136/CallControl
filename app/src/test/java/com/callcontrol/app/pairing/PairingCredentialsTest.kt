package com.callcontrol.app.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCredentialsTest {

    @Test
    fun generate_createsValidSixDigitCodeAndHighEntropySalt() {
        val now = 100_000L
        val creds = PairingCredentials.generate(currentTimeMs = now, ttlMs = 300_000L)

        assertNotNull(creds.pairingId)
        assertTrue(creds.pairingId.isNotBlank())

        assertEquals(6, creds.code.length)
        assertTrue(creds.code.all { it.isDigit() })

        assertEquals(32, creds.saltHex.length) // 16 bytes = 32 hex chars
        assertEquals(now, creds.createdAtMs)
        assertEquals(now + 300_000L, creds.expiresAtMs)
        assertFalse(creds.isConsumed)
        assertFalse(creds.isRateLimited())
        assertEquals(0, creds.attemptCount)
    }

    @Test
    fun validateAttempt_succeedsWithCorrectCode_andMarksConsumed() {
        val now = 100_000L
        val creds = PairingCredentials.generate(currentTimeMs = now)

        val result = creds.validateAttempt(creds.code, currentTimeMs = now + 1_000L)
        assertTrue(result is CredentialValidationResult.Success)
        assertTrue(creds.isConsumed)

        // Attempting to reuse the consumed credential must fail
        val reuse = creds.validateAttempt(creds.code, currentTimeMs = now + 2_000L)
        assertTrue(reuse is CredentialValidationResult.AlreadyConsumed)
    }

    @Test
    fun validateAttempt_decrementsAttemptsOnInvalidCode_andLocksOutAfterMaxAttempts() {
        val now = 100_000L
        val creds = PairingCredentials.generate(currentTimeMs = now, maxAttempts = 3)
        val wrongCode = if (creds.code == "123456") "654321" else "123456"

        // Attempt 1: 2 remaining
        val att1 = creds.validateAttempt(wrongCode, now + 1_000L)
        assertTrue(att1 is CredentialValidationResult.InvalidCode)
        assertEquals(2, (att1 as CredentialValidationResult.InvalidCode).attemptsRemaining)
        assertEquals(1, creds.attemptCount)
        assertFalse(creds.isConsumed)

        // Attempt 2: 1 remaining
        val att2 = creds.validateAttempt(wrongCode, now + 2_000L)
        assertTrue(att2 is CredentialValidationResult.InvalidCode)
        assertEquals(1, (att2 as CredentialValidationResult.InvalidCode).attemptsRemaining)
        assertEquals(2, creds.attemptCount)
        assertFalse(creds.isConsumed)

        // Attempt 3: Rate limited and locked out
        val att3 = creds.validateAttempt(wrongCode, now + 3_000L)
        assertTrue(att3 is CredentialValidationResult.RateLimited)
        assertTrue(creds.isRateLimited())
        assertTrue(creds.isConsumed)

        // Attempt 4 even with correct code is rejected due to rate limiting
        val att4 = creds.validateAttempt(creds.code, now + 4_000L)
        assertTrue(att4 is CredentialValidationResult.RateLimited || att4 is CredentialValidationResult.AlreadyConsumed)
    }

    @Test
    fun validateAttempt_rejectsExpiredCredentials() {
        val now = 100_000L
        val creds = PairingCredentials.generate(currentTimeMs = now, ttlMs = 60_000L) // 1 min TTL

        // Within TTL
        assertFalse(creds.isExpired(now + 30_000L))

        // Exactly at or after TTL expiry
        assertTrue(creds.isExpired(now + 60_000L))

        val result = creds.validateAttempt(creds.code, currentTimeMs = now + 60_001L)
        assertTrue(result is CredentialValidationResult.Expired)
        assertTrue(creds.isConsumed)
    }

    @Test
    fun constantTimeEquals_verifiesMatchingAndNonMatchingStrings() {
        assertTrue(PairingCredentials.constantTimeEquals("123456", "123456"))
        assertFalse(PairingCredentials.constantTimeEquals("123456", "123457"))
        assertFalse(PairingCredentials.constantTimeEquals("123456", "12345"))
        assertFalse(PairingCredentials.constantTimeEquals("123456", ""))
    }
}
