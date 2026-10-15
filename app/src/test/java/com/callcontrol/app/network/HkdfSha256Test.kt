package com.callcontrol.app.network

import com.callcontrol.app.pairing.PairingCredentials
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class HkdfSha256Test {

    @Test
    fun rfc5869_testCase1() {
        // RFC 5869 Test Case 1
        val ikm = PairingCredentials.hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = PairingCredentials.hexToBytes("000102030405060708090a0b0c")
        val info = PairingCredentials.hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val l = 42

        val expectedPrk = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
        val expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

        val prk = HkdfSha256.extract(salt, ikm)
        assertEquals(expectedPrk, PairingCredentials.bytesToHex(prk))

        val okm = HkdfSha256.expand(prk, info, l)
        assertEquals(expectedOkm, PairingCredentials.bytesToHex(okm))

        val combined = HkdfSha256.extractAndExpand(salt, ikm, info, l)
        assertEquals(expectedOkm, PairingCredentials.bytesToHex(combined))
    }

    @Test
    fun extractAndExpand_domainSeparationProducesDistinctKeys() {
        val ikm = "shared-ecdh-secret-bytes".toByteArray(Charsets.UTF_8)
        val salt = "binding-salt".toByteArray(Charsets.UTF_8)

        val clientKey = HkdfSha256.extractAndExpand(salt, ikm, "client-key".toByteArray(Charsets.UTF_8), 32)
        val serverKey = HkdfSha256.extractAndExpand(salt, ikm, "server-key".toByteArray(Charsets.UTF_8), 32)

        assertEquals(32, clientKey.size)
        assertEquals(32, serverKey.size)
        assertFalse(clientKey.contentEquals(serverKey))
    }
}
