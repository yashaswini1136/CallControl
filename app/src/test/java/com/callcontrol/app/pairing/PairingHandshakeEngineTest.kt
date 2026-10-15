package com.callcontrol.app.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingHandshakeEngineTest {

    @Test
    fun mutualAuthenticationHandshake_succeeds_andEnablesAuthenticatedCommunication() {
        val now = 100_000L
        val friendEngine = PairingHandshakeEngine(
            localDeviceId = "friend-phone-1",
            localRole = DeviceRole.FRIEND_DEVICE
        )
        val controllerEngine = PairingHandshakeEngine(
            localDeviceId = "controller-phone-2",
            localRole = DeviceRole.CONTROLLER
        )

        assertEquals(PairingState.UNPAIRED, friendEngine.state)
        assertEquals(PairingState.UNPAIRED, controllerEngine.state)

        // 1. Friend generates pairing credentials
        val creds = friendEngine.generatePairingCredentials(currentTimeMs = now)
        assertEquals(PairingState.PAIRING_PENDING, friendEngine.state)

        // 2. Controller enters code and creates PairRequest
        val clientNonce = MessageSecurity.generateNonce()
        val pairRequest = controllerEngine.createPairRequest(
            pairingId = creds.pairingId,
            submittedCode = creds.code,
            clientNonce = clientNonce,
            currentTimeMs = now + 1_000L
        )
        assertEquals(PairingState.PAIRING_PENDING, controllerEngine.state)

        // 3. Friend evaluates request (verifies code, attempts, etc.)
        val evalResult = friendEngine.evaluatePairRequest(pairRequest, currentTimeMs = now + 2_000L)
        assertTrue(evalResult is CredentialValidationResult.Success)

        // 4. Friend explicitly approves the request
        val pairResponse = friendEngine.approvePairing(pairRequest, currentTimeMs = now + 3_000L)
        assertTrue(pairResponse.approved)
        assertNotNull(pairResponse.sessionToken)
        assertNotNull(pairResponse.signature)
        assertEquals(PairingState.CONNECTED, friendEngine.state)
        assertEquals("controller-phone-2", friendEngine.peerDeviceId)

        // 5. Controller receives PairResponse, verifies signature & establishes mutual trust
        val controllerApproved = controllerEngine.handlePairResponse(
            response = pairResponse,
            submittedCode = creds.code,
            clientNonce = clientNonce
        )
        assertTrue(controllerApproved)
        assertEquals(PairingState.CONNECTED, controllerEngine.state)
        assertEquals("friend-phone-1", controllerEngine.peerDeviceId)
        assertEquals(friendEngine.activeSessionToken, controllerEngine.activeSessionToken)

        // 6. Test authenticated bidirectional messaging
        // Controller -> Friend: SessionControlRequest(START)
        val sessionStartMsg = controllerEngine.signMessage { seq ->
            ProtocolMessage.SessionControlRequest(
                timestampMs = now + 5_000L,
                sequenceNumber = seq,
                senderDeviceId = controllerEngine.localDeviceId,
                sessionToken = controllerEngine.activeSessionToken!!,
                action = SessionAction.START
            )
        }

        val friendValidation = friendEngine.validateIncomingMessage(sessionStartMsg, now + 5_000L)
        assertTrue(friendValidation is MessageValidationResult.Valid)

        // Friend -> Controller: SessionControlAck
        val sessionStartAck = friendEngine.signMessage { seq ->
            ProtocolMessage.SessionControlAck(
                timestampMs = now + 6_000L,
                sequenceNumber = seq,
                senderDeviceId = friendEngine.localDeviceId,
                sessionToken = friendEngine.activeSessionToken!!,
                resultingSessionStatus = "ACTIVE",
                success = true
            )
        }

        val controllerValidation = controllerEngine.validateIncomingMessage(sessionStartAck, now + 6_000L)
        assertTrue(controllerValidation is MessageValidationResult.Valid)
    }

    @Test
    fun friendReject_transitionsBothPartiesToRejected_andDoesNotEstablishKeys() {
        val now = 100_000L
        val friend = PairingHandshakeEngine("friend-1", DeviceRole.FRIEND_DEVICE)
        val controller = PairingHandshakeEngine("controller-1", DeviceRole.CONTROLLER)

        val creds = friend.generatePairingCredentials(now)
        val clientNonce = MessageSecurity.generateNonce()
        val request = controller.createPairRequest(creds.pairingId, creds.code, clientNonce, now + 1_000L)

        // Friend explicitly declines
        val response = friend.rejectPairing(request, reason = "User rejected request", currentTimeMs = now + 2_000L)
        assertFalse(response.approved)
        assertEquals(PairingState.REJECTED, friend.state)
        assertNull(friend.activeSessionToken)

        val completed = controller.handlePairResponse(response, creds.code, clientNonce)
        assertFalse(completed)
        assertEquals(PairingState.REJECTED, controller.state)
        assertNull(controller.activeSessionToken)
    }

    @Test
    fun invalidPairingCode_failsValidation_andPreventsConnection() {
        val now = 100_000L
        val friend = PairingHandshakeEngine("friend-1", DeviceRole.FRIEND_DEVICE)
        val controller = PairingHandshakeEngine("controller-1", DeviceRole.CONTROLLER)

        val creds = friend.generatePairingCredentials(now)
        val wrongCode = if (creds.code == "111111") "222222" else "111111"
        val clientNonce = MessageSecurity.generateNonce()
        val request = controller.createPairRequest(creds.pairingId, wrongCode, clientNonce, now + 1_000L)

        val eval = friend.evaluatePairRequest(request, now + 2_000L)
        assertTrue(eval is CredentialValidationResult.InvalidCode)
        assertEquals(PairingState.PAIRING_PENDING, friend.state)
    }

    @Test
    fun revocation_clearsSecretsAndTransitionsToRevoked() {
        val now = 100_000L
        val friend = PairingHandshakeEngine("friend-1", DeviceRole.FRIEND_DEVICE)
        val controller = PairingHandshakeEngine("controller-1", DeviceRole.CONTROLLER)

        val creds = friend.generatePairingCredentials(now)
        val clientNonce = MessageSecurity.generateNonce()
        val request = controller.createPairRequest(creds.pairingId, creds.code, clientNonce, now + 1_000L)
        friend.evaluatePairRequest(request, now + 2_000L)
        val response = friend.approvePairing(request, now + 3_000L)
        controller.handlePairResponse(response, creds.code, clientNonce)

        assertEquals(PairingState.CONNECTED, friend.state)
        assertEquals(PairingState.CONNECTED, controller.state)

        // Friend revokes pairing
        val revokeMsg = friend.disconnectOrRevoke(
            reason = "Revoked by user",
            isPermanentRevocation = true,
            currentTimeMs = now + 10_000L
        )

        assertEquals(PairingState.REVOKED, friend.state)
        assertNull(friend.activeSessionToken)

        // Controller processes revocation
        controller.handleRemoteDisconnect(revokeMsg)
        assertEquals(PairingState.REVOKED, controller.state)
        assertNull(controller.activeSessionToken)
    }
}
