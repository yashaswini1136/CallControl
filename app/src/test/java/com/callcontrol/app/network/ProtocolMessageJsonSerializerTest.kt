package com.callcontrol.app.network

import com.callcontrol.app.pairing.DeviceRole
import com.callcontrol.app.pairing.ProtocolMessage
import com.callcontrol.app.pairing.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolMessageJsonSerializerTest {

    @Test
    fun serializeAndDeserialize_pairRequestRoundtrip() {
        val original = ProtocolMessage.PairRequest(
            messageId = "req-1",
            timestampMs = 123456L,
            sequenceNumber = 0L,
            senderDeviceId = "client-dev",
            signature = "sig-123",
            clientRole = DeviceRole.CONTROLLER,
            pairingId = "pair-abc",
            submittedCode = "654321",
            clientNonce = "nonce-c"
        )

        val bytes = ProtocolMessageJsonSerializer.serialize(original)
        val deserialized = ProtocolMessageJsonSerializer.deserialize(bytes) as ProtocolMessage.PairRequest

        assertEquals(original.messageId, deserialized.messageId)
        assertEquals(original.timestampMs, deserialized.timestampMs)
        assertEquals(original.sequenceNumber, deserialized.sequenceNumber)
        assertEquals(original.senderDeviceId, deserialized.senderDeviceId)
        assertEquals(original.signature, deserialized.signature)
        assertEquals(original.clientRole, deserialized.clientRole)
        assertEquals(original.pairingId, deserialized.pairingId)
        assertEquals(original.submittedCode, deserialized.submittedCode)
        assertEquals(original.clientNonce, deserialized.clientNonce)
    }

    @Test
    fun serializeAndDeserialize_pairResponseRoundtrip() {
        val original = ProtocolMessage.PairResponse(
            messageId = "resp-1",
            timestampMs = 123456L,
            sequenceNumber = 0L,
            senderDeviceId = "server-dev",
            signature = "sig-456",
            approved = true,
            saltHex = "salt-hex-val",
            serverNonce = "nonce-s",
            sessionToken = "token-xyz",
            rejectionReason = null
        )

        val bytes = ProtocolMessageJsonSerializer.serialize(original)
        val deserialized = ProtocolMessageJsonSerializer.deserialize(bytes) as ProtocolMessage.PairResponse

        assertEquals(original.approved, deserialized.approved)
        assertEquals(original.saltHex, deserialized.saltHex)
        assertEquals(original.serverNonce, deserialized.serverNonce)
        assertEquals(original.sessionToken, deserialized.sessionToken)
    }

    @Test
    fun serializeAndDeserialize_policyUpdateRequestRoundtrip() {
        val original = ProtocolMessage.PolicyUpdateRequest(
            messageId = "pol-1",
            timestampMs = 123456L,
            sequenceNumber = 7L,
            senderDeviceId = "controller-dev",
            signature = "sig-789",
            sessionToken = "tok-1",
            appStorageKey = "instagram",
            targetLocked = true
        )

        val bytes = ProtocolMessageJsonSerializer.serialize(original)
        val deserialized = ProtocolMessageJsonSerializer.deserialize(bytes) as ProtocolMessage.PolicyUpdateRequest

        assertEquals(original.messageId, deserialized.messageId)
        assertEquals(original.sequenceNumber, deserialized.sequenceNumber)
        assertEquals(original.appStorageKey, deserialized.appStorageKey)
        assertEquals(original.targetLocked, deserialized.targetLocked)
    }

    @Test
    fun serializeAndDeserialize_disconnectOrRevokeRoundtrip() {
        val original = ProtocolMessage.DisconnectOrRevoke(
            messageId = "disc-1",
            timestampMs = 123456L,
            sequenceNumber = 9L,
            senderDeviceId = "friend-dev",
            signature = "sig-000",
            sessionToken = "tok-1",
            isPermanentRevocation = true,
            reason = "Revoked by user"
        )

        val bytes = ProtocolMessageJsonSerializer.serialize(original)
        val deserialized = ProtocolMessageJsonSerializer.deserialize(bytes) as ProtocolMessage.DisconnectOrRevoke

        assertEquals(original.isPermanentRevocation, deserialized.isPermanentRevocation)
        assertEquals(original.reason, deserialized.reason)
    }

    @Test
    fun deserialize_rejectsMalformedJson() {
        val badJson = "not a json string".toByteArray(Charsets.UTF_8)
        try {
            ProtocolMessageJsonSerializer.deserialize(badJson)
            fail("Expected FrameException on bad JSON")
        } catch (e: FrameException) {
            assertTrue(e.message!!.contains("Malformed JSON"))
        }
    }

    @Test
    fun deserialize_rejectsMissingCoreFields() {
        val incompleteJson = "{\"type\":\"HEARTBEAT\"}".toByteArray(Charsets.UTF_8)
        try {
            ProtocolMessageJsonSerializer.deserialize(incompleteJson)
            fail("Expected FrameException on missing fields")
        } catch (e: FrameException) {
            assertTrue(e.message!!.contains("Missing core protocol message"))
        }
    }
}
