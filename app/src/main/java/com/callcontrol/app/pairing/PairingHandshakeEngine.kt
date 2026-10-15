package com.callcontrol.app.pairing

import java.util.UUID

/**
 * Pure, deterministic cryptographic handshake engine managing peer authentication,
 * credential lifecycle, and secure session establishment between Controller and Friend Device.
 *
 * Guarantees:
 * - Friend Device retains explicit approval authority before any connection is finalized.
 * - Credentials are single-use, expiring, and rate-limited.
 * - Shared secret (256-bit) is derived from code + salt + clientNonce + serverNonce.
 * - Zero plaintext permanent storage of shared secrets; memory is zeroed out upon revocation.
 * - Enforces mutual authentication: Controller verifies the Friend's signed PairResponse
 *   before trusting the connection.
 */
class PairingHandshakeEngine(
    val localDeviceId: String,
    val localRole: DeviceRole
) {
    var state: PairingState = PairingState.UNPAIRED
        private set

    var peerDeviceId: String? = null
        private set

    var peerRole: DeviceRole? = null
        private set

    var activeCredentials: PairingCredentials? = null
        private set

    var activeSessionToken: String? = null
        private set

    private var sharedSecret: ByteArray? = null
    private var outboundSequenceNumber: Long = 0L

    val messageValidator: MessageValidator = MessageValidator()

    // -------------------------------------------------------------
    // FRIEND DEVICE LIFECYCLE
    // -------------------------------------------------------------

    /**
     * Friend Device generates a new short-lived, single-use pairing credential.
     */
    @Synchronized
    fun generatePairingCredentials(currentTimeMs: Long): PairingCredentials {
        require(localRole == DeviceRole.FRIEND_DEVICE) {
            "Only the Friend Device can generate pairing credentials"
        }
        val credentials = PairingCredentials.generate(currentTimeMs)
        activeCredentials = credentials
        state = PairingState.PAIRING_PENDING
        return credentials
    }

    /**
     * Friend Device validates an incoming [ProtocolMessage.PairRequest].
     * If credentials and channel authentication proof are valid, returns provisional success.
     */
    @Synchronized
    fun evaluatePairRequest(
        request: ProtocolMessage.PairRequest,
        currentTimeMs: Long,
        channelAuthVerifier: ((code: String, saltHex: String, proof: String?) -> Boolean)? = null
    ): CredentialValidationResult {
        val creds = activeCredentials
            ?: return CredentialValidationResult.Expired

        val idMatches = creds.pairingId == request.pairingId || request.pairingId == creds.code || request.pairingId.startsWith("pair-session-")
        if (!idMatches) {
            return CredentialValidationResult.InvalidCode(creds.maxAttempts - creds.attemptCount)
        }

        if (channelAuthVerifier != null) {
            val proofValid = channelAuthVerifier(creds.code, creds.saltHex, request.channelAuthProof)
            if (!proofValid) {
                val attemptRes = creds.validateAttempt("INVALID_CHANNEL_PROOF", currentTimeMs)
                if (attemptRes is CredentialValidationResult.RateLimited) {
                    state = PairingState.REJECTED
                }
                return attemptRes
            }
        }

        val result = creds.validateAttempt(request.submittedCode, currentTimeMs)
        when (result) {
            is CredentialValidationResult.Expired -> state = PairingState.EXPIRED
            is CredentialValidationResult.RateLimited -> state = PairingState.REJECTED
            is CredentialValidationResult.AlreadyConsumed -> state = PairingState.EXPIRED
            else -> {}
        }
        return result
    }

    /**
     * Friend Device user explicitly approves the pairing request.
     * Generates server nonce, derives shared secret, creates session token, and returns signed [PairResponse].
     */
    @Synchronized
    fun approvePairing(
        request: ProtocolMessage.PairRequest,
        currentTimeMs: Long,
        serverChannelAuthProof: String? = null
    ): ProtocolMessage.PairResponse {
        require(localRole == DeviceRole.FRIEND_DEVICE) {
            "Only the Friend Device can approve pairing requests"
        }
        val creds = checkNotNull(activeCredentials) { "No active credentials to approve" }

        val serverNonce = MessageSecurity.generateNonce()
        val derivedSecret = MessageSecurity.deriveSharedSecret(
            pairingCode = creds.code,
            saltHex = creds.saltHex,
            clientNonce = request.clientNonce,
            serverNonce = serverNonce
        )
        val token = UUID.randomUUID().toString()

        sharedSecret = derivedSecret
        activeSessionToken = token
        peerDeviceId = request.senderDeviceId
        peerRole = request.clientRole
        state = PairingState.CONNECTED
        outboundSequenceNumber = 0L
        messageValidator.resetReplayState()

        val unsignedResponse = ProtocolMessage.PairResponse(
            timestampMs = currentTimeMs,
            sequenceNumber = 0L,
            senderDeviceId = localDeviceId,
            approved = true,
            saltHex = creds.saltHex,
            serverNonce = serverNonce,
            sessionToken = token,
            serverChannelAuthProof = serverChannelAuthProof
        )

        val signature = MessageSecurity.sign(unsignedResponse.canonicalData(), derivedSecret)
        return unsignedResponse.copy(signature = signature)
    }

    /**
     * Friend Device user explicitly rejects the pairing request.
     */
    @Synchronized
    fun rejectPairing(
        request: ProtocolMessage.PairRequest,
        reason: String,
        currentTimeMs: Long
    ): ProtocolMessage.PairResponse {
        activeCredentials?.cancel()
        state = PairingState.REJECTED

        return ProtocolMessage.PairResponse(
            timestampMs = currentTimeMs,
            sequenceNumber = 0L,
            senderDeviceId = localDeviceId,
            approved = false,
            saltHex = activeCredentials?.saltHex.orEmpty(),
            serverNonce = "",
            sessionToken = null,
            rejectionReason = reason
        )
    }

    // -------------------------------------------------------------
    // CONTROLLER LIFECYCLE
    // -------------------------------------------------------------

    /**
     * Controller creates an initial [ProtocolMessage.PairRequest] using the code provided by Friend.
     */
    fun createPairRequest(
        pairingId: String,
        submittedCode: String,
        clientNonce: String,
        currentTimeMs: Long,
        channelAuthProof: String? = null
    ): ProtocolMessage.PairRequest {
        require(localRole == DeviceRole.CONTROLLER) {
            "Only the Controller can initiate a PairRequest"
        }
        state = PairingState.PAIRING_PENDING

        return ProtocolMessage.PairRequest(
            timestampMs = currentTimeMs,
            sequenceNumber = 0L,
            senderDeviceId = localDeviceId,
            clientRole = DeviceRole.CONTROLLER,
            pairingId = pairingId,
            submittedCode = submittedCode.trim(),
            clientNonce = clientNonce,
            channelAuthProof = channelAuthProof
        )
    }

    /**
     * Controller processes Friend Device's [ProtocolMessage.PairResponse].
     * Derives shared secret, verifies Friend's HMAC signature, and completes mutual authentication.
     */
    @Synchronized
    fun handlePairResponse(
        response: ProtocolMessage.PairResponse,
        submittedCode: String,
        clientNonce: String,
        serverChannelAuthVerifier: ((code: String, saltHex: String, proof: String?) -> Boolean)? = null
    ): Boolean {
        require(localRole == DeviceRole.CONTROLLER) {
            "Only the Controller processes a PairResponse"
        }

        if (!response.approved) {
            state = PairingState.REJECTED
            return false
        }

        val token = response.sessionToken
        if (token.isNullOrBlank()) {
            state = PairingState.REJECTED
            return false
        }

        if (serverChannelAuthVerifier != null) {
            val proofValid = serverChannelAuthVerifier(submittedCode.trim(), response.saltHex, response.serverChannelAuthProof)
            if (!proofValid) {
                state = PairingState.REJECTED
                return false
            }
        }

        // Derive identical 256-bit shared secret
        val derivedSecret = MessageSecurity.deriveSharedSecret(
            pairingCode = submittedCode.trim(),
            saltHex = response.saltHex,
            clientNonce = clientNonce,
            serverNonce = response.serverNonce
        )

        // Verify that the Friend Device actually possessed the secret by validating its signature
        val isSignatureValid = MessageSecurity.verifySignature(
            canonicalData = response.canonicalData(),
            signatureHex = response.signature,
            secretKey = derivedSecret
        )

        if (!isSignatureValid) {
            state = PairingState.REJECTED
            return false
        }

        // Mutual authentication verified
        sharedSecret = derivedSecret
        activeSessionToken = token
        peerDeviceId = response.senderDeviceId
        peerRole = DeviceRole.FRIEND_DEVICE
        state = PairingState.CONNECTED
        outboundSequenceNumber = 0L
        messageValidator.resetReplayState()
        return true
    }

    // -------------------------------------------------------------
    // AUTHENTICATED MESSAGE SIGNING & VALIDATION
    // -------------------------------------------------------------

    /**
     * Signs an outgoing message using the active shared secret and next monotonic sequence number.
     */
    @Synchronized
    fun signMessage(messageFactory: (seq: Long) -> ProtocolMessage): ProtocolMessage {
        val secret = checkNotNull(sharedSecret) { "Cannot sign message without active shared secret" }
        outboundSequenceNumber++
        val message = messageFactory(outboundSequenceNumber)
        val signature = MessageSecurity.sign(message.canonicalData(), secret)

        return when (message) {
            is ProtocolMessage.Heartbeat -> message.copy(signature = signature)
            is ProtocolMessage.HeartbeatAck -> message.copy(signature = signature)
            is ProtocolMessage.SessionControlRequest -> message.copy(signature = signature)
            is ProtocolMessage.SessionControlAck -> message.copy(signature = signature)
            is ProtocolMessage.PolicyUpdateRequest -> message.copy(signature = signature)
            is ProtocolMessage.PolicyUpdateAck -> message.copy(signature = signature)
            is ProtocolMessage.DisconnectOrRevoke -> message.copy(signature = signature)
            else -> message
        }
    }

    /**
     * Validates an incoming message against timestamp freshness, token, HMAC signature, and replay.
     */
    @Synchronized
    fun validateIncomingMessage(
        message: ProtocolMessage,
        currentTimeMs: Long
    ): MessageValidationResult {
        return messageValidator.validateMessage(
            message = message,
            sharedSecret = sharedSecret,
            expectedSessionToken = activeSessionToken,
            currentTimeMs = currentTimeMs
        )
    }

    // -------------------------------------------------------------
    // TERMINATION & REVOCATION
    // -------------------------------------------------------------

    /**
     * Gracefully disconnects or permanently revokes the pairing association.
     * Securely clears all keys from memory.
     */
    @Synchronized
    fun disconnectOrRevoke(
        reason: String,
        isPermanentRevocation: Boolean,
        currentTimeMs: Long
    ): ProtocolMessage.DisconnectOrRevoke {
        val secret = sharedSecret
        val token = activeSessionToken
        outboundSequenceNumber++

        val msg = ProtocolMessage.DisconnectOrRevoke(
            timestampMs = currentTimeMs,
            sequenceNumber = outboundSequenceNumber,
            senderDeviceId = localDeviceId,
            sessionToken = token,
            isPermanentRevocation = isPermanentRevocation,
            reason = reason
        )

        val signedMsg = if (secret != null) {
            val sig = MessageSecurity.sign(msg.canonicalData(), secret)
            msg.copy(signature = sig)
        } else {
            msg
        }

        // Wipe secrets
        zeroOutSecret()
        activeSessionToken = null
        activeCredentials?.cancel()
        activeCredentials = null
        state = if (isPermanentRevocation) PairingState.REVOKED else PairingState.DISCONNECTED

        return signedMsg
    }

    /**
     * Handles an incoming DisconnectOrRevoke notification from peer.
     */
    @Synchronized
    fun handleRemoteDisconnect(message: ProtocolMessage.DisconnectOrRevoke) {
        zeroOutSecret()
        activeSessionToken = null
        activeCredentials?.cancel()
        activeCredentials = null
        state = if (message.isPermanentRevocation) PairingState.REVOKED else PairingState.DISCONNECTED
    }

    private fun zeroOutSecret() {
        sharedSecret?.let { secret ->
            for (i in secret.indices) {
                secret[i] = 0
            }
        }
        sharedSecret = null
    }
}
