package com.callcontrol.app.network

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Standard HMAC-based Extract-and-Expand Key Derivation Function (HKDF)
 * as specified in RFC 5869 using HMAC-SHA-256.
 *
 * Guarantees cryptographic domain separation and uniform entropy distribution
 * for derived symmetric keys and IV bases.
 */
object HkdfSha256 {

    private const val HMAC_ALGO = "HmacSHA256"
    private const val HASH_LEN = 32 // SHA-256 output length in bytes

    /**
     * HKDF-Extract(salt, IKM) -> PRK
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        val saltKey = if (salt == null || salt.isEmpty()) {
            ByteArray(HASH_LEN) // HashLen zeros if salt is omitted (RFC 5869 Section 2.2)
        } else {
            salt
        }
        mac.init(SecretKeySpec(saltKey, HMAC_ALGO))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand(PRK, info, L) -> OKM
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "Length must be positive: $length" }
        val n = (length + HASH_LEN - 1) / HASH_LEN
        require(n <= 255) { "Length $length too large for HKDF-SHA256 (max ${255 * HASH_LEN})" }

        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(prk, HMAC_ALGO))

        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()

            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
        }
        return okm
    }

    /**
     * Complete HKDF: Extract then Expand.
     */
    fun extractAndExpand(salt: ByteArray?, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }
}
