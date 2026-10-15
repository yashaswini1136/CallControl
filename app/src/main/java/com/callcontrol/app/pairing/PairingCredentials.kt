package com.callcontrol.app.pairing

import java.security.SecureRandom
import java.util.UUID

/**
 * Result of validating a submitted pairing code against active [PairingCredentials].
 */
sealed class CredentialValidationResult {
    data object Success : CredentialValidationResult()
    data class InvalidCode(val attemptsRemaining: Int) : CredentialValidationResult()
    data object RateLimited : CredentialValidationResult()
    data object Expired : CredentialValidationResult()
    data object AlreadyConsumed : CredentialValidationResult()
}

/**
 * Short-lived, single-use, cryptographically secure pairing credential.
 *
 * Safeguards:
 * - Code is generated using [SecureRandom] with high entropy (6 decimal digits).
 * - Enforces a strict time-to-live ([ttlMs], default 5 minutes).
 * - Single-use: once verified or consumed, it cannot be reused.
 * - Rate-limited: locked out after [maxAttempts] failed attempts to prevent brute-force attacks.
 * - Includes a 128-bit random salt for key derivation.
 */
class PairingCredentials private constructor(
    val pairingId: String,
    val code: String,
    val saltHex: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    var attemptCount: Int = 0
        private set

    var isConsumed: Boolean = false
        private set

    fun isExpired(currentTimeMs: Long): Boolean = currentTimeMs >= expiresAtMs

    fun isRateLimited(): Boolean = attemptCount >= maxAttempts

    /**
     * Validates a submitted code in constant time (timing-attack resistant).
     * Automatically increments attempt counter and consumes credential upon success or lockout.
     */
    @Synchronized
    fun validateAttempt(submittedCode: String, currentTimeMs: Long): CredentialValidationResult {
        if (isConsumed) {
            return CredentialValidationResult.AlreadyConsumed
        }

        if (isExpired(currentTimeMs)) {
            isConsumed = true
            return CredentialValidationResult.Expired
        }

        if (isRateLimited()) {
            isConsumed = true
            return CredentialValidationResult.RateLimited
        }

        val codeMatches = constantTimeEquals(code, submittedCode.trim())
        if (!codeMatches) {
            attemptCount++
            if (isRateLimited()) {
                isConsumed = true
                return CredentialValidationResult.RateLimited
            }
            return CredentialValidationResult.InvalidCode(attemptsRemaining = maxAttempts - attemptCount)
        }

        // Successfully matched
        isConsumed = true
        return CredentialValidationResult.Success
    }

    /**
     * Explicitly marks this credential as consumed/canceled (e.g., on rejection or user cancel).
     */
    @Synchronized
    fun cancel() {
        isConsumed = true
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 5 * 60 * 1000L // 5 minutes
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        private const val SALT_BYTE_LENGTH: Int = 16

        private val secureRandom = SecureRandom()

        /**
         * Factory method to generate a new secure credential set.
         */
        fun generate(
            currentTimeMs: Long,
            ttlMs: Long = DEFAULT_TTL_MS,
            maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
        ): PairingCredentials {
            val pairingId = UUID.randomUUID().toString()

            // Generate 6 decimal digits: 000000 to 999999
            val codeNumber = secureRandom.nextInt(1_000_000)
            val code = String.format(java.util.Locale.US, "%06d", codeNumber)

            // Generate 128-bit cryptographically secure salt
            val saltBytes = ByteArray(SALT_BYTE_LENGTH)
            secureRandom.nextBytes(saltBytes)
            val saltHex = bytesToHex(saltBytes)

            return PairingCredentials(
                pairingId = pairingId,
                code = code,
                saltHex = saltHex,
                createdAtMs = currentTimeMs,
                expiresAtMs = currentTimeMs + ttlMs,
                maxAttempts = maxAttempts
            )
        }

        /**
         * Timing-safe string comparison to prevent timing side-channel attacks on code checking.
         */
        fun constantTimeEquals(a: String, b: String): Boolean {
            val aBytes = a.toByteArray(Charsets.UTF_8)
            val bBytes = b.toByteArray(Charsets.UTF_8)
            var result = aBytes.size xor bBytes.size
            val minLen = minOf(aBytes.size, bBytes.size)
            for (i in 0 until minLen) {
                result = result or (aBytes[i].toInt() xor bBytes[i].toInt())
            }
            return result == 0
        }

        fun bytesToHex(bytes: ByteArray): String {
            val hexChars = "0123456789abcdef"
            val result = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val octet = byte.toInt()
                val firstIndex = (octet ushr 4) and 0x0f
                val secondIndex = octet and 0x0f
                result.append(hexChars[firstIndex])
                result.append(hexChars[secondIndex])
            }
            return result.toString()
        }

        fun hexToBytes(hex: String): ByteArray {
            val cleanHex = hex.trim()
            val len = cleanHex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = (
                    (Character.digit(cleanHex[i], 16) shl 4) +
                        Character.digit(cleanHex[i + 1], 16)
                    ).toByte()
                i += 2
            }
            return data
        }
    }
}
