package com.callcontrol.app.network

import com.callcontrol.app.pairing.CredentialValidationResult
import com.callcontrol.app.pairing.DeviceRole
import com.callcontrol.app.pairing.MessageSecurity
import com.callcontrol.app.pairing.MessageValidationResult
import com.callcontrol.app.pairing.MessageValidator
import com.callcontrol.app.pairing.PairingHandshakeEngine
import com.callcontrol.app.pairing.PairingState
import com.callcontrol.app.pairing.ProtocolMessage
import com.callcontrol.app.pairing.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LanSocketTransportSecurityTest {

    private fun findAvailablePort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    @Test
    fun unauthenticatedSocket_cannotExecutePrivilegedCommandsWithoutStage1Handshake() {
        val port = findAvailablePort()
        val server = LanSocketTransport(enableEncryption = true)
        val client = LanSocketTransport(enableEncryption = true)

        val validator = MessageValidator()
        var rejectedResult: MessageValidationResult? = null
        val messageProcessedLatch = CountDownLatch(1)

        val clientConnectedLatch = CountDownLatch(1)
        val serverConnectedLatch = CountDownLatch(1)

        server.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.CONNECTED) serverConnectedLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {
                // Server validates that incoming privileged message has no valid session token or shared secret
                rejectedResult = validator.validateMessage(
                    message = message,
                    sharedSecret = null, // No pairing handshake completed
                    expectedSessionToken = null,
                    currentTimeMs = System.currentTimeMillis()
                )
                messageProcessedLatch.countDown()
            }
            override fun onError(throwable: Throwable) {}
        })

        client.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.CONNECTED) clientConnectedLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {}
            override fun onError(throwable: Throwable) {}
        })

        server.startListening(port, "127.0.0.1")
        client.connect("127.0.0.1", port)

        assertTrue(serverConnectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(clientConnectedLatch.await(5, TimeUnit.SECONDS))

        // Attacker over established TCP sends an unauthenticated PolicyUpdateRequest
        val unauthorizedCommand = ProtocolMessage.PolicyUpdateRequest(
            messageId = "attack-cmd-1",
            timestampMs = System.currentTimeMillis(),
            sequenceNumber = 1L,
            senderDeviceId = "attacker-phone",
            signature = "fake-sig-000",
            sessionToken = "fake-token",
            appStorageKey = "instagram",
            targetLocked = false
        )

        client.sendMessage(unauthorizedCommand)
        assertTrue("Server should receive and evaluate message", messageProcessedLatch.await(5, TimeUnit.SECONDS))

        assertNotNull(rejectedResult)
        // Must be rejected due to missing/invalid authentication signature
        assertTrue(
            "Privileged message over raw TCP without Stage 1 pairing must be rejected",
            rejectedResult is MessageValidationResult.InvalidSignature
        )

        client.disconnect()
        server.disconnect()
    }

    @Test
    fun disconnect_wipesTransportAndPreventsSubsequentMessaging() {
        val port = findAvailablePort()
        val server = LanSocketTransport(enableEncryption = false)
        val client = LanSocketTransport(enableEncryption = false)

        val clientLatch = CountDownLatch(1)
        client.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.CONNECTED) clientLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {}
            override fun onError(throwable: Throwable) {}
        })

        server.startListening(port, "127.0.0.1")
        client.connect("127.0.0.1", port)
        assertTrue("Client should connect within 5s", clientLatch.await(5, TimeUnit.SECONDS))

        assertTrue(client.isConnected)
        client.disconnect()

        assertFalse(client.isConnected)
        // Sending after disconnect must return false
        val sent = client.sendMessage(
            ProtocolMessage.Heartbeat(
                timestampMs = 1000L,
                sequenceNumber = 1L,
                senderDeviceId = "client",
                sessionToken = "tok"
            )
        )
        assertFalse(sent)
        server.disconnect()
    }

    @Test
    fun liveSocket_fullHandshakeWithChannelAuth_succeedsAndEnablesPrivilegedMessages() {
        val port = findAvailablePort()
        val server = LanSocketTransport(enableEncryption = true)
        val client = LanSocketTransport(enableEncryption = true)

        val friendEngine = PairingHandshakeEngine("friend-dev-1", DeviceRole.FRIEND_DEVICE)
        val controllerEngine = PairingHandshakeEngine("controller-dev-2", DeviceRole.CONTROLLER)

        val clientConnectedLatch = CountDownLatch(1)
        val serverConnectedLatch = CountDownLatch(1)
        val serverPairRequestLatch = CountDownLatch(1)
        val clientPairResponseLatch = CountDownLatch(1)
        val serverPrivilegedCommandLatch = CountDownLatch(1)

        val receivedValidationResult = AtomicReference<MessageValidationResult>()
        var clientReceivedResponse: ProtocolMessage.PairResponse? = null

        server.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.CONNECTED) serverConnectedLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {
                when (message) {
                    is ProtocolMessage.PairRequest -> {
                        val evalResult = friendEngine.evaluatePairRequest(
                            request = message,
                            currentTimeMs = System.currentTimeMillis(),
                            channelAuthVerifier = { code, salt, proof ->
                                server.verifyChannelAuthProof(code, salt, proof, isClientProof = true)
                            }
                        )
                        if (evalResult is CredentialValidationResult.Success) {
                            val creds = friendEngine.activeCredentials!!
                            val serverProof = server.computeChannelAuthProof(creds.code, creds.saltHex, forClient = false)
                            val response = friendEngine.approvePairing(
                                request = message,
                                currentTimeMs = System.currentTimeMillis(),
                                serverChannelAuthProof = serverProof
                            )
                            server.sendMessage(response)
                        }
                        serverPairRequestLatch.countDown()
                    }
                    is ProtocolMessage.SessionControlRequest -> {
                        val res = friendEngine.validateIncomingMessage(message, System.currentTimeMillis())
                        receivedValidationResult.set(res)
                        serverPrivilegedCommandLatch.countDown()
                    }
                    else -> {}
                }
            }
            override fun onError(throwable: Throwable) {}
        })

        client.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.CONNECTED) clientConnectedLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {
                if (message is ProtocolMessage.PairResponse) {
                    clientReceivedResponse = message
                    clientPairResponseLatch.countDown()
                }
            }
            override fun onError(throwable: Throwable) {}
        })

        server.startListening(port, "127.0.0.1")
        client.connect("127.0.0.1", port)

        assertTrue(serverConnectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(clientConnectedLatch.await(5, TimeUnit.SECONDS))

        // Ephemeral channel binding token must match across real socket peers
        assertNotNull(client.getChannelBindingToken())
        assertEquals(client.getChannelBindingToken(), server.getChannelBindingToken())

        val now = System.currentTimeMillis()
        val creds = friendEngine.generatePairingCredentials(now)
        val clientNonce = MessageSecurity.generateNonce()

        // Client computes channel auth proof bound to its underlying socket encryption
        val clientAuthProof = client.computeChannelAuthProof(creds.code, creds.saltHex, forClient = true)
        val pairRequest = controllerEngine.createPairRequest(
            pairingId = creds.pairingId,
            submittedCode = creds.code,
            clientNonce = clientNonce,
            currentTimeMs = now,
            channelAuthProof = clientAuthProof
        )

        // Send over encrypted socket
        client.sendMessage(pairRequest)

        assertTrue("Server should receive and evaluate PairRequest", serverPairRequestLatch.await(5, TimeUnit.SECONDS))
        assertEquals(PairingState.CONNECTED, friendEngine.state)

        assertTrue("Client should receive PairResponse", clientPairResponseLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(clientReceivedResponse)

        val verified = controllerEngine.handlePairResponse(
            response = clientReceivedResponse!!,
            submittedCode = creds.code,
            clientNonce = clientNonce,
            serverChannelAuthVerifier = { code, salt, proof ->
                client.verifyChannelAuthProof(code, salt, proof, isClientProof = false)
            }
        )
        assertTrue("Controller must verify PairResponse signature and channel auth proof", verified)
        assertEquals(PairingState.CONNECTED, controllerEngine.state)

        // Now send privileged session command over the authenticated socket
        val privilegedCmd = controllerEngine.signMessage { seq ->
            ProtocolMessage.SessionControlRequest(
                timestampMs = System.currentTimeMillis(),
                sequenceNumber = seq,
                senderDeviceId = controllerEngine.localDeviceId,
                sessionToken = controllerEngine.activeSessionToken!!,
                action = SessionAction.START
            )
        }

        client.sendMessage(privilegedCmd)
        assertTrue("Server should receive privileged command", serverPrivilegedCommandLatch.await(5, TimeUnit.SECONDS))
        assertTrue(
            "Privileged command over authenticated pairing must be valid",
            receivedValidationResult.get() is MessageValidationResult.Valid
        )

        client.disconnect()
        server.disconnect()
    }

    @Test
    fun liveSocket_activeMitmKeySubstitution_rejectedByChannelAuthProof() {
        val port = findAvailablePort()
        val server = LanSocketTransport(enableEncryption = true)
        val client = LanSocketTransport(enableEncryption = true)

        val friendEngine = PairingHandshakeEngine("friend-dev-1", DeviceRole.FRIEND_DEVICE)

        val serverConnectedLatch = CountDownLatch(1)
        val serverEvaluatedLatch = CountDownLatch(1)
        var evaluatedResult: CredentialValidationResult? = null

        server.setListener(object : TransportListener {
            override fun onStateChanged(newState: TransportState, detail: String?) {
                if (newState == TransportState.CONNECTED) serverConnectedLatch.countDown()
            }
            override fun onMessageReceived(message: ProtocolMessage) {
                if (message is ProtocolMessage.PairRequest) {
                    evaluatedResult = friendEngine.evaluatePairRequest(
                        request = message,
                        currentTimeMs = System.currentTimeMillis(),
                        channelAuthVerifier = { code, salt, proof ->
                            server.verifyChannelAuthProof(code, salt, proof, isClientProof = true)
                        }
                    )
                    serverEvaluatedLatch.countDown()
                }
            }
            override fun onError(throwable: Throwable) {}
        })

        server.startListening(port, "127.0.0.1")
        client.connect("127.0.0.1", port)

        assertTrue(serverConnectedLatch.await(5, TimeUnit.SECONDS))

        val now = System.currentTimeMillis()
        val creds = friendEngine.generatePairingCredentials(now)

        // Simulate active MitM where client proof was computed against a different channel binding
        // (e.g. an attacker substituted public keys during the unauthenticated ECDH exchange)
        val mitmProof = "tampered-or-substituted-key-binding-proof-hex"
        val pairRequest = ProtocolMessage.PairRequest(
            timestampMs = now,
            sequenceNumber = 0L,
            senderDeviceId = "attacker-controller",
            clientRole = DeviceRole.CONTROLLER,
            pairingId = creds.pairingId,
            submittedCode = creds.code,
            clientNonce = "attacker-nonce-123",
            channelAuthProof = mitmProof
        )

        client.sendMessage(pairRequest)
        assertTrue("Server should evaluate request", serverEvaluatedLatch.await(5, TimeUnit.SECONDS))

        // Verification must fail due to channel authentication proof mismatch
        assertTrue(
            "Channel proof mismatch must result in invalid attempt",
            evaluatedResult is CredentialValidationResult.InvalidCode
        )
        // Pairing state must remain pending (not connected)
        assertEquals(PairingState.PAIRING_PENDING, friendEngine.state)
        assertEquals(1, creds.attemptCount)

        client.disconnect()
        server.disconnect()
    }
}
