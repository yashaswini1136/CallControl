package com.callcontrol.app.pairing

import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.abs

/**
 * Result of validating an incoming [ProtocolMessage].
 */
sealed class MessageValidationResult {
    data object Valid : MessageValidationResult()
    data class InvalidSignature(val reason: String) : MessageValidationResult()
    data class ExpiredTimestamp(val messageTimeMs: Long, val currentTimeMs: Long, val driftMs: Long) : MessageValidationResult()
    data class ReplayDetected(val reason: String) : MessageValidationResult()
    data class UnauthorizedSessionToken(val providedToken: String?, val expectedToken: String) : MessageValidationResult()
    data class MalformedMessage(val reason: String) : MessageValidationResult()
}

/**
 * Replay protection engine.
 *
 * Enforces two independent defenses against replay attacks:
 * 1. Sliding window message-ID deduplication (remembers recent message UUIDs).
 * 2. Monotonic sequence numbering: for an established session, incoming sequence numbers
 *    must strictly exceed the highest previously received sequence number.
 */
class ReplayProtection(
    private val maxCacheSize: Int = DEFAULT_MAX_CACHE_SIZE,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {
    private var highestSequenceNumber: Long = 0L

    // LinkedHashMap with access-order eviction to bound memory usage
    private val seenMessageIds = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(maxCacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > maxCacheSize
            }
        }
    )

    /**
     * Checks if the message ID has been seen or the sequence number violates monotonicity.
     * If valid, records the message and returns true. If duplicate or out of order, returns false.
     */
    @Synchronized
    fun checkAndRecord(
        messageId: String,
        sequenceNumber: Long,
        currentTimeMs: Long,
        enforceSequenceMonotonicity: Boolean = true
    ): Boolean {
        if (messageId.isBlank()) return false

        // Check if message ID was already processed
        if (seenMessageIds.containsKey(messageId)) {
            return false
        }

        // Monotonic sequence check for session messages
        if (enforceSequenceMonotonicity) {
            if (sequenceNumber <= highestSequenceNumber) {
                return false
            }
            highestSequenceNumber = sequenceNumber
        }

        pruneOldEntries(currentTimeMs)
        seenMessageIds[messageId] = currentTimeMs
        return true
    }

    @Synchronized
    fun reset(initialSequenceNumber: Long = 0L) {
        seenMessageIds.clear()
        highestSequenceNumber = initialSequenceNumber
    }

    private fun pruneOldEntries(currentTimeMs: Long) {
        val iterator = seenMessageIds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTimeMs - entry.value > maxAgeMs) {
                iterator.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_CACHE_SIZE: Int = 1000
        const val DEFAULT_MAX_AGE_MS: Long = 60_000L // 60 seconds
    }
}

/**
 * Comprehensive validator for incoming [ProtocolMessage] instances.
 *
 * Validates:
 * 1. Malformed structure (empty IDs, blank sender).
 * 2. Timestamp freshness (drift within [maxClockDriftMs]).
 * 3. Session token authorization.
 * 4. HMAC-SHA256 signature verification via [MessageSecurity].
 * 5. Replay attack rejection via [ReplayProtection].
 */
class MessageValidator(
    private val replayProtection: ReplayProtection = ReplayProtection(),
    private val maxClockDriftMs: Long = DEFAULT_MAX_CLOCK_DRIFT_MS
) {

    fun validateMessage(
        message: ProtocolMessage,
        sharedSecret: ByteArray?,
        expectedSessionToken: String? = null,
        currentTimeMs: Long
    ): MessageValidationResult {
        // 1. Structure check
        if (message.messageId.isBlank() || message.senderDeviceId.isBlank()) {
            return MessageValidationResult.MalformedMessage("Message contains blank ID or sender")
        }

        // 2. Timestamp Freshness check
        val driftMs = abs(currentTimeMs - message.timestampMs)
        if (driftMs > maxClockDriftMs) {
            return MessageValidationResult.ExpiredTimestamp(
                messageTimeMs = message.timestampMs,
                currentTimeMs = currentTimeMs,
                driftMs = driftMs
            )
        }

        // 3. Session token check (for session-bound messages)
        val messageSessionToken = extractSessionToken(message)
        if (expectedSessionToken != null && messageSessionToken != null) {
            if (!PairingCredentials.constantTimeEquals(expectedSessionToken, messageSessionToken)) {
                return MessageValidationResult.UnauthorizedSessionToken(
                    providedToken = messageSessionToken,
                    expectedToken = expectedSessionToken
                )
            }
        }

        // 4. Cryptographic HMAC Signature check
        val requiresSignature = message !is ProtocolMessage.PairRequest &&
            !(message is ProtocolMessage.PairResponse && !message.approved)

        if (requiresSignature) {
            if (sharedSecret == null || sharedSecret.isEmpty()) {
                return MessageValidationResult.InvalidSignature("Missing shared authentication secret")
            }
            if (message.signature.isNullOrBlank()) {
                return MessageValidationResult.InvalidSignature("Message is missing required HMAC signature")
            }
            val validSignature = MessageSecurity.verifySignature(
                canonicalData = message.canonicalData(),
                signatureHex = message.signature,
                secretKey = sharedSecret
            )
            if (!validSignature) {
                return MessageValidationResult.InvalidSignature("HMAC-SHA256 signature mismatch")
            }
        }

        // 5. Replay Protection check
        val enforceSequence = message !is ProtocolMessage.PairRequest && message !is ProtocolMessage.PairResponse
        val recorded = replayProtection.checkAndRecord(
            messageId = message.messageId,
            sequenceNumber = message.sequenceNumber,
            currentTimeMs = currentTimeMs,
            enforceSequenceMonotonicity = enforceSequence
        )
        if (!recorded) {
            return MessageValidationResult.ReplayDetected(
                "Duplicate message ID or out-of-order sequence number: id=${message.messageId}, seq=${message.sequenceNumber}"
            )
        }

        return MessageValidationResult.Valid
    }

    fun resetReplayState() {
        replayProtection.reset()
    }

    private fun extractSessionToken(message: ProtocolMessage): String? {
        return when (message) {
            is ProtocolMessage.Heartbeat -> message.sessionToken
            is ProtocolMessage.HeartbeatAck -> message.sessionToken
            is ProtocolMessage.SessionControlRequest -> message.sessionToken
            is ProtocolMessage.SessionControlAck -> message.sessionToken
            is ProtocolMessage.PolicyUpdateRequest -> message.sessionToken
            is ProtocolMessage.PolicyUpdateAck -> message.sessionToken
            is ProtocolMessage.DisconnectOrRevoke -> message.sessionToken
            else -> null
        }
    }

    companion object {
        const val DEFAULT_MAX_CLOCK_DRIFT_MS: Long = 60_000L // 60 seconds
    }
}
