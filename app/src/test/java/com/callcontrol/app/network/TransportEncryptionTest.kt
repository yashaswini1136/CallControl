package com.callcontrol.app.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransportEncryptionTest {

    @Test
    fun ecdhAndAesGcm_directionalEncryptAndDecryptRoundtripSuccessfully() {
        val alice = TransportEncryption(isInitiator = true)
        val bob = TransportEncryption(isInitiator = false)

        assertFalse(alice.isEstablished)
        assertFalse(bob.isEstablished)

        // Complete key exchange
        alice.completeKeyExchange(bob.localPublicKeyEncoded)
        bob.completeKeyExchange(alice.localPublicKeyEncoded)

        assertTrue(alice.isEstablished)
        assertTrue(bob.isEstablished)
        assertNotNull(alice.channelBindingToken)
        assertEquals(alice.channelBindingToken, bob.channelBindingToken)

        // Direction 1: Alice (Client) -> Bob (Server)
        val clientMessage = "Sensitive CallControl policy command from Client".toByteArray(Charsets.UTF_8)
        val clientCiphertext = alice.encrypt(clientMessage)
        assertNotNull(clientCiphertext)

        val bobDecrypted = bob.decrypt(clientCiphertext)
        assertArrayEquals(clientMessage, bobDecrypted)

        // Direction 2: Bob (Server) -> Alice (Client)
        val serverMessage = "Authenticated confirmation response from Server".toByteArray(Charsets.UTF_8)
        val serverCiphertext = bob.encrypt(serverMessage)
        assertNotNull(serverCiphertext)

        val aliceDecrypted = alice.decrypt(serverCiphertext)
        assertArrayEquals(serverMessage, aliceDecrypted)
    }

    @Test
    fun directionalKeyIsolation_preventsReflectionAttacks() {
        val alice = TransportEncryption(isInitiator = true)
        val bob = TransportEncryption(isInitiator = false)

        alice.completeKeyExchange(bob.localPublicKeyEncoded)
        bob.completeKeyExchange(alice.localPublicKeyEncoded)

        val msg = "Testing reflection prevention".toByteArray(Charsets.UTF_8)
        val aliceCiphertext = alice.encrypt(msg)

        // If Alice tries to decrypt her own outbound ciphertext (reflection attack),
        // it must fail because her inbound channel uses Bob's key, not her write key
        try {
            alice.decrypt(aliceCiphertext)
            fail("Expected TransportSecurityException on reflected packet")
        } catch (_: TransportSecurityException) {
            // Success
        }
    }

    @Test
    fun nonceMonotonicity_rejectsTransportReplayAttacks() {
        val alice = TransportEncryption(isInitiator = true)
        val bob = TransportEncryption(isInitiator = false)

        alice.completeKeyExchange(bob.localPublicKeyEncoded)
        bob.completeKeyExchange(alice.localPublicKeyEncoded)

        val frame1 = alice.encrypt("Frame 1".toByteArray(Charsets.UTF_8))
        val frame2 = alice.encrypt("Frame 2".toByteArray(Charsets.UTF_8))

        // Bob receives frame 1, then frame 2
        bob.decrypt(frame1)
        bob.decrypt(frame2)

        // Attacker replays frame 1 -> must be rejected due to monotonic counter check
        try {
            bob.decrypt(frame1)
            fail("Expected TransportSecurityException on replayed frame counter")
        } catch (e: TransportSecurityException) {
            assertTrue(e.message!!.contains("Replay attack detected"))
        }
    }

    @Test
    fun channelBindingAuthProof_detectsKeySubstitutionAndMitM() {
        val alice = TransportEncryption(isInitiator = true)
        val bob = TransportEncryption(isInitiator = false)
        val eve = TransportEncryption(isInitiator = false) // Active MitM

        alice.completeKeyExchange(bob.localPublicKeyEncoded)
        bob.completeKeyExchange(alice.localPublicKeyEncoded)

        val validCode = "849201"
        val salt = "saltsalt12345678"

        // Both parties compute channel auth proof using pairing code
        val aliceProof = alice.computeChannelAuthProof(validCode, salt, forClient = true)
        assertTrue(bob.verifyChannelAuthProof(validCode, salt, aliceProof, isClientProof = true))

        // Attacker without pairing code cannot forge auth proof
        assertFalse(bob.verifyChannelAuthProof("000000", salt, aliceProof, isClientProof = true))

        // If MitM substituted Eve's public key to Alice
        val aliceMitm = TransportEncryption(isInitiator = true)
        aliceMitm.completeKeyExchange(eve.localPublicKeyEncoded)
        val mitmProof = aliceMitm.computeChannelAuthProof(validCode, salt, forClient = true)

        // Bob rejects proof because channel binding tokens do not match
        assertFalse(bob.verifyChannelAuthProof(validCode, salt, mitmProof, isClientProof = true))
    }

    @Test
    fun decrypt_rejectsTamperedCiphertextDueToGcmAuthTagFailure() {
        val alice = TransportEncryption(isInitiator = true)
        val bob = TransportEncryption(isInitiator = false)

        alice.completeKeyExchange(bob.localPublicKeyEncoded)
        bob.completeKeyExchange(alice.localPublicKeyEncoded)

        val plaintext = "Hello secure channel".toByteArray(Charsets.UTF_8)
        val ciphertext = alice.encrypt(plaintext)

        // Tamper with ciphertext byte
        val tampered = ciphertext.clone()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()

        try {
            bob.decrypt(tampered)
            fail("Expected TransportSecurityException on tampered ciphertext")
        } catch (_: TransportSecurityException) {
            // Success
        }
    }

    @Test
    fun zeroize_clearsKeysAndResetsCounters() {
        val alice = TransportEncryption(isInitiator = true)
        val bob = TransportEncryption(isInitiator = false)

        alice.completeKeyExchange(bob.localPublicKeyEncoded)
        assertTrue(alice.isEstablished)

        alice.zeroize()
        assertFalse(alice.isEstablished)

        try {
            alice.encrypt("Test".toByteArray(Charsets.UTF_8))
            fail("Expected IllegalStateException after zeroize")
        } catch (_: IllegalStateException) {
            // Success
        }
    }
}
