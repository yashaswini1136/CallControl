package com.callcontrol.app.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSecurityTest {

    @Test
    fun generateNonce_producesUniqueHighEntropyHexStrings() {
        val nonce1 = MessageSecurity.generateNonce()
        val nonce2 = MessageSecurity.generateNonce()

        assertNotNull(nonce1)
        assertEquals(32, nonce1.length) // 16 bytes = 32 hex chars
        assertEquals(32, nonce2.length)
        assertNotEquals(nonce1, nonce2)
    }

    @Test
    fun deriveSharedSecret_producesIdentical256BitKeysForMatchingInputs() {
        val code = "482915"
        val salt = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val clientNonce = MessageSecurity.generateNonce()
        val serverNonce = MessageSecurity.generateNonce()

        val secret1 = MessageSecurity.deriveSharedSecret(code, salt, clientNonce, serverNonce)
        val secret2 = MessageSecurity.deriveSharedSecret(code, salt, clientNonce, serverNonce)

        assertEquals(32, secret1.size) // 256 bits = 32 bytes
        assertArrayEquals(secret1, secret2)

        // Changing even a single bit in the code yields a completely different secret
        val alteredSecret = MessageSecurity.deriveSharedSecret("482916", salt, clientNonce, serverNonce)
        assertFalse(secret1.contentEquals(alteredSecret))
    }

    @Test
    fun signAndVerify_succeedsForValidSignature_andRejectsTampering() {
        val secret = MessageSecurity.deriveSharedSecret("112233", "salt123", "clientNonce", "serverNonce")
        val canonicalData = "msg-1|1000|1|deviceA|POLICY_UPDATE_REQUEST|instagram|true"

        val signature = MessageSecurity.sign(canonicalData, secret)
        assertNotNull(signature)
        assertEquals(64, signature.length) // SHA-256 HMAC = 32 bytes = 64 hex chars

        // Valid signature passes verification
        assertTrue(MessageSecurity.verifySignature(canonicalData, signature, secret))

        // Tampering with canonical data fails verification
        val tamperedData = "msg-1|1000|1|deviceA|POLICY_UPDATE_REQUEST|instagram|false"
        assertFalse(MessageSecurity.verifySignature(tamperedData, signature, secret))

        // Tampering with signature string fails verification
        val tamperedSig = signature.substring(0, 63) + (if (signature.last() == '0') '1' else '0')
        assertFalse(MessageSecurity.verifySignature(canonicalData, tamperedSig, secret))

        // Wrong secret key fails verification
        val wrongSecret = MessageSecurity.deriveSharedSecret("999999", "salt123", "clientNonce", "serverNonce")
        assertFalse(MessageSecurity.verifySignature(canonicalData, signature, wrongSecret))
    }
}
