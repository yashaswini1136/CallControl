package com.callcontrol.app.pairing

import java.util.UUID

/**
 * Strongly typed protocol messages exchanged between Controller and Friend Device.
 *
 * Security:
 * - Every message has a unique [messageId] (UUID).
 * - Every message has a monotonic [sequenceNumber] for replay attack detection.
 * - Every message has a timestamp [timestampMs] for freshness / expiration checks.
 * - Authenticated messages carry an HMAC-SHA256 [signature] generated via [MessageSecurity].
 */
sealed class ProtocolMessage {
    abstract val messageId: String
    abstract val timestampMs: Long
    abstract val sequenceNumber: Long
    abstract val senderDeviceId: String
    abstract val signature: String?

    abstract fun messageType(): String
    abstract fun canonicalPayload(): String

    /**
     * Canonical string representation signed and verified with HMAC-SHA256.
     * Prevents field tampering or ambiguity.
     */
    fun canonicalData(): String {
        return "$messageId|$timestampMs|$sequenceNumber|$senderDeviceId|${messageType()}|${canonicalPayload()}"
    }

    // -------------------------------------------------------------
    // PAIRING HANDSHAKE MESSAGES
    // -------------------------------------------------------------

    data class PairRequest(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long = 0L,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val clientRole: DeviceRole = DeviceRole.CONTROLLER,
        val pairingId: String,
        val submittedCode: String,
        val clientNonce: String,
        val channelAuthProof: String? = null
    ) : ProtocolMessage() {
        override fun messageType(): String = "PAIR_REQUEST"
        override fun canonicalPayload(): String =
            "$clientRole|$pairingId|${submittedCode.trim()}|$clientNonce|${channelAuthProof.orEmpty()}"
    }

    data class PairResponse(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long = 0L,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val approved: Boolean,
        val saltHex: String,
        val serverNonce: String,
        val sessionToken: String?,
        val rejectionReason: String? = null,
        val serverChannelAuthProof: String? = null
    ) : ProtocolMessage() {
        override fun messageType(): String = "PAIR_RESPONSE"
        override fun canonicalPayload(): String =
            "$approved|$saltHex|$serverNonce|${sessionToken.orEmpty()}|${rejectionReason.orEmpty()}|${serverChannelAuthProof.orEmpty()}"
    }

    // -------------------------------------------------------------
    // HEARTBEAT MESSAGES
    // -------------------------------------------------------------

    data class Heartbeat(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val sessionToken: String
    ) : ProtocolMessage() {
        override fun messageType(): String = "HEARTBEAT"
        override fun canonicalPayload(): String = sessionToken
    }

    data class HeartbeatAck(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val sessionToken: String,
        val acknowledgedSequenceNumber: Long
    ) : ProtocolMessage() {
        override fun messageType(): String = "HEARTBEAT_ACK"
        override fun canonicalPayload(): String =
            "$sessionToken|$acknowledgedSequenceNumber"
    }

    // -------------------------------------------------------------
    // SESSION CONTROL MESSAGES
    // -------------------------------------------------------------

    data class SessionControlRequest(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val sessionToken: String,
        val action: SessionAction
    ) : ProtocolMessage() {
        override fun messageType(): String = "SESSION_CONTROL_REQUEST"
        override fun canonicalPayload(): String = "$sessionToken|$action"
    }

    data class SessionControlAck(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val sessionToken: String,
        val resultingSessionStatus: String,
        val success: Boolean,
        val errorMessage: String? = null
    ) : ProtocolMessage() {
        override fun messageType(): String = "SESSION_CONTROL_ACK"
        override fun canonicalPayload(): String =
            "$sessionToken|$resultingSessionStatus|$success|${errorMessage.orEmpty()}"
    }

    // -------------------------------------------------------------
    // POLICY UPDATE MESSAGES
    // -------------------------------------------------------------

    data class PolicyUpdateRequest(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val sessionToken: String,
        val appStorageKey: String,
        val targetLocked: Boolean
    ) : ProtocolMessage() {
        override fun messageType(): String = "POLICY_UPDATE_REQUEST"
        override fun canonicalPayload(): String =
            "$sessionToken|$appStorageKey|$targetLocked"
    }

    data class PolicyUpdateAck(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val sessionToken: String,
        val appStorageKey: String,
        val appliedLocked: Boolean,
        val success: Boolean,
        val errorMessage: String? = null
    ) : ProtocolMessage() {
        override fun messageType(): String = "POLICY_UPDATE_ACK"
        override fun canonicalPayload(): String =
            "$sessionToken|$appStorageKey|$appliedLocked|$success|${errorMessage.orEmpty()}"
    }

    // -------------------------------------------------------------
    // DISCONNECT & REVOCATION
    // -------------------------------------------------------------

    data class DisconnectOrRevoke(
        override val messageId: String = UUID.randomUUID().toString(),
        override val timestampMs: Long,
        override val sequenceNumber: Long,
        override val senderDeviceId: String,
        override val signature: String? = null,
        val sessionToken: String?,
        val isPermanentRevocation: Boolean,
        val reason: String
    ) : ProtocolMessage() {
        override fun messageType(): String = "DISCONNECT_OR_REVOKE"
        override fun canonicalPayload(): String =
            "${sessionToken.orEmpty()}|$isPermanentRevocation|$reason"
    }
}
