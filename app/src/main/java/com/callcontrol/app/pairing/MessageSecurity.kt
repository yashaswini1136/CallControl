package com.callcontrol.app.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic security utilities for CallControl peer authentication and message integrity.
 *
 * Guarantees:
 * - Shared secrets are derived with SHA-256 combining pairing code, salt, client nonce, and server nonce (256-bit entropy).
 * - Message signing and verification use HMAC-SHA256.
 * - Verification uses timing-safe constant-time equality checks ([MessageDigest.isEqual]).
 * - High-entropy nonces generated using [SecureRandom].
 */
object MessageSecurity {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_ALGORITHM = "SHA-256"
    private const val NONCE_BYTES = 16

    private val secureRandom = SecureRandom()

    /**
     * Generates a 128-bit cryptographically secure random nonce as a lowercase hex string.
     */
    fun generateNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return PairingCredentials.bytesToHex(bytes)
    }

    /**
     * Derives a 256-bit shared authentication secret from pairing parameters.
     * Both the Controller and Friend Device compute this identically using:
     * pairingCode + saltHex + clientNonce + serverNonce.
     */
    fun deriveSharedSecret(
        pairingCode: String,
        saltHex: String,
        clientNonce: String,
        serverNonce: String
    ): ByteArray {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        digest.update(pairingCode.trim().toByteArray(Charsets.UTF_8))
        digest.update(saltHex.trim().lowercase().toByteArray(Charsets.UTF_8))
        digest.update(clientNonce.trim().lowercase().toByteArray(Charsets.UTF_8))
        digest.update(serverNonce.trim().lowercase().toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    /**
     * Computes an HMAC-SHA256 signature for canonical message data using the [secretKey].
     */
    fun sign(canonicalData: String, secretKey: ByteArray): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(secretKey, HMAC_ALGORITHM)
        mac.init(keySpec)
        val signatureBytes = mac.doFinal(canonicalData.toByteArray(Charsets.UTF_8))
        return PairingCredentials.bytesToHex(signatureBytes)
    }

    /**
     * Verifies whether [signatureHex] matches the HMAC-SHA256 of [canonicalData] using [secretKey].
     * Uses [MessageDigest.isEqual] to prevent timing side-channel attacks.
     */
    fun verifySignature(
        canonicalData: String,
        signatureHex: String?,
        secretKey: ByteArray?
    ): Boolean {
        if (signatureHex.isNullOrBlank() || secretKey == null || secretKey.isEmpty()) {
            return false
        }

        return try {
            val expectedSignature = sign(canonicalData, secretKey)
            val expectedBytes = expectedSignature.toByteArray(Charsets.UTF_8)
            val actualBytes = signatureHex.trim().lowercase().toByteArray(Charsets.UTF_8)
            MessageDigest.isEqual(expectedBytes, actualBytes)
        } catch (_: Exception) {
            false
        }
    }
}
